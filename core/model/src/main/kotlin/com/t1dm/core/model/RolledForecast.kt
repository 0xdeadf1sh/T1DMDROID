package com.t1dm.core.model

/**
 * An ON-DEMAND, autoregressively rolled forecast (issue I2) — the model's own 2 h forecast re-fed into
 * its context N times to reach a user-chosen horizon of up to 12 h.
 *
 * **DISPLAY-ONLY and EPHEMERAL by contract.** This type exists solely to be drawn on the BG panel for
 * inspection. It is DELIBERATELY a distinct type from [ModelPrediction] so it CANNOT be mistaken for,
 * or substituted into, the validated 2 h forecast the safety-critical surfaces consume:
 *
 *  - it is NEVER placed in [InferenceState.predictions] (the cycle's persisted forecast),
 *  - it NEVER feeds `:calc` (dose selection reads `ForecastPort`/`PredFan`, not this),
 *  - it NEVER feeds the top-bar HYPO/HYPER indicator or the ongoing-notification countdown (both stay
 *    pinned to the validated 2 h horizon — "HYPO in 19H" must be impossible).
 *
 * Beyond the first [validatedSteps] the values are EXTRAPOLATED and unvalidated: error compounds with
 * each roll, so the renderer draws that region distinctly (hatched / dimmed) and never lets it raise an
 * alert. All BG values are step-major mg/dL; presenters convert to the active unit. Step `i` (0-based)
 * is the model's median for the time `anchorTsMs + (i + 1)·stepMs` — the same convention as
 * [ModelPrediction].
 *
 * Fail-closed: a roll that could not be produced at all yields [NONE] / [missing]; a roll that
 * degenerated part-way keeps the valid prefix, sets [degenerate], and states [reason] in plain language.
 */
data class RolledForecast(
    val anchorTsMs: Long,
    val stepMs: Long,
    /** Step-major median BG (mg/dL) over the whole roll (validated prefix + extrapolated tail). */
    val medianBg: DoubleArray,
    /** The τ=.05 lower band edge (mg/dL) per step. */
    val lowerBg: DoubleArray,
    /** The τ=.95 upper band edge (mg/dL) per step. */
    val upperBg: DoubleArray,
    /** The prefix length that lies inside the VALIDATED horizon (2 h ⇒ 24). Steps past this are
     *  extrapolated and must be drawn distinctly and never alerted on. */
    val validatedSteps: Int,
    /** The user-requested roll horizon in hours (30 min…12 h), for the legend / read-out. */
    val requestedHours: Double,
    /** True only for a fully-produced, non-degenerate roll. Display eligibility only — this NEVER
     *  authorises an alert or a dose. */
    val eligible: Boolean,
    /** A roll that degenerated at some point; [medianBg] holds only the valid prefix. */
    val degenerate: Boolean,
    /** Plain-language reason when the roll is absent, partial, or degenerate; null when fully eligible. */
    val reason: String?,
    /** How many 2 h forwards actually completed (the valid portion = completedRolls·2 h). */
    val completedRolls: Int,
    /** How many 2 h forwards the request implied. */
    val requestedRolls: Int,
) {
    val size: Int get() = medianBg.size
    val isEmpty: Boolean get() = medianBg.isEmpty()

    /** Absolute epoch-ms at the END of the rolled fan (its right edge on the graph). */
    val horizonEndMs: Long get() = anchorTsMs + size.toLong() * stepMs

    /** How much of the fan is extrapolated (beyond the validated 2 h). */
    val extrapolatedSteps: Int get() = (size - validatedSteps).coerceAtLeast(0)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RolledForecast) return false
        return anchorTsMs == other.anchorTsMs && stepMs == other.stepMs &&
            medianBg.contentEquals(other.medianBg) && lowerBg.contentEquals(other.lowerBg) &&
            upperBg.contentEquals(other.upperBg) && validatedSteps == other.validatedSteps &&
            requestedHours == other.requestedHours && eligible == other.eligible &&
            degenerate == other.degenerate && reason == other.reason &&
            completedRolls == other.completedRolls && requestedRolls == other.requestedRolls
    }

    override fun hashCode(): Int {
        var r = anchorTsMs.hashCode()
        r = 31 * r + medianBg.contentHashCode()
        r = 31 * r + validatedSteps
        r = 31 * r + requestedHours.hashCode()
        r = 31 * r + eligible.hashCode()
        r = 31 * r + degenerate.hashCode()
        return r
    }

    companion object {
        /** No roll displayed. */
        val NONE = RolledForecast(
            anchorTsMs = 0L, stepMs = 300_000L,
            medianBg = DoubleArray(0), lowerBg = DoubleArray(0), upperBg = DoubleArray(0),
            validatedSteps = 0, requestedHours = 0.0, eligible = false, degenerate = false,
            reason = null, completedRolls = 0, requestedRolls = 0,
        )

        /** A roll that could not be produced at all (no model / no context / forward failed). */
        fun missing(requestedHours: Double, requestedRolls: Int, reason: String): RolledForecast =
            NONE.copy(requestedHours = requestedHours, requestedRolls = requestedRolls, reason = reason)
    }
}
