package com.t1dm.core.model

/**
 * The model-probed insulin sensitivity factor and insulin-to-carbohydrate ratio, read off three
 * counterfactual rolls of the selected fp32 authority (`:calc` `SensitivityProbe`).
 *
 * **Display-only by construction**, on the [RolledForecast] precedent: this is a distinct type that
 * cannot enter `:calc`'s decision path, is never written into `inferenceState.predictions`, is never
 * stored, never synced, and is read by no rail. The dose advisor searches its own candidate grid
 * against the model directly and has no use for a linearised ratio.
 *
 * Both figures are the model's *marginal* response at [horizonMs], not a clinical titration:
 *
 * - [isfMgdlPerU] — mg/dL the median forecast falls per unit of rapid insulin. Positive.
 * - [icrGPerU] — grams of carbohydrate whose predicted rise one unit cancels. Positive.
 *
 * [horizonMs] is load-bearing to their meaning. It is the validated window, not the full insulin
 * action window, so a rapid analogue has not finished acting: both figures are smaller than the
 * textbook whole-action definitions of the same names. See `SensitivityProbe`.
 */
data class SensitivityEstimate(
    val atMs: Long,
    val horizonMs: Long,
    val isfMgdlPerU: Double,
    val icrGPerU: Double,
    /**
     * The selected model this was probed on. These figures are a property of one artifact, not of the
     * patient — comparing two models is much of what the read-out is for — so an estimate that cannot
     * name its model could be read against the wrong one. A selection change makes the held figure
     * stale immediately, whatever its age says.
     */
    val modelId: String,
)
