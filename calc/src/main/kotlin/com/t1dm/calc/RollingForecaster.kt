package com.t1dm.calc

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.RolledForecast
import com.t1dm.data.curve.ChannelBuilder
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.InferenceControllerDefaults
import com.t1dm.inference.backend.GraphIo
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The production [ForecastPort]: rolls by re-feeding the median (INFERENCE.md §9), gating every
 *  roll on the Rust degeneracy guard (§3.6-B) before re-feeding it. No model, too little context, a
 *  throw, or a degenerate roll all yield a non-eligible [PredFan] rather than an exception. */
class RollingForecaster(
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
    private val channels: ChannelBuilder,
    private val history: BgHistoryProvider,
    private val selected: SelectedModelProvider,
    /** Read fresh only for a roll carrying no [ForecastRequest.smoothingWindow]. It MUST track the
     *  display cycle's window, or the display roll anchors on a different `last_bg` than the forecast
     *  drawn beside it (INFERENCE.md §7.1). */
    private val smoothingWindowProvider: suspend () -> Int = { InferenceControllerDefaults.SAVGOL_WINDOW },
) : ForecastPort {

    override suspend fun roll(request: ForecastRequest): PredFan {
        val r = rollInternal(request)
        return when (r.eligibility) {
            ForecastEligibility.MISSING -> missing(request, r.reason ?: "forecast unavailable")
            else -> PredFan(request.candidateU, r.steps, STEP_MS, request.validatedSteps, r.status, r.eligibility)
        }
    }

    /** DISPLAY-ONLY: [RolledForecast] is a distinct type that cannot enter `:calc`, which reads
     *  [PredFan] only. A per-roll degeneracy stops the roll and keeps the valid prefix; a missing
     *  model yields [RolledForecast.missing]. Never throws. */
    suspend fun rollForDisplay(nowMs: Long, requestedHours: Double, validatedSteps: Int): RolledForecast {
        val fullRollSteps = Math.round(requestedHours * HorizonPolicy.STEPS_PER_HOUR).toInt().coerceAtLeast(1)
        val requestedRolls = (fullRollSteps + validatedSteps - 1) / validatedSteps.coerceAtLeast(1)
        val request = ForecastRequest(
            rollStartMs = nowMs,
            fullRollSteps = fullRollSteps,
            validatedSteps = validatedSteps,
            announced = emptyList(),
            candidate = null,
            candidateU = 0.0,
        )
        val r = rollInternal(request)
        val anchor = r.anchorTsMs
        if (anchor == null || (r.eligibility == ForecastEligibility.MISSING)) {
            return RolledForecast.missing(requestedHours, requestedRolls, r.reason ?: "forecast unavailable")
        }
        val n = r.steps.size
        val median = DoubleArray(n) { r.steps[it].medianBg }
        val lower = DoubleArray(n) { r.steps[it].lowerBg }
        val upper = DoubleArray(n) { r.steps[it].upperBg }
        // Step-major, and only when EVERY step has a fan: a ragged array draws some steps as three
        // bands and the rest as one.
        val nq = r.steps.firstOrNull()?.bandsMgdl?.size ?: 0
        val bands = if (nq > 0 && r.steps.all { it.bandsMgdl.size == nq }) {
            DoubleArray(n * nq) { i -> r.steps[i / nq].bandsMgdl[i % nq] }
        } else {
            DoubleArray(0)
        }
        val degenerate = r.eligibility == ForecastEligibility.DEGENERATE
        val eligible = r.eligibility == ForecastEligibility.ELIGIBLE
        val validHours = r.completedRolls * (validatedSteps / HorizonPolicy.STEPS_PER_HOUR.toDouble())
        val reason = when {
            degenerate -> "The rolled forecast degenerated after about %.1f h; only the valid portion is shown.".format(validHours)
            else -> null
        }
        return RolledForecast(
            anchorTsMs = anchor,
            stepMs = STEP_MS,
            medianBg = median,
            lowerBg = lower,
            upperBg = upper,
            bandsMgdl = bands,
            validatedSteps = validatedSteps,
            requestedHours = requestedHours,
            eligible = eligible,
            degenerate = degenerate,
            reason = reason,
            completedRolls = r.completedRolls,
            requestedRolls = requestedRolls,
        )
    }

    /** [anchorTsMs] is null only when no context series could be obtained. */
    private data class Rolled(
        val anchorTsMs: Long?,
        val steps: List<FanStep>,
        val status: ForecastStatus,
        val eligibility: ForecastEligibility,
        val reason: String?,
        val completedRolls: Int,
    )

    /** The single source of the rolling math, shared by the dose and display paths. On the first
     *  degeneracy it stops and returns the valid prefix. */
    private suspend fun rollInternal(request: ForecastRequest): Rolled {
        val model = selected.current()
            ?: return Rolled(null, emptyList(), ForecastStatus.OK, ForecastEligibility.MISSING, "no selected model", 0)
        val desc = model.descriptor
        // PREDICTION_PATCHES isn't a descriptor field; derive it from the validated horizon.
        val predPatches = desc.predictionHorizonHours * HorizonPolicy.STEPS_PER_HOUR / desc.patchSize
        val predSteps = predPatches * desc.patchSize
        if (predSteps <= 0) return Rolled(null, emptyList(), ForecastStatus.OK, ForecastEligibility.MISSING, "descriptor prediction window is 0", 0)

        val minSteps = desc.minContextPatches * desc.patchSize
        val maxSteps = desc.maxContextPatches * desc.patchSize
        val series = history.dosingBgSeries(maxSteps, minSteps)
            ?: return Rolled(null, emptyList(), ForecastStatus.OK, ForecastEligibility.MISSING, "still collecting context (< $minSteps steps)", 0)

        val nCtx = series.mgdl.size
        if (nCtx % desc.patchSize != 0 || nCtx < minSteps) {
            return Rolled(series.anchorTsMs, emptyList(), ForecastStatus.OK, ForecastEligibility.MISSING, "context length $nCtx not a valid multiple", 0)
        }

        // The candidate is re-anchored onto the prediction zone's first bucket, or bucketize rounds
        // its leading step to idx<0 (curve.rs) and under-counts its lowering effect — fail-OPEN.
        val predZoneStartMs = series.gridStartMs + nCtx.toLong() * STEP_MS      // matches InferenceController
        val candShift = predZoneStartMs - request.rollStartMs
        val shiftedCandidate = request.candidate?.map { it.copy(startMs = it.startMs + candShift) }
        val future = channels.futureOverrides(predZoneStartMs, request.fullRollSteps, request.announced, shiftedCandidate)
        val ctx0 = channels.contextChannels(series.gridStartMs, nCtx)

        // mg/dL BG; raw carb/insulin/exercise per step.
        val bg = ArrayDeque<Double>(series.mgdl.toList())
        val carb = ArrayDeque<Double>(ctx0.carb.toList())
        val insulin = ArrayDeque<Double>(ctx0.insulin.toList())
        val exercise = ArrayDeque<Double>(ctx0.exercise.toList())

        val outSteps = ArrayList<FanStep>(request.fullRollSteps)
        var carrySpread = emptyList<Double>()
        val nRolls = (request.fullRollSteps + predSteps - 1) / predSteps
        // Once for the whole roll: smoothing roll r+1 differently from roll r would push the re-fed
        // median across a filter discontinuity.
        val smoothingWindow = InferenceControllerDefaults.nearestSmoothingStop(
            request.smoothingWindow
                ?: runCatching { smoothingWindowProvider() }.getOrNull()
                ?: InferenceControllerDefaults.SAVGOL_WINDOW,
        )

        for (r in 0 until nRolls) {
            val base = r * predSteps
            val predCarb = sliceOrPad(future.carb, base, predSteps)
            val predInsulin = sliceOrPad(future.insulin, base, predSteps)
            val predExercise = sliceOrPad(future.exercise, base, predSteps)

            val forecast: Forecast = try {
                withContext(dispatchers.default) {
                    val built = native.buildGraphInput(
                        desc, bg.toList(), carb.toList(), insulin.toList(), exercise.toList(),
                        predCarb, predInsulin, predExercise,
                        emptyList(), true, smoothingWindow,
                    )
                    val out = withContext(dispatchers.inference) { model.run(GraphIo.tensors(built)) }
                    // Not a fallback: `adapt` throws if an attached adapter cannot be applied.
                    val head = model.adapt(out, built.mSlots) ?: out.headRaw.map { it.toDouble() }
                    val all = native.assembleDecode(
                        desc, head, built.anchors, built.slotPatch, built.nMasked, carrySpread,
                    )
                    native.forecastSlice(all, built.firstForecastPatch, built.t)
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "roll %d failed for candidate %s U", r, request.candidateU)
                return Rolled(series.anchorTsMs, outSteps.toList(), ForecastStatus.OK, ForecastEligibility.MISSING, "forecast forward failed: ${t.message}", r)
            }

            val status = withContext(dispatchers.default) { native.forecastDegeneracyCheck(desc, forecast) }
            if (status != ForecastStatus.OK) {
                // §3.6-B: keep the valid prefix for display, but never re-feed roll r.
                return Rolled(series.anchorTsMs, outSteps.toList(), status, ForecastEligibility.DEGENERATE, null, r)
            }

            appendWindow(forecast, predSteps, outSteps, desc.patchSize, predPatches)

            repeat(predSteps) { i ->
                if (bg.isNotEmpty()) {
                    bg.removeFirst(); carb.removeFirst(); insulin.removeFirst(); exercise.removeFirst()
                }
                bg.addLast(forecast.medianBg.getOrElse(i) { forecast.medianBg.lastOrNull() ?: 120.0 })
                carb.addLast(predCarb.getOrElse(i) { 0.0 })
                insulin.addLast(predInsulin.getOrElse(i) { 0.0 })
                exercise.addLast(predExercise.getOrElse(i) { 0.0 })
            }
            // The fan just measured already carries `carrySpread`, so this REPLACES it rather than
            // folding in: folding would count this roll's carry twice (SPEC/inference.md §9).
            carrySpread = terminalOffsets(forecast)
        }

        val trimmed = if (outSteps.size > request.fullRollSteps) outSteps.subList(0, request.fullRollSteps).toList() else outSteps.toList()
        return Rolled(series.anchorTsMs, trimmed, ForecastStatus.OK, ForecastEligibility.ELIGIBLE, null, nRolls)
    }

    /** [f] is step-major: mg/dL median + a P·S·7 band fan. */
    private fun appendWindow(f: Forecast, predSteps: Int, out: ArrayList<FanStep>, patchSize: Int, predPatches: Int) {
        val nq = 7
        val steps = predPatches * patchSize
        for (i in 0 until minOf(predSteps, steps)) {
            if (i !in f.medianBg.indices) break
            val median = f.medianBg[i]
            // One read: the outer pair is columns 0 and 6 of these same seven.
            val fan = if (f.bandsMgdl.size >= (i + 1) * nq) {
                List(nq) { k -> f.bandsMgdl[i * nq + k] }
            } else {
                emptyList()
            }
            val lower = fan.firstOrNull() ?: median       // τ=.05
            val upper = fan.lastOrNull() ?: median        // τ=.95
            out.add(FanStep(median, lower, upper, fan))
        }
    }

    /** Terminal-step risk-space spread per level, in `carry_spread`'s own layout
     *  `[up .75 .9 .95 | dn .25 .1 .05]` (SPEC/inference.md §9.4). Per level, not one half-width:
     *  a shared carry hands the .75 edge the whole .05–.95 accumulation and the pairs collapse. */
    private fun terminalOffsets(f: Forecast): List<Double> {
        val nq = 7
        val nSpreads = nq / 2
        val steps = f.medianRisk.size
        if (steps == 0 || f.qTauRisk.size < steps * nq) return emptyList()
        val row = (steps - 1) * nq
        val med = f.qTauRisk[row + nSpreads]
        return List(nSpreads) { k -> (f.qTauRisk[row + nSpreads + 1 + k] - med).coerceAtLeast(0.0) } +
            List(nSpreads) { k -> (med - f.qTauRisk[row + nSpreads - 1 - k]).coerceAtLeast(0.0) }
    }

    private fun sliceOrPad(src: DoubleArray, from: Int, len: Int): List<Double> =
        List(len) { src.getOrElse(from + it) { 0.0 } }

    private fun missing(request: ForecastRequest, why: String): PredFan {
        Timber.tag(TAG).i("forecast MISSING for %s U: %s", request.candidateU, why)
        return PredFan(request.candidateU, emptyList(), STEP_MS, request.validatedSteps, ForecastStatus.OK, ForecastEligibility.MISSING)
    }

    private companion object {
        const val TAG = "DoseCalc"
        const val STEP_MS = 300_000L
    }
}
