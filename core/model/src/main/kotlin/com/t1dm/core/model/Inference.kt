package com.t1dm.core.model

/** Model pre/post-processing types crossing the Rust `t1dm-core` seam (INFERENCE.md §§6-8). */

/** bg in risk space; the other three in log1p space. */
data class ChannelStat(val mean: Double, val std: Double)

/** Named and shaped in FILE order. */
data class HeadTensorSpec(val name: String, val shape: List<Int>)

/** Absent when the export shipped none, in which case the model runs but takes no adapter. */
data class HeadSpec(
    val file: String,
    val dtype: String,
    val byteOrder: String,
    val activation: String,
    val sha256: String,
    val dModel: Int,
    val hidden: Int,
    val outDim: Int,
    /** The rule taking `hidden` to the head's per-step input; a name this build does not
     *  implement is refused at parse. */
    val decoder: String,
    val tensors: List<HeadTensorSpec>,
)

/** Non-null iff the exported `.pte` emits the second `time_logits` output; a graph cut at
 *  `head_raw` leaves it null. [outputIndex] is the positional `.pte` output slot. */
data class TimeHead(val outputIndex: Int, val nBins: Int, val binHours: Double)

/**
 * The risk parameterization the exported checkpoint was trained under, so it cannot be a runtime
 * constant: decoding against the wrong scale yields plausible, finite, wrong mg/dL. Distinct from
 * `NativeCore.kovatchevF`, the fixed CLINICAL scale behind LBGI/HBGI and the risk-warped axis.
 */
data class KovatchevParams(
    val scale: Double,
    val power: Double,
    val offset: Double,
    val bgClampMin: Double,
    val bgClampMax: Double,
)

/** Parsed from a model `descriptor.json` — SPEC §2.4. */
data class ModelDescriptor(
    val bg: ChannelStat,
    val carb: ChannelStat,
    val insulin: ChannelStat,
    /** Carbohydrate-EQUIVALENT glucose disposal, g/step — a positive magnitude in its own
     *  channel, never a negative carbohydrate value in the carb channel. */
    val exercise: ChannelStat,
    val ropeBase: Int,
    val quantileSpreadMin: Double,
    val negFill: Double,
    val predictionHorizonHours: Int,
    val maxContextPatches: Int,
    val minContextPatches: Int,
    val patchSize: Int,
    val nInputFeatures: Int,
    /** The exported graph's fixed sequence length `T`; the window is left-padded into it. */
    val seqLen: Int,
    /** `M` — the head's slot count, and the cap on the masked set a caller may ask for. */
    val maxMaskedPatches: Int,
    /** The most spans and the longest span the training sampler ever drew; past either, the model
     *  has never seen it. */
    val maskMaxSpans: Int,
    val maskSpanMax: Int,
    /** Trunk width — the length of one patch's hidden state. */
    val dModel: Int,
    /** The architecture the checkpoint was trained under; only `risk-v5` parses. */
    val archVersion: String,
    /** The risk transform THIS checkpoint was trained under; the sole decode authority. */
    val kovatchev: KovatchevParams,
    /** Read by nothing: no branch here or in the core, and the export emits no `conformal_delta`.
     *  The §8.4 on-device recalibration does not consult it — see [BandCalibration]. */
    val conformalEnabled: Boolean,
    /** The co-trained time-probe descriptor, or null when the graph is cut at `head_raw`. */
    val time: TimeHead? = null,
    /** Null when the export shipped none. Its absence costs no forecast and forbids every
     *  adapter. */
    val head: HeadSpec? = null,
)

/** CONTEXT-relative patch coordinates: patch 0 is the oldest real context patch supplied,
 *  whatever left-padding lands in front of it. */
data class MaskSpan(val startPatch: Int, val length: Int)

/**
 * [patches] is `T·PATCH_SIZE·N_FEAT` step-major, [attnMask] `T·T` additive, [slotSel] `M·T`
 * one-hot rows. [anchors]/[slotPatch] describe all `M` slots; only the first [nMasked] are
 * real. [firstForecastPatch] is `-1` for a window with no future zone.
 */
data class GraphInput(
    val nCtx: Int,
    val t: Int,
    val patchDim: Int,
    val mSlots: Int,
    val nMasked: Int,
    val patches: FloatArray,
    val attnMask: FloatArray,
    val slotSel: FloatArray,
    val anchors: List<Double>,
    val slotPatch: List<Int>,
    val firstForecastPatch: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is GraphInput &&
            nCtx == other.nCtx && t == other.t && patchDim == other.patchDim &&
            mSlots == other.mSlots && nMasked == other.nMasked &&
            patches.contentEquals(other.patches) && attnMask.contentEquals(other.attnMask) &&
            slotSel.contentEquals(other.slotSel) && anchors == other.anchors &&
            slotPatch == other.slotPatch && firstForecastPatch == other.firstForecastPatch

    override fun hashCode(): Int =
        (((nCtx * 31 + t) * 31 + nMasked) * 31 + patches.contentHashCode()) * 31 + slotPatch.hashCode()
}

/** Step-major over the P·S horizon (`i = p·S + s`): risk space in [medianRisk]/[qTauRisk], the
 *  `f_inv` mg/dL projections in [medianBg]/[bandsMgdl]. */
data class Forecast(
    val medianRisk: List<Double>,
    val qTauRisk: List<Double>,
    val medianBg: List<Double>,
    val bandsMgdl: List<Double>,
    /** The absolute patch each decoded slot came from — what locates a span on a chart, and what
     *  tells an infill row from a forecast row. */
    val slotPatch: List<Int> = emptyList(),
)

/** Why a forecast is unfit to drive a rail/alert (§3.6-B). [OK] ⇒ eligible. */
enum class ForecastStatus { OK, NON_FINITE, RAIL_PINNED, COLLAPSED_BAND, MISORDERED_QUANTILES }

/** `alpha/rank` is the scale, so raising the rank does not raise the step size with it. */
data class LoraConfig(
    val rank: Int,
    val alpha: Double,
    val targetHidden: Boolean,
    val targetL0: Boolean,
    val targetL1: Boolean,
    val targetL2: Boolean,
)

/** A freshly initialised adapter is exactly the identity. */
data class LoraWeights(
    val config: LoraConfig,
    /** Digest of the head file it was fitted on — geometry does not identify a head, so this is
     *  what binds an adapter to its model. */
    val headSha256: String,
    val dModel: Int,
    val hidden: Int,
    val outDim: Int,
    val params: List<Double>,
)

/** The slots must be ONE contiguous span. */
data class LoraSample(
    val hidden: List<Double>,
    val anchors: List<Double>,
    val targetBg: List<Double>,
    val nSlots: Int,
    /**
     * The SAME window's hidden state with a probe dose injected into the masked span's dose
     * channel; empty when the window was not paired. Without it a rank-1 map can null the model's
     * marginal dose response and still score better on pinball loss.
     */
    val hiddenPert: List<Double> = emptyList(),
    /** True for the trailing-forecast geometry — the only one the guard measures on. */
    val isForecast: Boolean = true,
)

data class LoraTrainOpts(
    val epochs: Int,
    val lr: Double,
    val holdoutFrac: Double,
    val weightDecay: Double,
    val seed: Long,
    /** How hard to pin the adapted marginal dose response to the frozen model's; `0.0` is off. A
     *  multiple of the frozen head's own mean training loss, not a raw coefficient. */
    val distillWeight: Double = 1.0,
)

/** DELIBERATELY UNDEFAULTED: the bar lives in the crate, and defaults here were a second copy of
 *  it. `NativeCore.loraGuardOptsFit()` reads the crate's. */
data class LoraGuardOpts(
    val maxWindows: Int,
    val minWindows: Int,
    val probeDoseU: Double,
    val minFrozenResponse: Double,
    val minRetention: Double,
    val maxRetention: Double,
    val minSignAgreement: Double,
)

/** [ABSENT] is storage-only and no guard run produces it: an adapter arriving by import or
 *  archive restore has no verdict, and silence is not a pass, so it is refused at attach. */
enum class LoraGuardVerdict { PASS, BLOCKED, INCONCLUSIVE, ABSENT }

data class LoraGuardReport(
    val verdict: LoraGuardVerdict,
    val nWindows: Int,
    val frozenResponseMgdl: Double,
    val adaptedResponseMgdl: Double,
    val retention: Double,
    val signAgreement: Double,
    val why: String,
)

data class LoraTrainReport(
    val nTrain: Int,
    val nHoldout: Int,
    val epochsRun: Int,
    val trainLossFirst: Double,
    val trainLossLast: Double,
    val holdoutLossBefore: Double,
    val holdoutLossAfter: Double,
    /** True only when the adapter beat the frozen head on windows it never trained on. */
    val improved: Boolean,
    val lossHistory: List<Double>,
    /** Held-out loss at the end of each epoch. */
    val holdoutHistory: List<Double>,
    /** The epoch whose weights the fit returned; `0` when no epoch beat the frozen head. */
    val bestEpoch: Int,
    /** Training samples that carried a usable counterfactual branch. */
    val nPaired: Int = 0,
    /** The coefficient the distillation term actually ran with. */
    val distillScale: Double = 0.0,
    /** The distillation term alone, per epoch; [lossHistory] carries the sum. */
    val distillHistory: List<Double> = emptyList(),
    /** Null when there was nothing to measure on, which is itself a reason not to attach. */
    val guard: LoraGuardReport? = null,
)

/** Called once per epoch while a fit runs. Never per sample. */
fun interface LoraProgressSink {
    fun onEpoch(epoch: Int, epochs: Int, trainLoss: Double, holdoutLoss: Double)
}

data class LoraTrainResult(val weights: LoraWeights, val report: LoraTrainReport)

data class SynthParams(
    val baselineBg: Double,
    val mealGrams: Double,
    val carbRatio: Double,
    val basalUPerHour: Double,
    val exerciseProb: Double,
    val exerciseCarbEquivPerMin: Double,
    val cgmNoiseSd: Double,
    val missedBolusProb: Double,
)

data class SynthSeries(
    val bg: List<Double>,
    val carb: List<Double>,
    val insulin: List<Double>,
    val exercise: List<Double>,
    val nMeals: Int,
    val nBoluses: Int,
    val nBouts: Int,
)

data class GapRun(val start: Int, val end: Int)
