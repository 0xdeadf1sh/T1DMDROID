package com.t1dm.inference

import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.ModelPrediction

/** Trailing per-5-min-step mg/dL. [anchorTsMs] is the last MEASURED sample; carry-forward never
 *  resets it. [gridStartMs] is the grid timestamp of `mgdl[0]`. [sourceId] is the CGM behind every
 *  value — two sensors differ by a median 28 mg/dL — null only for a synthetic context. */
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

/** A port, not a constant: a unit of insulin is a CURVE, and the whole unit in one 5-minute bucket
 *  is roughly nineteen sigma out of distribution once normalised. Not defaulted at the use site — a
 *  null port means the counterfactual branch does not run: no verdict, `ABSENT`, attach refused. */
fun interface ProbeInsulinPort {
    /** [units] of rapid insulin as absolute action per 5-minute step, [steps] long, zero-padded past
     *  the curve's end. Same resolver as a logged bolus. */
    suspend fun action(units: Double, steps: Int): DoubleArray
}

interface BgHistoryProvider {
    /** Newest-last mg/dL, at most [maxSteps] 5-min steps, null under [minSteps]. May splice a
     *  model-reconstructed sample into a slot the sensor never covered; [dosingBgSeries] may not. */
    suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries?

    /** The series a DOSE may be scored on: no reconstructed sample in it, so a model's own output
     *  never feeds the advice derived from it. Deliberately not defaulted. */
    suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries?

    /** MEASURED (non-interpolated, NORMAL) readings in the trailing [windowSteps] grid slots — the
     *  WARMUP gate's numerator. */
    suspend fun measuredStepsInWindow(windowSteps: Int): Int = 0

    /** Gaps left as `NaN` rather than carried forward — the input a model is FITTED on, so a fit
     *  never learns persistence from a flat stretch that never happened (`SPEC/invariants.md` §1).
     *  Default null: a provider that cannot tell the two apart declines the fit. */
    suspend fun fitBgSeries(maxSteps: Int, minSteps: Int): BgSeries? = null

    /** Slots in the trailing [maxSteps] holding a MODEL'S OWN OUTPUT. `SPEC/invariants.md` §1: never
     *  a fit target or a fit window's context. A `NaN` test on [fitBgSeries] cannot stand in — an
     *  ordinary sensor gap is `NaN` there too. Default empty. */
    suspend fun reconstructedSlots(maxSteps: Int): Set<Long> = emptySet()
}

/** The baseline's causal IOB/COB source. Distinct from [ContextChannelSource], whose summed per-step
 *  amounts have thrown away which event contributed what and when it started. */
fun interface CurveEventSource {
    /** Every curve whose action overlaps `[fromMs, toMs)`, including tails of earlier events. */
    suspend fun events(fromMs: Long, toMs: Long): List<CurveEvent>
}

/** Weights and band estimator persist as one unit; they are one model. A `null` store keeps the fit
 *  in memory for the session. */
interface BaselineStore {
    suspend fun load(): BaselineModel?
    suspend fun save(model: BaselineModel)
    suspend fun clear()
}

/** Carb-appearance (feat 1) and insulin-action (feat 2) channels over a grid window. `null`
 *  (unwired) ⇒ the `normalize(0)` no-dose baseline. */
fun interface ContextChannelSource {
    /** Per-5-min amounts over `[gridStartMs, gridStartMs + nSteps·STEP)`. */
    suspend fun channels(gridStartMs: Long, nSteps: Int): ModelChannels
}

/** Index-aligned to one grid window. [exercise] is grams of carbohydrate EQUIVALENT disposed per
 *  bucket, a positive magnitude — never a negative [carb], an intensity, a duration or an energy. */
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

/** COMMITTED dose tails carried into the PREDICTION ZONE (SPEC §3.3): already absorbing, not a
 *  what-if, so the forecast does not read a physically-impossible drop-off at the now-boundary.
 *  Announced and candidate doses are excluded. `null` ⇒ the `normalize(0)` no-dose baseline. */
fun interface FutureOverrideSource {
    /** Per-5-min committed amounts over `[rollStartMs, rollStartMs + nFutureSteps·STEP)`. A bout
     *  that has already ended is still disposing glucose, so its tail is committed too. */
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

/** Read fresh every cycle, so attach and detach take effect on the next tick. `null` is the frozen
 *  model. An attached adapter changes the fan the app stores, alarms on and doses off, so the
 *  model's conformal correction and accuracy history are dropped when it changes. */
fun interface LoraStore {
    suspend fun attached(modelId: String): LoraWeights?
}
