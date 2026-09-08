package com.t1dm.inference

import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.ModelPrediction

/** Trailing per-5-min mg/dL. [sourceId] sensors differ ~28 mg/dL median; null=synthetic. */
data class BgSeries(
    val mgdl: DoubleArray,
    val anchorTsMs: Long,
    val gridStartMs: Long,
    val sourceId: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is BgSeries && anchorTsMs == other.anchorTsMs && gridStartMs == other.gridStartMs &&
            sourceId == other.sourceId && mgdl.contentEquals(other.mgdl)

    override fun hashCode(): Int {
        var h = mgdl.contentHashCode()
        h = 31 * h + anchorTsMs.hashCode()
        h = 31 * h + gridStartMs.hashCode()
        h = 31 * h + (sourceId?.hashCode() ?: 0)
        return h
    }
}

/** A port, not a constant: a whole unit in 5min is ~19σ OOD. Null ⇒ no counterfactual, ABSENT. */
fun interface ProbeInsulinPort {
    /** [units] rapid insulin action per 5-min step, [steps] long, zero-padded past curve end. */
    suspend fun action(units: Double, steps: Int): DoubleArray
}

interface BgHistoryProvider {
    /** Newest-last mg/dL, ≤[maxSteps], null under [minSteps]. May splice recon; dosing may not. */
    suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries?

    /** Series a DOSE may score on: no recon sample, so no model output feeds its own advice. */
    suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries?

    /** MEASURED (non-interp, NORMAL) readings in [windowSteps] — the WARMUP gates numerator. */
    suspend fun measuredStepsInWindow(windowSteps: Int): Int = 0

    /** Gaps as NaN, not carried forward: a fit never learns fake persistence (§1). */
    suspend fun fitBgSeries(maxSteps: Int, minSteps: Int): BgSeries? = null

    /** Slots in [maxSteps] holding a MODELS OWN OUTPUT (§1): never a fit target. NaN cant tell. */
    suspend fun reconstructedSlots(maxSteps: Int): Set<Long> = emptySet()
}

/** Baseline causal IOB/COB source. Distinct from [ContextChannelSource]'s summed, lossy amounts. */
fun interface CurveEventSource {
    /** Every curve whose action overlaps `[fromMs, toMs)`, including tails of earlier events. */
    suspend fun events(fromMs: Long, toMs: Long): List<CurveEvent>
}

/** Weights and band estimator persist as one unit, one model. Null store keeps fit in-memory. */
interface BaselineStore {
    suspend fun load(): BaselineModel?
    suspend fun save(model: BaselineModel)
    suspend fun clear()
}

/** Carb-appearance (feat 1)/insulin-action (feat 2) channels over a window. Null ⇒ no-dose. */
fun interface ContextChannelSource {
    /** Per-5-min amounts over `[gridStartMs, gridStartMs + nSteps·STEP)`. */
    suspend fun channels(gridStartMs: Long, nSteps: Int): ModelChannels
}

/** Index-aligned to a grid window. [exercise] is grams-carb EQUIVALENT, positive, per bucket. */
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
        fun zero(n: Int) = ModelChannels(DoubleArray(n), DoubleArray(n), DoubleArray(n))
    }
}

/** COMMITTED tails into PRED ZONE (§3.3), not what-if; excl. announced/candidate. Null=no-dose. */
fun interface FutureOverrideSource {
    /** Per-5-min committed amounts over the roll window. An ended bout still disposes; counts. */
    suspend fun overrides(rollStartMs: Long, nFutureSteps: Int): ModelChannels
}

interface PredictionStore {
    suspend fun persist(cycleTsMs: Long, predictions: List<ModelPrediction>)
    suspend fun loadLast(): List<ModelPrediction>?
}

data class CumulativeTelemetry(val predictions: Long, val totalInferenceMs: Double)

/** A `null` store keeps the counters in memory for the session. */
interface TelemetryStore {
    suspend fun load(): Map<String, CumulativeTelemetry>
    suspend fun save(all: Map<String, CumulativeTelemetry>)
}

/** Read fresh per cycle: attach/detach take effect next tick. Adapter change drops history. */
fun interface LoraStore {
    suspend fun attached(modelId: String): LoraWeights?
}
