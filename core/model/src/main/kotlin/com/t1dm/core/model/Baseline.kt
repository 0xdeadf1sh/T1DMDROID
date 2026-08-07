package com.t1dm.core.model

/**
 * The classical forecast baseline — the domain types mirroring `t1dm-core::baseline` one-to-one.
 *
 * Features: twelve lagged CGM values, causal IOB/COB at the anchor, and the committed carb
 * appearance and insulin action summed over the next 30 / 60 / 120 minutes.
 *
 * It exists so the neural model can be measured against something rather than against nothing.
 * The published comparisons are unflattering to both sides: on OhioT1DM the entire state of the
 * art spans under 2 mg/dL RMSE at 30 minutes, and beats zero-order hold by about five — so a
 * baseline is only worth shipping if it is a fair opponent, which means fitted on this patient,
 * direct multi-step rather than rolled recursively, and carrying an honest interval.
 *
 * **From the app's perspective this is an ordinary model** everywhere the model abstraction is a
 * FAN: it joins the running set, selecting it gives the graph overlay, the predictive alert and the
 * widget its fan exactly as selecting any model does, it is scored by the same suite through the
 * same §3.6 gates, and it syncs under its own id. It has no descriptor and no `.pte`, because it is
 * fitted on device rather than exported and `SPEC/invariants.md` §4 rule 5 makes a descriptor and an
 * artifact one unit, so it sits beside the discovered set rather than inside it and outside the
 * running cap.
 *
 * **What it cannot drive is the dose calculator.** `:calc`'s rolled search is graph-shaped: it sizes
 * its context from a descriptor's patch geometry and runs a `GraphInput` forward per roll with the
 * candidate dose written into the prediction zone. A model with neither a descriptor nor a graph
 * cannot answer that, so with the baseline selected `authorityModelInfo()` finds no loaded fp32
 * authority and the calculator, the ISF/ICR probe and the rolled display overlay all fail closed.
 * That is the safe direction and it is not silent to the code — but the refusal currently reads
 * "no selected model", which is the wrong reason. Giving the baseline a real rolling path is a
 * `:calc` change behind the dose-rail invariants, not something to infer from this file.
 */

/** The stable `model_id` the baseline publishes under — on the graph, in the `prediction` table,
 *  and on the wire, where `SPEC/http-api.md` keys predictions on `(made_at, model_id)` and so
 *  lets a second model coexist with no contract change. Versioned because a change to the feature
 *  set or the horizon makes stored rows from an older fit incomparable, not merely stale. */
const val BASELINE_MODEL_ID: String = "ridge-cgm-iob-cob-v1"

/**
 * The baseline's shape and shrinkage. Sourced from the core's own
 * [com.t1dm.core.common.NativeCore.baselineDefaultSpec] rather than restated here — the defaults
 * are the Rust module's to choose.
 */
data class BaselineSpec(
    val nLags: Int,
    val horizonSteps: Int,
    val ridgeLambda: Double,
    val useIob: Boolean,
    val useCob: Boolean,
    /** Sums of committed carb appearance and insulin action over the next 30 / 60 / 120 min.
     *  Without these the model's only exogenous inputs are two scalars at the anchor, and a dose
     *  snapping to a later grid slot moves nothing until the next CGM sample advances the anchor —
     *  so logging a meal appeared to do nothing at all. */
    val useForward: Boolean,
)

/**
 * A fitted baseline: folded weights, the band estimator, and the provenance for both.
 *
 * [bandDelta] is **part of the model**, not a correction applied to it. `SPEC/inference.md` §8.4's
 * delta is a post-hoc display-only recalibration of a fan the neural model already produced, and
 * [ConformalFit]'s own documentation forbids storing or transmitting a fan with one applied. The
 * arithmetic here is the same and the role is not: a ridge fit has no interval of its own, so the
 * residual quantiles ARE its interval estimator, fitted in the same call from the same history and
 * carried in the same record. A baseline forecast has exactly one fan — there is no raw/calibrated
 * pair to choose between, and therefore nothing the boundary in [ConformalFit] could be breached
 * by.
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
     *  uncalibrated model still predicts a median, but its fan is degenerate and the degeneracy
     *  guard withholds the forecast — a median with no honest interval is not shown. */
    val calibrated: Boolean get() = bandDelta.any { it != 0.0 }
}

/**
 * One run of the manual fit: the model, its band calibration, and the held-out evidence for both.
 *
 * [holdoutRmseMgdl] and [persistenceRmseMgdl] are per horizon step over the same held-out windows —
 * rows the ridge never saw. Both are **median-line** figures, not the band projection of
 * `SPEC/invariants.md` §6.2, and the two bases must never share a column. Persistence travels
 * beside the model because it is the number that decides whether anything here earned its
 * footprint.
 */
data class BaselineFit(
    val model: BaselineModel,
    val conformal: ConformalFit,
    val holdoutRmseMgdl: List<Double>,
    val nHoldoutWindows: Int,
    val persistenceRmseMgdl: List<Double>,
)

/** A baseline forecast. mg/dL only: it regresses glucose onto glucose and has no risk space to
 *  name, which `SPEC/invariants.md` §4 rule 1 would otherwise make a defect. */
data class BaselineForecast(
    val medianBg: List<Double>,
    val bandsMgdl: List<Double>,
)

/** Why a manual fit produced no model, as distinct from a fit that ran and refused to calibrate. */
enum class BaselineFitRefusal {
    /** A fit is already in flight. A second entry is refused outright, never queued. */
    BUSY,

    /** Not enough grid-aligned history to fill the lag span, the horizon and both splits. */
    INSUFFICIENT_HISTORY,

    /** The core rejected the window — a shape it cannot solve, or a design with no complete rows. */
    CORE_REFUSED,
}
