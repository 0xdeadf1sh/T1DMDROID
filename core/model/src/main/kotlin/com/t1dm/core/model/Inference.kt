package com.t1dm.core.model

/**
 * Model pre/post-processing types crossing the Rust `t1dm-core` seam (Phase 2,
 * INFERENCE.md §§6-8). These mirror the uniffi records one-to-one; `:core:native`
 * projects the generated `uniffi.t1dm_core.*` types onto these so downstream consumers
 * (`:inference`, `:calc`, `:alerts`) never depend on the binding directly.
 */

/** Per-channel normalization statistics (bg in risk space; the other three in log1p space). */
data class ChannelStat(val mean: Double, val std: Double)

/** One tensor of the head side file, named and shaped in FILE order. */
data class HeadTensorSpec(val name: String, val shape: List<Int>)

/**
 * The BG head the export wrote beside the artifact — the seam an adapter attaches to.
 * Absent when the export shipped none, in which case the model runs but takes no adapter.
 */
data class HeadSpec(
    val file: String,
    val dtype: String,
    val byteOrder: String,
    val activation: String,
    val sha256: String,
    val dModel: Int,
    val hidden: Int,
    val stepBasisDim: Int,
    val outDim: Int,
    val tensors: List<HeadTensorSpec>,
)

/**
 * The co-trained hour-of-day TIME PROBE section of a descriptor (mirrors the Rust `TimeHead`).
 * Non-null iff the exported `.pte` emits the second `time_logits` output; a graph cut at
 * `head_raw` leaves it null and the app surfaces no predicted-hour belief. [outputIndex] is the
 * positional `.pte` output slot; [nBins]/[binHours] describe the hour-of-day circle.
 */
data class TimeHead(val outputIndex: Int, val nBins: Int, val binHours: Double)

/**
 * The Kovatchev risk parameterization the exported checkpoint was trained under (mirrors the
 * Rust `KovatchevParams`), read from the descriptor's `kovatchev` block. A re-anchored
 * checkpoint carries different constants and a different physical BG range, so this cannot be
 * a runtime constant — decoding risk-space output against the wrong scale yields plausible,
 * finite, wrong mg/dL. Distinct from `NativeCore.kovatchevF`, the fixed CLINICAL scale behind
 * LBGI/HBGI and the risk-warped axis.
 */
data class KovatchevParams(
    val scale: Double,
    val power: Double,
    val offset: Double,
    val bgClampMin: Double,
    val bgClampMax: Double,
)

/**
 * The pre/post contract parsed from a model `descriptor.json` (SPEC §2.4) — the sole
 * source of the normalization stats plus the checkpoint-absent decode constants.
 */
data class ModelDescriptor(
    val bg: ChannelStat,
    val carb: ChannelStat,
    val insulin: ChannelStat,
    /** Carbohydrate-EQUIVALENT glucose disposal, g/step — a positive magnitude in its own
     *  channel, never a negative carbohydrate value in the carb channel. */
    val exercise: ChannelStat,
    val ropeBase: Int,
    val medianGlobalDim: Int,
    val stepBasisType: String,
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
    /** The span count and longest span the training sampler ever drew. Past either, the model
     *  is being asked for something it has never seen. */
    val maskMaxSpans: Int,
    val maskSpanMax: Int,
    /** Trunk width — the length of one slot's hidden state. */
    val dModel: Int,
    /** `K` — within-patch basis columns the head emits per (slot, channel). */
    val stepBasisDim: Int,
    /** The risk transform THIS checkpoint was trained under — the sole authority for decoding
     *  its output back to mg/dL. */
    val kovatchev: KovatchevParams,
    /**
     * The exporter's `conformal.enabled` flag, carried because it is part of the descriptor — and
     * READ BY NOTHING. It governs no branch here and no branch in the core: a descriptor shipping
     * `true` parses and changes precisely nothing, because the export path emits no
     * `conformal_delta` for it to switch on.
     *
     * The on-device recalibration of `SPEC/inference.md` §8.4 does NOT consult it. That correction
     * is fitted from the patient's own matured forecasts and is governed entirely by whether a
     * `conformal_delta` row exists for the model — see [BandCalibration]. The two are separate
     * mechanisms and neither can enable or disable the other.
     */
    val conformalEnabled: Boolean,
    /** The co-trained time-probe descriptor, or null when the graph is cut at `head_raw`. */
    val time: TimeHead? = null,
    /** The head side file, or null when the export shipped none. Its absence costs no
     *  forecast and forbids every adapter. */
    val head: HeadSpec? = null,
)

/**
 * One masked span, in CONTEXT-relative patch coordinates: patch 0 is the oldest real context
 * patch supplied, whatever left-padding lands in front of it.
 */
data class MaskSpan(val startPatch: Int, val length: Int)

/**
 * The complete fixed-shape graph input. Built entirely in the Rust core so the mask rule, the
 * left-pad and the masked-patch fill exist once; this side copies the three float buffers into
 * direct NIO buffers and reasons about no geometry at all.
 *
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

/**
 * The decoded forecast, step-major over the P·S horizon (`i = p·S + s`). Risk-space
 * ([medianRisk]/[qTauRisk]) and the `f_inv` mg/dL projections ([medianBg]/[bandsMgdl]).
 */
data class Forecast(
    val medianRisk: List<Double>,
    val qTauRisk: List<Double>,
    val medianBg: List<Double>,
    val bandsMgdl: List<Double>,
    /** The absolute patch each decoded slot came from — what locates a span on a chart, and
     *  what tells an infill row from a forecast row. */
    val slotPatch: List<Int> = emptyList(),
)

/** Why a forecast is unfit to drive a rail/alert (§3.6-B). [OK] ⇒ eligible. */
enum class ForecastStatus { OK, NON_FINITE, RAIL_PINNED, COLLAPSED_BAND, MISORDERED_QUANTILES }

// ── The adapter (SPEC/inference.md's head seam; T1DMDROID's own fit) ─────────────────

/**
 * Where an adapter attaches and how large it is. `alpha/rank` is the scale, so raising the
 * rank does not silently raise the step size with it.
 */
data class LoraConfig(
    val rank: Int,
    val alpha: Double,
    val targetHidden: Boolean,
    val targetL0: Boolean,
    val targetL1: Boolean,
    val targetL2: Boolean,
)

/** A trained (or freshly initialised) adapter. A fresh one is exactly the identity. */
data class LoraWeights(
    val config: LoraConfig,
    /** The digest of the head file it was fitted on. Geometry does not identify a head — two
     *  checkpoints of one capacity share it — so this is what binds an adapter to its model. */
    val headSha256: String,
    val dModel: Int,
    val hidden: Int,
    val outDim: Int,
    val params: List<Double>,
)

/**
 * One training window: the trunk hidden states of a span's slots, that span's anchors, and the
 * BG that actually happened. The slots must be ONE contiguous span.
 */
data class LoraSample(
    val hidden: List<Double>,
    val anchors: List<Double>,
    val targetBg: List<Double>,
    val nSlots: Int,
    /**
     * The SAME window's trunk hidden state with a probe dose injected into the masked span's dose
     * channel. Empty when the window was not paired.
     *
     * It is what makes "what does one more unit of insulin do to this forecast" a quantity the fit
     * can see. Without it an adapter can null the model's marginal dose response with a rank-1 map,
     * score better on pinball loss, and hand the calculator a forecaster that does not respond to
     * insulin at all.
     */
    val hiddenPert: List<Double> = emptyList(),
    /** True for the trailing-forecast geometry — the only one the guard measures on. */
    val isForecast: Boolean = true,
)

/** Optimiser settings for a fit. */
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

/**
 * What the counterfactual guard is allowed to conclude, and on what evidence.
 *
 * DELIBERATELY UNDEFAULTED. The bar the fit's own guard pass uses lives in the crate, and defaults
 * here were a second copy of every number in it — correct the day they were written, and free to
 * disagree the day one moved. `NativeCore.loraGuardOptsFit()` reads the crate's.
 */
data class LoraGuardOpts(
    val maxWindows: Int,
    val minWindows: Int,
    val probeDoseU: Double,
    val minFrozenResponse: Double,
    val minRetention: Double,
    val maxRetention: Double,
    val minSignAgreement: Double,
)

/**
 * Whether an adapter preserved the model's marginal response to insulin.
 *
 * Four values against the crate's three: [ABSENT] is a storage-only state meaning "never probed",
 * which no guard run can produce. It exists because a stored adapter with no verdict must be
 * refused at attach rather than treated as unproblematic — an adapter arriving by import or by
 * archive restore has no verdict, and silence is not a pass.
 */
enum class LoraGuardVerdict { PASS, BLOCKED, INCONCLUSIVE, ABSENT }

/** The guard's finding, with every input to it, so a refusal can be read rather than trusted. */
data class LoraGuardReport(
    val verdict: LoraGuardVerdict,
    val nWindows: Int,
    val frozenResponseMgdl: Double,
    val adaptedResponseMgdl: Double,
    val retention: Double,
    val signAgreement: Double,
    val why: String,
)

/** What a fit did, in the terms the panel has to show before anyone attaches it. */
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
    /** Held-out loss at the end of each epoch — [lossHistory]'s held-out twin. */
    val holdoutHistory: List<Double>,
    /** The epoch whose weights the fit returned; `0` when no epoch beat the frozen head. */
    val bestEpoch: Int,
    /** Training samples that carried a usable counterfactual branch. */
    val nPaired: Int = 0,
    /** The coefficient the distillation term actually ran with. */
    val distillScale: Double = 0.0,
    /** The distillation term alone, per epoch — `lossHistory` carries the sum, so without this a
     *  fit whose pinball improved while its dose response collapsed looks like a good one. */
    val distillHistory: List<Double> = emptyList(),
    /** What the guard made of the returned adapter. Null when there was nothing to measure on,
     *  which is itself a reason not to attach. */
    val guard: LoraGuardReport? = null,
)

/** Called once per epoch while a fit runs. Never per sample. */
fun interface LoraProgressSink {
    fun onEpoch(epoch: Int, epochs: Int, trainLoss: Double, holdoutLoss: Double)
}

/** An adapter and the account of how it was fitted, which travel together. */
data class LoraTrainResult(val weights: LoraWeights, val report: LoraTrainReport)

// ── The synthetic patient ───────────────────────────────────────────────────────────

/** The knobs the synthetic generator takes; every default is a population figure. */
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

/** Four synthetic channels on the grid, in the units the model reads. */
data class SynthSeries(
    val bg: List<Double>,
    val carb: List<Double>,
    val insulin: List<Double>,
    val exercise: List<Double>,
    val nMeals: Int,
    val nBoluses: Int,
    val nBouts: Int,
)

/** An absent-sample run in a gridded BG series — a gap a repair can offer to fill. */
data class GapRun(val start: Int, val end: Int)
