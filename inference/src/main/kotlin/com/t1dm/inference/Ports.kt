package com.t1dm.inference

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
 * Supplies the shared BG history a cycle conditions on (PLAN.private.md Phase 2 deliverable 4) plus
 * the measured-context coverage the WARMUP gate reads (inference-runtime.md). The `:app`
 * implementation projects the active source's grid-aligned readings; a shorter-than-`minSteps`
 * return means "still collecting context" (the model needs ≥16 patches = 8 h).
 */
interface BgHistoryProvider {
    /** Newest-last mg/dL series of at most [maxSteps] 5-min steps, or `null` if under [minSteps]. */
    suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries?

    /** Count of MEASURED (non-interpolated, NORMAL) readings within the trailing [windowSteps]
     *  grid slots — the WARMUP gate's numerator. Default 0 keeps non-Room fakes total. */
    suspend fun measuredStepsInWindow(windowSteps: Int): Int = 0
}

/**
 * The reconstructed carb-appearance (feat 1) + insulin-action (feat 2) channels over a grid window,
 * built once per cycle from the logged meals/doses/basal via the `:data` `ChannelBuilder` (PLAN
 * §3.3). Kept as a port so `:inference` neither reaches into Room nor pins the curve engine; `:app`
 * binds it to `ChannelBuilder.contextChannels`. `null` (unwired) ⇒ the `normalize(0)` no-dose
 * baseline, preserving the Phase-2 behaviour.
 */
fun interface ContextChannelSource {
    /** `(carb, insulin)` per-5-min amounts over `[gridStartMs, gridStartMs + nSteps·STEP)`. */
    suspend fun channels(gridStartMs: Long, nSteps: Int): Pair<DoubleArray, DoubleArray>
}

/**
 * The COMMITTED dose tails carried into the PREDICTION ZONE (PLAN §3.3). A meal/bolus logged just
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
    /** `(carb, insulin)` per-5-min committed amounts over `[rollStartMs, rollStartMs + nFutureSteps·STEP)`. */
    suspend fun overrides(rollStartMs: Long, nFutureSteps: Int): Pair<DoubleArray, DoubleArray>
}

/**
 * Persists a cycle's predictions and reloads the latest on restart (PLAN.private.md Phase 2:
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
