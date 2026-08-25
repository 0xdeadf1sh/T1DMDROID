package com.t1dm.core.model

/**
 * Mirrors `t1dm-core::baseline`. Fitted on device: no descriptor and no `.pte`, so it sits beside
 * the discovered set and outside the running cap. With it selected the dose calculator, the ISF/ICR
 * probe and the rolled overlay fail closed — but the refusal reads "no selected model".
 */

/** The stable `model_id` it publishes under. Versioned: a change to the feature set or the horizon
 *  makes stored rows from an older fit incomparable, not merely stale. */
const val BASELINE_MODEL_ID: String = "ridge-cgm-iob-cob-v1"

/** Defaults come from `NativeCore.baselineDefaultSpec`, never restated here. */
data class BaselineSpec(
    val nLags: Int,
    val horizonSteps: Int,
    val ridgeLambda: Double,
    val useIob: Boolean,
    val useCob: Boolean,
    /** Sums of committed carb appearance and insulin action over the next 30 / 60 / 120 min. Without
     *  them a dose snapping to a later grid slot moves nothing until the next CGM sample advances the
     *  anchor, so logging a meal appeared to do nothing at all. */
    val useForward: Boolean,
)

/**
 * [bandDelta] is part of the model, not `SPEC/inference.md` §8.4's post-hoc delta: a ridge fit has no
 * interval of its own, so the residual quantiles ARE its estimator, fitted in the same call. There is
 * no raw/calibrated pair here, so [ConformalFit]'s ban on storing a corrected fan cannot be breached.
 */
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
    /** False until a fit had enough held-out history for the seven levels to resolve. An
     *  uncalibrated fan is degenerate and the forecast is withheld — a median with no honest
     *  interval is not shown. */
    val calibrated: Boolean get() = bandDelta.any { it != 0.0 }
}

/** Per horizon step over the same held-out windows. Both are MEDIAN-LINE figures, not
 *  `SPEC/invariants.md` §6.2's band projection — the two bases must never share a column. */
data class BaselineFit(
    val model: BaselineModel,
    val conformal: ConformalFit,
    val holdoutRmseMgdl: List<Double>,
    val nHoldoutWindows: Int,
    val persistenceRmseMgdl: List<Double>,
)

/** mg/dL only: it regresses glucose onto glucose and has no risk space to name, which
 *  `SPEC/invariants.md` §4 rule 1 would otherwise make a defect. */
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

    /** The core rejected the window — a shape it cannot solve, or a design with no complete rows. */
    CORE_REFUSED,
}
