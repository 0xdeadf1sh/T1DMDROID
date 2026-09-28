package com.t1dm.inference

import com.t1dm.core.model.LoraSample
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.ModelPrediction

/** mg/dL per 5-min step; anchorTsMs is the last MEASURED sample, never reset by carry-forward. */
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

/** Null port: counterfactual branch doesn't run — no verdict, ABSENT, attach refused. */
fun interface ProbeInsulinPort {
    /** Absolute rapid-insulin action per 5-min step, steps long, zero-padded past curve end. */
    suspend fun action(units: Double, steps: Int): DoubleArray
}

interface BgHistoryProvider {
    /** Newest-last mg/dL, ≤maxSteps steps, null under minSteps. May splice reconstructed gaps. */
    suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries?

    /** Dose-scoring series: no reconstructed sample, so a model's output never feeds advice. */
    suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries?

    /** MEASURED (non-interpolated, NORMAL) in trailing windowSteps slots; WARMUP numerator. */
    suspend fun measuredStepsInWindow(windowSteps: Int): Int = 0

    /** Fit input: gaps stay NaN, never carried forward (SPEC/invariants.md §1). Default null. */
    suspend fun fitBgSeries(maxSteps: Int, minSteps: Int): BgSeries? = null

    /** Slots holding the model's own output; never a fit target/window (SPEC/invariants.md §1). */
    suspend fun reconstructedSlots(maxSteps: Int): Set<Long> = emptySet()

    /** Every sensor's fit input, trusted first. Default: the trusted one alone. */
    suspend fun fitSources(maxSteps: Int, minSteps: Int): List<FitSource> {
        val dense = recentBgSeries(maxSteps, minSteps) ?: return emptyList()
        val measured = fitBgSeries(maxSteps, minSteps) ?: return emptyList()
        val fromModel = runCatching { reconstructedSlots(maxSteps) }.getOrElse { emptySet() }
        return listOf(FitSource(dense, measured, fromModel))
    }
}

/** One sensor: [dense] as the model reads it, [measured] NaN at gaps, [reconstructed] slot ms. */
class FitSource(val dense: BgSeries, val measured: BgSeries, val reconstructed: Set<Long>)

/** A fit's windows; [nAtEvent] of them sit right beside a meal or bolus. */
class LoraWindows(val samples: List<LoraSample>, val nAtEvent: Int)

/** Carb and bolus start instants in `[fromMs, toMs)`, ms, ascending; basal is not an event. */
fun interface EventOnsetSource {
    suspend fun onsets(fromMs: Long, toMs: Long): LongArray
}

/** Carb (feat1) and insulin-action (feat2) channels over grid window; null ⇒ no-dose baseline. */
fun interface ContextChannelSource {
    /** Per-5-min amounts over `[gridStartMs, gridStartMs + nSteps·STEP)`. */
    suspend fun channels(gridStartMs: Long, nSteps: Int): ModelChannels
}

/** Grid-aligned per-5-min amounts: carb grams and insulin action, the model's only two signals. */
data class ModelChannels(
    val carb: DoubleArray,
    val insulin: DoubleArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ModelChannels && carb.contentEquals(other.carb) && insulin.contentEquals(other.insulin)

    override fun hashCode(): Int = carb.contentHashCode() * 31 + insulin.contentHashCode()

    companion object {
        fun zero(n: Int) = ModelChannels(DoubleArray(n), DoubleArray(n))
    }
}

/** Committed dose tails into the prediction zone (SPEC §3.3), not what-if; null ⇒ no-dose. */
fun interface FutureOverrideSource {
    /** Per-5-min committed amounts; an ended bout still disposes glucose, so its tail counts. */
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

/** Model id to artifact fingerprint, across restarts; fingerprints compare for equality only. */
interface ArtifactLedger {
    suspend fun load(): Map<String, String>
    suspend fun save(all: Map<String, String>)
}

/** The selected model id across restarts; null [load] is no choice yet. */
interface SelectionStore {
    suspend fun load(): String?
    suspend fun save(id: String)
}

/** Read fresh each cycle; null is the frozen model. */
fun interface LoraStore {
    suspend fun attached(modelId: String): LoraWeights?
}
