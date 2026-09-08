package com.t1dm.core.model

/** Mirrors t1dm-core::baseline; fitted on device, no descriptor/.pte, outside the running cap. */

/** Stable model_id it publishes under; versioned, an older fit's rows become incomparable. */
const val BASELINE_MODEL_ID: String = "ridge-cgm-iob-cob-v1"

/** Defaults come from `NativeCore.baselineDefaultSpec`, never restated here. */
data class BaselineSpec(
    val nLags: Int,
    val horizonSteps: Int,
    val ridgeLambda: Double,
    val useIob: Boolean,
    val useCob: Boolean,
    /** Committed carb/insulin over next 30/60/120 min; without them a logged meal looks inert. */
    val useForward: Boolean,
)

/** bandDelta is part of the model, not §8.4's delta; residual quantiles are its estimator. */
data class BaselineModel(
    val spec: BaselineSpec,
    val nFeatures: Int,
    val weights: List<Double>,
    val bandDelta: List<Double>,
    val nTrainRows: Int,
    val fittedAtMs: Long,
    val trainFromMs: Long,
    val trainToMs: Long,
) {
    /** False until enough held-out history resolved 7 levels; uncalibrated withholds forecast. */
    val calibrated: Boolean get() = bandDelta.any { it != 0.0 }
}

/** Per horizon step, MEDIAN-LINE figures, not SPEC/invariants.md §6.2's band projection. */
data class BaselineFit(
    val model: BaselineModel,
    val conformal: ConformalFit,
    val holdoutRmseMgdl: List<Double>,
    val nHoldoutWindows: Int,
    val persistenceRmseMgdl: List<Double>,
)

/** mg/dL only: regresses glucose onto glucose, no risk space to name (SPEC §4 rule 1). */
data class BaselineForecast(
    val medianBg: List<Double>,
    val bandsMgdl: List<Double>,
)

/** Why a manual fit produced no model, as distinct from a fit that ran and refused to calibrate. */
enum class BaselineFitRefusal {
    /** Already in flight; refused outright, never queued. */
    BUSY,

    /** Not enough grid-aligned history to fill the lag span, the horizon and both splits. */
    INSUFFICIENT_HISTORY,

    /** The core rejected the window: a shape it can't solve, or a design with no complete rows. */
    CORE_REFUSED,
}
