package com.t1dm.calc

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.data.curve.ChannelBuilder
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.backend.GraphIo
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The production [ForecastPort] (PLAN Phase 4 §5 `RollingForecaster`). It rolls the selected
 * fp32-authoritative model to the full action window by re-feeding the median (INFERENCE.md §9),
 * conditioning each window's prediction zone on the announced-future + candidate curves from
 * [ChannelBuilder.futureOverrides], and gating every roll through the Rust degeneracy guard (§3.6-B)
 * before its median is re-fed.
 *
 * **Fail-closed by construction.** No selected model, too little context, an exception, or any roll
 * failing `forecast_degeneracy_check` yields a non-eligible [PredFan] — never a throw, never a
 * fabricated band. This path is exercised on-device (PLAN Phase 4 "Verify on K90"); the calculator's
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
        val model = selected.current() ?: return missing(request, "no selected model")
        val desc = model.descriptor
        // PREDICTION_PATCHES isn't a descriptor field; derive it from the validated horizon (2 h ⇒ 4).
        val predPatches = desc.predictionHorizonHours * HorizonPolicy.STEPS_PER_HOUR / desc.patchSize
        val predSteps = predPatches * desc.patchSize                   // 4·6 = 24 steps per forward
        if (predSteps <= 0) return missing(request, "descriptor prediction window is 0")

        val minSteps = desc.minContextPatches * desc.patchSize
        val maxSteps = desc.maxContextPatches * desc.patchSize
        val series = history.recentBgSeries(maxSteps, minSteps)
            ?: return missing(request, "still collecting context (< $minSteps steps)")

        val nCtx = series.mgdl.size
        if (nCtx % desc.patchSize != 0 || nCtx < minSteps) return missing(request, "context length $nCtx not a valid multiple")

        // The whole future window (store tails + announced + candidate + auto-extended basal), sliced
        // per roll into prediction-zone dose channels. Also yields the logged-only IOB/COB.
        val contextStartMs = series.anchorTsMs - (nCtx - 1).toLong() * STEP_MS
        val future = channels.futureOverrides(request.rollStartMs, request.fullRollSteps, request.announced, request.candidate)
        val ctx0 = channels.contextChannels(contextStartMs, nCtx)

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
                return missing(request, "forecast forward failed: ${t.message}")
            }

            val status = withContext(dispatchers.default) { native.forecastDegeneracyCheck(forecast) }
            if (status != ForecastStatus.OK) {
                return PredFan(request.candidateU, outSteps, STEP_MS, request.validatedSteps, status, ForecastEligibility.DEGENERATE)
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

        val trimmed = if (outSteps.size > request.fullRollSteps) outSteps.subList(0, request.fullRollSteps).toList() else outSteps
        return PredFan(request.candidateU, trimmed, STEP_MS, request.validatedSteps, ForecastStatus.OK, ForecastEligibility.ELIGIBLE)
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
