package com.t1dm.core.model

/**
 * Split-conformal band recalibration (`SPEC/inference.md` §8.4), fitted ON DEVICE — the domain
 * types mirroring the Rust `t1dm-core::conformal` records one-to-one.
 *
 * §8.4 describes a delta the CHECKPOINT may carry, fit on the simulator distribution, and says in
 * the same breath that for real-world CGM it must be re-fit per cohort or omitted. The exporter
 * ships none, so on a real sensor the fan the model produces is the raw fan. This is the other
 * half of that sentence: the cohort is one patient, the calibration set is their own matured
 * `(forecast, realized)` history, and the fit runs here.
 *
 * **Three boundaries govern where a calibrated fan may appear, and none of them is negotiable.**
 *
 *  1. The MEDIAN NEVER MOVES. §8.4 pins `delta[.., median] = 0` and the core rejects a delta that
 *     does not; the dose calculator scores off the median line, so a correction that shifted it
 *     would silently change every recommendation.
 *  2. CLASSIFY RAW, THEN CALIBRATE. Every consumer that decides a CATEGORY — the alarm engine off
 *     the τ.25/.75 edges, the rolling forecaster's rails, the excursion detectors, the accuracy
 *     suite — reads [ModelPrediction.bandsMgdl] as stored, which is the raw fan. The calibrated
 *     fan is a DISPLAY quantity and reaches exactly one surface: the BG panel's forecast overlay.
 *  3. THE WIRE CARRIES THE RAW FAN. `SPEC/http-api.md`'s Prediction has no calibrated/raw
 *     discriminator, and because §8.4 pins the median a calibrated fan would still satisfy "row
 *     index 3 equals `line`" — so it would traverse and store indistinguishably, and the operator
 *     console would render a device-fitted correction as though it were the model's own. Nothing
 *     calibrated is ever written to the `prediction` table or pushed. Making that distinction
 *     visible would be a wire-contract change across three repositories, not a local one.
 */

/**
 * A fitted per-`(step, τ)` additive correction and the evidence for it, straight from the core.
 *
 * [delta] is `steps · nQuantiles`, step-major in ascending τ — the layout of
 * [ModelPrediction.bandsMgdl] — so it applies to a fan without a transpose. It is all zeros
 * whenever [sufficient] is false: the fail-closed outcome of too little history is NO correction,
 * never one extrapolated from a handful of residuals.
 *
 * [nCal] windows were fitted on and [nEval] held out; [cov90Raw]/[cov90Cal] are the realized
 * τ.05–.95 coverage on that held-out split before and after the correction, and
 * [meanWidth90Raw]/[meanWidth90Cal] the mean edge-to-edge width beside them — which
 * `SPEC/invariants.md` §6.2 requires, since a band widened until it swallows every truth covers
 * perfectly and forecasts nothing.
 */
data class ConformalFit(
    val delta: List<Double>,
    val steps: Int,
    val nQuantiles: Int,
    val nWindows: Int,
    val nCal: Int,
    val nEval: Int,
    val nRejected: Int,
    val minCalWindows: Int,
    val sufficient: Boolean,
    val maxAbsDeltaMgdl: Double,
    val cov90Raw: Double?,
    val cov90Cal: Double?,
    val meanWidth90Raw: Double?,
    val meanWidth90Cal: Double?,
) {
    companion object {
        /** The fail-closed value the binding maps a core error to: no correction, no evidence. */
        val NONE = ConformalFit(
            delta = emptyList(),
            steps = 0,
            nQuantiles = 0,
            nWindows = 0,
            nCal = 0,
            nEval = 0,
            nRejected = 0,
            minCalWindows = 0,
            sufficient = false,
            maxAbsDeltaMgdl = 0.0,
            cov90Raw = null,
            cov90Cal = null,
            meanWidth90Raw = null,
            meanWidth90Cal = null,
        )
    }
}

/**
 * A stored correction for one model — what survives process death, and the provenance a reader
 * needs to judge it.
 *
 * Only a SUFFICIENT fit is ever stored. A refusal leaves whatever was there untouched: a fit that
 * declined to run has not learnt that the previous one was wrong, and discarding it would silently
 * drop the user back to the raw fan on the strength of a walk that scored nothing.
 */
data class BandCalibration(
    val modelId: String,
    val delta: List<Double>,
    val steps: Int,
    val nQuantiles: Int,
    val nCal: Int,
    val nEval: Int,
    val maxAbsDeltaMgdl: Double,
    val cov90Raw: Double?,
    val cov90Cal: Double?,
    val meanWidth90Raw: Double?,
    val meanWidth90Cal: Double?,
    val windowDays: Int,
    val fittedAtMs: Long,
) {
    /**
     * When this correction stops being applied — one [windowDays] past the fit.
     *
     * The Rust module states the hazard it cannot repair from inside the fit: the whole method
     * rests on exchangeability between the calibration set and the forecasts the delta is later
     * applied to, and "a patient whose behaviour changes invalidates a delta fitted before it".
     * Nothing on device can detect that change, so the correction is trusted for exactly as long as
     * the history it was fitted on and no longer. Past it the BG panel draws the raw fan — the same
     * fail-closed default that holds before any fit exists, and the stance the suite takes
     * everywhere: withhold a number rather than show one that cannot be justified.
     *
     * The window is the fit's OWN rather than a constant, so a correction fitted over a longer
     * history survives proportionately longer without this restating what that history was.
     */
    val expiresAtMs: Long get() = fittedAtMs + windowDays.toLong() * 86_400_000L

    /** True once [expiresAtMs] has passed — the one predicate both the apply and the panel read. */
    fun expiredAt(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

/**
 * Why a recalibration produced no result at all, as distinct from the fit's own fail-closed refusal.
 *
 * Both are conditions of the CALLER, not of the patient's history: neither says anything about how
 * much matured forecast exists, and reporting either as an emptiness would tell the user their data
 * was inadequate when it is not.
 */
enum class BandFitRefusal {
    /** A fit is already in flight. A second entry is refused outright, never queued. */
    BUSY,

    /** The model's own forecast horizon could not be established, so there is no length at which a
     *  correction fitted now could ever match the fan it would later be applied to. */
    HORIZON_UNKNOWN,
}

/**
 * What one run of the recalibrate action did, for the panel that has to say so.
 *
 * The three outcomes are distinguishable, and must stay so — each names a different thing for the
 * user to do about it:
 *
 *  * [refusal] non-null — the call never reached the window walk. [fit] and the counts are absent
 *    rather than zero, because nothing measured them.
 *  * [refusal] null and [fit] null — the walk ran and found nothing at all to score.
 *  * `fit.sufficient == false` — the fail-closed refusal of the fit itself; [fit] carries the counts
 *    that name the reason and [stored] is false because storage was not touched.
 */
data class BandCalibrationOutcome(
    val fit: ConformalFit?,
    val stored: Boolean,
    val nMatured: Int,
    val nIncomplete: Int,
    val refusal: BandFitRefusal? = null,
)
