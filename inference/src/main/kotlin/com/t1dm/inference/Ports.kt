package com.t1dm.inference

import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.ModelPrediction

/**
 * A trailing per-5-min-step BG series. [anchorTsMs] is the last MEASURED sample (the freshness
 * anchor, §3.6-D — interpolated carry-forward never resets it); [gridStartMs] is the grid timestamp
 * of `mgdl[0]`, so the carb/insulin channels reconstructed over `[gridStartMs, +size·STEP)` align
 * index-for-index with the BG array feeding `build_context`.
 */
data class BgSeries(val mgdl: DoubleArray, val anchorTsMs: Long, val gridStartMs: Long) {
    override fun equals(other: Any?): Boolean =
        other is BgSeries && anchorTsMs == other.anchorTsMs && gridStartMs == other.gridStartMs &&
            mgdl.contentEquals(other.mgdl)

    override fun hashCode(): Int {
        var h = mgdl.contentHashCode()
        h = 31 * h + anchorTsMs.hashCode()
        h = 31 * h + gridStartMs.hashCode()
        return h
    }
}

/**
 * Supplies the shared BG history a cycle conditions on plus the measured-context coverage the
 * WARMUP gate reads (inference-runtime.md). The `:app` implementation projects the active source's
 * grid-aligned readings; a shorter-than-`minSteps` return means "still collecting context". How
 * much context that is comes from the model's own descriptor — `MIN_CONTEXT_PATCHES`, which the
 * current models put at several days — and never from a constant here.
 */
interface BgHistoryProvider {
    /** Newest-last mg/dL series of at most [maxSteps] 5-min steps, or `null` if under [minSteps].
     *  May stand a model-reconstructed sample in for a slot the sensor never covered — see
     *  [dosingBgSeries] for the series that may not. */
    suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries?

    /**
     * The series a DOSE may be scored on: the same window, with no reconstructed sample in it.
     *
     * A gap fill is what a model thinks was there. Conditioning a displayed forecast on one is the
     * point of having it; conditioning a dose recommendation on one closes a loop between a model's
     * own output and the advice derived from it, which is the one place this app does not let a
     * derived number back in.
     *
     * Deliberately NOT defaulted: every provider states which series it hands the dose path, and a
     * provider that cannot tell the two apart says so by returning its plain one explicitly.
     */
    suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries?

    /** Count of MEASURED (non-interpolated, NORMAL) readings within the trailing [windowSteps]
     *  grid slots — the WARMUP gate's numerator. Default 0 keeps non-Room fakes total. */
    suspend fun measuredStepsInWindow(windowSteps: Int): Int = 0

    /**
     * The same trailing series, but with **gaps left as `NaN` instead of carried forward** — the
     * input a model is FITTED on rather than conditioned on.
     *
     * [recentBgSeries] carries the last value across a gap, which is right for inference (the model
     * needs a dense context and the freshness anchor tracks the real reading) and wrong for a fit:
     * a long carry-forward run is a flat stretch that never happened, and a least-squares fit would
     * happily learn persistence from it. `SPEC/invariants.md` §1 makes a filled value a presentation
     * step and never something to be treated as measured, so the fit sees the gaps and drops the
     * rows that span them.
     *
     * Default `null` — a provider that cannot distinguish measured from carried-forward must not
     * pretend it can, and the baseline fit then declines rather than fitting on filled data.
     */
    suspend fun fitBgSeries(maxSteps: Int, minSteps: Int): BgSeries? = null
}

/**
 * The resolved carb and insulin curves overlapping a window — the events the baseline derives its
 * strictly-causal IOB/COB columns from.
 *
 * Distinct from [ContextChannelSource], which returns per-step channel AMOUNTS already summed onto
 * the grid. On-board is the remaining forward area of each event, and computing it causally needs
 * to know which event contributed what and when it started, which a summed channel has thrown away.
 * `:app` binds this to the same `ChannelBuilder` the channels come from, so both views are built
 * from one set of logged records.
 */
fun interface CurveEventSource {
    /** Every carb and insulin curve whose action overlaps `[fromMs, toMs)`, including the tails of
     *  events that started before it. */
    suspend fun events(fromMs: Long, toMs: Long): List<CurveEvent>
}

/**
 * Persists the fitted classical baseline — weights and band estimator as one unit, because they are
 * one model (see [com.t1dm.core.model.BaselineModel]). Implemented in `:app` over the Room `kv`
 * store to keep `:inference` free of a schema dependency; a `null` store keeps the fit in memory
 * for the session.
 */
interface BaselineStore {
    suspend fun load(): BaselineModel?
    suspend fun save(model: BaselineModel)
    suspend fun clear()
}

/**
 * The reconstructed carb-appearance (feat 1) + insulin-action (feat 2) channels over a grid window,
 * built once per cycle from the logged meals/doses/basal via the `:data` `ChannelBuilder` (PLAN
 * §3.3). Kept as a port so `:inference` neither reaches into Room nor pins the curve engine; `:app`
 * binds it to `ChannelBuilder.contextChannels`. `null` (unwired) ⇒ the `normalize(0)` no-dose
 * baseline, preserving the Phase-2 behaviour.
 */
fun interface ContextChannelSource {
    /** Per-5-min amounts over `[gridStartMs, gridStartMs + nSteps·STEP)`. */
    suspend fun channels(gridStartMs: Long, nSteps: Int): ModelChannels
}

/**
 * The three reconstructed input channels beside BG, index-aligned to one grid window.
 *
 * [exercise] is grams of carbohydrate EQUIVALENT disposed per bucket — a positive magnitude in
 * its own channel, on the scale the model was trained at. It is never a negative carbohydrate
 * value in [carb], and never an intensity, a duration or an energy.
 */
data class ModelChannels(
    val carb: DoubleArray,
    val insulin: DoubleArray,
    val exercise: DoubleArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ModelChannels && carb.contentEquals(other.carb) &&
            insulin.contentEquals(other.insulin) && exercise.contentEquals(other.exercise)

    override fun hashCode(): Int =
        (carb.contentHashCode() * 31 + insulin.contentHashCode()) * 31 + exercise.contentHashCode()

    companion object {
        /** The no-event baseline for a window of [n] steps. */
        fun zero(n: Int) = ModelChannels(DoubleArray(n), DoubleArray(n), DoubleArray(n))
    }
}

/**
 * The COMMITTED dose tails carried into the PREDICTION ZONE (SPEC §3.3). A meal/bolus logged just
 * before the now-boundary is still absorbing over the next hours — that action is committed, not a
 * what-if — so the main-view forecast must see it continue past the boundary rather than vanish
 * (which would make the model read a physically-impossible carb drop-off and dip the forecast). The
 * `:app` binding is `ChannelBuilder.futureOverrides(rollStartMs, nFutureSteps, announced = emptyList,
 * candidate = null)`: the store's own PK/Ra-tail reads + auto-extended basal, WITHOUT any announced
 * or candidate dose — the same curve engine the `RollingForecaster` baseline roll uses, so the
 * directional response is identical (a committed meal RAISES, a committed insulin LOWERS). `null`
 * (unwired) ⇒ the `normalize(0)` no-dose baseline, preserving the pre-Phase-4c behaviour.
 */
fun interface FutureOverrideSource {
    /** Per-5-min committed amounts over `[rollStartMs, rollStartMs + nFutureSteps·STEP)`. A bout
     *  that has already ended is still disposing glucose, so its tail is committed too. */
    suspend fun overrides(rollStartMs: Long, nFutureSteps: Int): ModelChannels
}

/**
 * Persists a cycle's predictions and reloads the latest on restart (Phase 2:
 * "persist a prediction … expose via StateFlow"). Implemented in `:app` over the Room `kv` store to
 * keep `:inference` free of a schema dependency; the dedicated `prediction` table + `PREDICTIONS`
 * outbox enqueue land in Phase 3.
 */
interface PredictionStore {
    suspend fun persist(cycleTsMs: Long, predictions: List<ModelPrediction>)
    suspend fun loadLast(): List<ModelPrediction>?
}

/** A durable cumulative inference-telemetry counter for one model (Phase 7C — Models drill-down). */
data class CumulativeTelemetry(val predictions: Long, val totalInferenceMs: Double)

/**
 * Persists the CUMULATIVE per-model inference telemetry (#predictions + total backend wall-time) so
 * the Models drill-down's "total time spent on inference" survives process restarts. Implemented in
 * `:app` over the Room `kv` store (a single JSON blob) to keep `:inference` free of a schema
 * dependency. A `null` store (tests / unwired) ⇒ the counters live only in memory for the session.
 */
interface TelemetryStore {
    suspend fun load(): Map<String, CumulativeTelemetry>
    suspend fun save(all: Map<String, CumulativeTelemetry>)
}

/**
 * Supplies the adapter attached to a model, if any — the seam through which a fitted
 * personalisation reaches the LIVE forecast.
 *
 * Read fresh every cycle, so attaching or detaching one takes effect on the next tick rather than
 * at the next process start. `null` (unwired, or nothing attached) is the frozen model, which is
 * what every model starts as.
 *
 * **An attached adapter changes the fan the app stores, alarms on and doses off.** That is the
 * point of attaching one, and it is why attaching is an explicit act with the held-out numbers in
 * front of the user, and why the model's own conformal correction and realised-accuracy history are
 * dropped when it changes: both describe the forecaster that was there before.
 */
fun interface LoraStore {
    suspend fun attached(modelId: String): LoraWeights?
}
