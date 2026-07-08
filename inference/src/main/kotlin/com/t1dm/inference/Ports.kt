package com.t1dm.inference

import com.t1dm.core.model.ModelPrediction

/** A trailing per-5-min-step BG series with the timestamp of its last (anchor) sample. */
data class BgSeries(val mgdl: DoubleArray, val anchorTsMs: Long) {
    override fun equals(other: Any?): Boolean =
        other is BgSeries && anchorTsMs == other.anchorTsMs && mgdl.contentEquals(other.mgdl)

    override fun hashCode(): Int = 31 * mgdl.contentHashCode() + anchorTsMs.hashCode()
}

/**
 * Supplies the shared BG history a cycle conditions on (PLAN.private.md Phase 2 deliverable 4). The
 * `:app` implementation projects the active source's grid-aligned readings; a shorter-than-`minSteps`
 * return means "still collecting context" (the model needs ≥16 patches = 8 h). Carb/insulin are the
 * `normalize(0)` baseline this phase — the real curve engine is Phase 4.
 */
fun interface BgHistoryProvider {
    /** Newest-last mg/dL series of at most [maxSteps] 5-min steps, or `null` if under [minSteps]. */
    suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries?
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
