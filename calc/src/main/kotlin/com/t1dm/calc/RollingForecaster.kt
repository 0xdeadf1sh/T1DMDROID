package com.t1dm.calc

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.RolledForecast
import com.t1dm.data.curve.ChannelBuilder
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.backend.GraphIo
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The production [ForecastPort] (Phase 4 §5 `RollingForecaster`). It rolls the selected
 * fp32-authoritative model to the full action window by re-feeding the median (INFERENCE.md §9),
 * conditioning each window's prediction zone on the announced-future + candidate curves from
 * [ChannelBuilder.futureOverrides], and gating every roll through the Rust degeneracy guard (§3.6-B)
 * before its median is re-fed.
 *
 * **Fail-closed by construction.** No selected model, too little context, an exception, or any roll
 * failing `forecast_degeneracy_check` yields a non-eligible [PredFan] — never a throw, never a
 * fabricated band. This path is exercised on-device (Phase 4 "Verify on K90"); the calculator's
 * safety logic is unit-tested against a deterministic fake port instead, since the host build has no
 * `.pte` and `StubNativeCore` leaves the model pre/post as `TODO`.
 */
class RollingForecaster(
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
    private val channels: ChannelBuilder,
    private val history: BgHistoryProvider,
    private val selected: SelectedModelProvider,
) : ForecastPort {

    override suspend fun roll(request: ForecastRequest): PredFan {
        val r = rollInternal(request)
        return when (r.eligibility) {
            ForecastEligibility.MISSING -> missing(request, r.reason ?: "forecast unavailable")
            else -> PredFan(request.candidateU, r.steps, STEP_MS, request.validatedSteps, r.status, r.eligibility)
        }
    }

    /**
     * The ON-DEMAND, display-only rolled forecast (issue I2). Runs on the SAME fp32-authoritative
     * [rollInternal] path as the dose calculator (the exact autoregressive re-feed + PER-ROLL Rust
     * degeneracy guard — no math is duplicated), but produces a [RolledForecast] instead of a
     * [PredFan]. The result is EPHEMERAL and carries its anchor so the graph can place and auto-pan to
     * it; it is a distinct type that CANNOT enter `:calc`, the top-bar indicator, or the notification
     * countdown (all of which read [PredFan] / `InferenceState`, never a [RolledForecast]).
     *
     * Fail-closed: any per-roll degeneracy STOPS the roll, keeps the valid prefix, and marks the
     * result [RolledForecast.degenerate] with a plain reason. A missing model / context yields
     * [RolledForecast.missing]. Never throws.
     */
    suspend fun rollForDisplay(nowMs: Long, requestedHours: Double, validatedSteps: Int): RolledForecast {
        val fullRollSteps = Math.round(requestedHours * HorizonPolicy.STEPS_PER_HOUR).toInt().coerceAtLeast(1)
        val requestedRolls = (fullRollSteps + validatedSteps - 1) / validatedSteps.coerceAtLeast(1)
        // Baseline roll: the already-committed meals/doses (+ auto-extended basal) only. announced and
        // candidate are empty — exactly the dashboard's baseline conditioning (matches the calculator's
        // 0 U baseline), so the displayed roll agrees with the cycle forecast over the first 2 h.
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
            validatedSteps = validatedSteps,
            requestedHours = requestedHours,
            eligible = eligible,
            degenerate = degenerate,
            reason = reason,
            completedRolls = r.completedRolls,
            requestedRolls = requestedRolls,
        )
    }

    /** The accumulated result of the shared rolling loop, before it is projected onto a [PredFan] or a
     *  [RolledForecast]. [anchorTsMs] is null only when no context series could be obtained. */
    private data class Rolled(
        val anchorTsMs: Long?,
        val steps: List<FanStep>,
        val status: ForecastStatus,
        val eligibility: ForecastEligibility,
        val reason: String?,
        val completedRolls: Int,
    )

    /**
     * The single source of the rolling math, shared by [roll] (dose path) and [rollForDisplay]
     * (display path). Re-feeds the median per roll and gates EVERY roll on the Rust degeneracy check;
     * on the first degeneracy it stops and returns the valid prefix marked [ForecastEligibility.DEGENERATE].
     */
    private suspend fun rollInternal(request: ForecastRequest): Rolled {
        val model = selected.current()
            ?: return Rolled(null, emptyList(), ForecastStatus.OK, ForecastEligibility.MISSING, "no selected model", 0)
        val desc = model.descriptor
        // PREDICTION_PATCHES isn't a descriptor field; derive it from the validated horizon (2 h ⇒ 4).
        val predPatches = desc.predictionHorizonHours * HorizonPolicy.STEPS_PER_HOUR / desc.patchSize
        val predSteps = predPatches * desc.patchSize                   // 4·6 = 24 steps per forward
        if (predSteps <= 0) return Rolled(null, emptyList(), ForecastStatus.OK, ForecastEligibility.MISSING, "descriptor prediction window is 0", 0)

        val minSteps = desc.minContextPatches * desc.patchSize
        val maxSteps = desc.maxContextPatches * desc.patchSize
        val series = history.recentBgSeries(maxSteps, minSteps)
            ?: return Rolled(null, emptyList(), ForecastStatus.OK, ForecastEligibility.MISSING, "still collecting context (< $minSteps steps)", 0)

        val nCtx = series.mgdl.size
        if (nCtx % desc.patchSize != 0 || nCtx < minSteps) {
            return Rolled(series.anchorTsMs, emptyList(), ForecastStatus.OK, ForecastEligibility.MISSING, "context length $nCtx not a valid multiple", 0)
        }

        // The whole future window (store tails + announced + candidate + auto-extended basal), sliced
        // per roll into prediction-zone dose channels. Also yields the logged-only IOB/COB.
        // §4-#4: origin context at series.gridStartMs and the future at the grid boundary one step past
        // the last context sample (predZoneStartMs == InferenceController's cycle path), re-anchoring the
        // candidate by the same shift so bucketize never rounds its leading step to idx<0 (curve.rs) and
        // under-counts the candidate's lowering effect — a fail-OPEN regression the re-anchor prevents.
        val predZoneStartMs = series.gridStartMs + nCtx.toLong() * STEP_MS      // == last reading + STEP (matches InferenceController)
        val candShift = predZoneStartMs - request.rollStartMs
        val shiftedCandidate = request.candidate?.map { it.copy(startMs = it.startMs + candShift) }  // (c) re-anchor to pred bucket 0
        val future = channels.futureOverrides(predZoneStartMs, request.fullRollSteps, request.announced, shiftedCandidate)  // (b) future origin
        val ctx0 = channels.contextChannels(series.gridStartMs, nCtx)           // (a) context origin

        // Rolling context windows (mg/dL BG, raw carb/insulin per step). r=0 is the real history;
        // subsequent rolls slide forward, the re-fed median + the future dose channels becoming context.
        val bg = ArrayDeque<Double>(series.mgdl.toList())
        val carb = ArrayDeque<Double>(ctx0.carb.toList())
        val insulin = ArrayDeque<Double>(ctx0.insulin.toList())

        val outSteps = ArrayList<FanStep>(request.fullRollSteps)
        var carrySpread = 0.0
        val nRolls = (request.fullRollSteps + predSteps - 1) / predSteps

        for (r in 0 until nRolls) {
            val base = r * predSteps
            val predCarb = sliceOrPad(future.carb, base, predSteps)
            val predInsulin = sliceOrPad(future.insulin, base, predSteps)

            val forecast: Forecast = try {
                withContext(dispatchers.default) {
                    val built = native.buildContext(desc, bg.toList(), carb.toList(), insulin.toList(), predCarb, predInsulin)
                    val input = GraphIo.graphInput(built, desc.negFill)
                    val out = withContext(dispatchers.inference) { model.run(input) }
                    native.assembleDecode(desc, out.headRaw.map { it.toDouble() }, built.lastBg, carrySpread)
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "roll %d failed for candidate %s U", r, request.candidateU)
                return Rolled(series.anchorTsMs, outSteps.toList(), ForecastStatus.OK, ForecastEligibility.MISSING, "forecast forward failed: ${t.message}", r)
            }

            val status = withContext(dispatchers.default) { native.forecastDegeneracyCheck(forecast) }
            if (status != ForecastStatus.OK) {
                // §3.6-B: a degenerate roll makes the WHOLE rolled forecast ineligible; keep the valid
                // prefix (rolls 0..r-1) so display can show what was sound, but never re-feed r.
                return Rolled(series.anchorTsMs, outSteps.toList(), status, ForecastEligibility.DEGENERATE, null, r)
            }

            appendWindow(forecast, predSteps, outSteps, desc.patchSize, predPatches)

            // Re-feed: the median (mg/dL) + this roll's announced doses become the next context tail.
            repeat(predSteps) { i ->
                if (bg.isNotEmpty()) { bg.removeFirst(); carb.removeFirst(); insulin.removeFirst() }
                bg.addLast(forecast.medianBg.getOrElse(i) { forecast.medianBg.lastOrNull() ?: 120.0 })
                carb.addLast(predCarb.getOrElse(i) { 0.0 })
                insulin.addLast(predInsulin.getOrElse(i) { 0.0 })
            }
            carrySpread += terminalHalfWidth(forecast)
        }

        val trimmed = if (outSteps.size > request.fullRollSteps) outSteps.subList(0, request.fullRollSteps).toList() else outSteps.toList()
        return Rolled(series.anchorTsMs, trimmed, ForecastStatus.OK, ForecastEligibility.ELIGIBLE, null, nRolls)
    }

    /** Map one roll's decoded [Forecast] (step-major mg/dL median + P·S·7 band fan) into [FanStep]s. */
    private fun appendWindow(f: Forecast, predSteps: Int, out: ArrayList<FanStep>, patchSize: Int, predPatches: Int) {
        val nq = 7
        val steps = predPatches * patchSize
        for (i in 0 until minOf(predSteps, steps)) {
            if (i !in f.medianBg.indices) break
            val median = f.medianBg[i]
            val lower = f.bandsMgdl.getOrElse(i * nq + 0) { median }       // τ=.05
            val upper = f.bandsMgdl.getOrElse(i * nq + (nq - 1)) { median } // τ=.95
            out.add(FanStep(median, lower, upper))
        }
    }

    /** The terminal-step risk-space half-width, seeding the next roll's carry_spread (§9.4). */
    private fun terminalHalfWidth(f: Forecast): Double {
        val nq = 7
        val steps = f.medianRisk.size
        if (steps == 0 || f.qTauRisk.size < steps * nq) return 0.0
        val last = steps - 1
        val top = f.qTauRisk[last * nq + (nq - 1)]
        val med = f.qTauRisk[last * nq + 3]
        return (top - med).coerceAtLeast(0.0)
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
