package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.SensitivityEstimate
import timber.log.Timber

/**
 * Resolves an announced meal into its appearance-(Ra)-curve [CurveEvent]s, so the probe stays
 * agnostic of the curve engine — the carbohydrate twin of [BolusResolver]. In production this is
 * backed by `CurveEngine.carbEvent` at the mixed-meal GI default; in tests a fake returns a marker
 * the fake [ForecastPort] reads.
 *
 * The glycaemic index is the resolver's to pin, not the probe's, and it is not incidental: GI shapes
 * how much of a meal has appeared by the probe's horizon, so it moves the estimated ratio. One
 * resolver, one GI, or two probes are not comparable.
 */
fun interface CarbResolver {
    suspend fun resolve(grams: Double, atMs: Long): List<CurveEvent>
}

/**
 * Estimates this patient's insulin sensitivity factor and insulin-to-carbohydrate ratio by asking
 * the selected fp32 authority what one unit and ten grams would each do, right now.
 *
 * Three rolls of the SAME [ForecastPort] the dose calculator searches against, differing only in
 * what is injected into the prediction zone:
 *
 * | roll | candidate |
 * | --- | --- |
 * | baseline | — |
 * | insulin | [PROBE_DOSE_U] U as a rapid PK curve |
 * | carbohydrate | [PROBE_CARB_G] g as an appearance (Ra) curve |
 *
 * **Both counterfactuals ride `candidate`, and that is load-bearing.** [ForecastRequest.announced]
 * and [ForecastRequest.candidate] are not interchangeable: the production [RollingForecaster]
 * re-anchors only the candidate onto the prediction zone's first bucket, because `announced` carries
 * the user's real committed future at real wall-clock instants while a candidate is a what-if
 * injected at the roll origin. A probe is a what-if. Passing the meal as `announced` — which this
 * did first — left the two counterfactuals injected at instants that differed by the gap between now
 * and the next grid boundary, so the ratio between them was measured off a meal further advanced
 * than the dose it was being ratioed against, and the leading Ra bucket could be dropped outright by
 * `bucketize`'s negative-index guard. `futureOverrides` splits a candidate by [CurveEvent.kind], so
 * a carbohydrate candidate reaches the carb channel exactly as an insulin one reaches the insulin
 * channel.
 *
 * Each roll is capped to the validated window ([HorizonPolicy.validatedSteps]), so `nRolls` is 1 and
 * the whole probe costs three model forwards with no autoregressive re-feed. The medians are read at
 * the END of that window and differenced:
 *
 * ```
 * ISF = median_baseline − median_insulin                    (mg/dL per U)
 * ICR = ISF × PROBE_CARB_G / (median_carb − median_baseline) (g per U)
 * ```
 *
 * **The horizon is part of the answer.** The read is at the validated window — the same prefix every
 * dose decision is capped to, and the only part of a roll this project is willing to call validated.
 * A rapid analogue is nowhere near finished acting there, so [SensitivityEstimate.isfMgdlPerU] is
 * strictly smaller than the whole-action ISF a clinician would quote, and the ICR is shifted by
 * however much of the meal has appeared by then. Reading the full ~5 h action window instead would
 * match the textbook definitions but would rest the number on the re-fed extrapolated tail.
 *
 * **Fail-closed.** Returns null — the panel then shows nothing at all, not a dash — whenever the
 * estimate cannot be justified: a stale, warm-up, absent or mostly-fabricated anchor, a non-eligible
 * fan, an empty or ragged validated window, a response in the wrong direction, a response too small
 * to separate from decode noise, or a figure outside the band a patient parameter can occupy. It
 * never throws and never fabricates a figure. Nothing here can reach a dose: the result is a
 * [SensitivityEstimate], a type no rail, no advisor and no store accepts.
 */
class SensitivityProbe(
    private val port: ForecastPort,
    private val insulin: BolusResolver,
    private val carb: CarbResolver,
    /** §3.6-D anchor facts. The probe gates on these ITSELF rather than borrowing [Rails.freshness]:
     *  that rail is switchable by the user because a dose is theirs to take, and it speaks in the
     *  language of refusing to dose. Whether a displayed figure was derived from a current reading is
     *  not a preference, so this gate has no toggle. */
    private val anchorSource: AnchorInfoSource,
) {

    suspend fun probe(
        nowMs: Long,
        config: CalcConfig,
        /** Pinned across all three rolls, exactly as the [DoseAdvisor] pins one window across a
         *  candidate grid: differencing two fans built on different BG input filters would attribute
         *  the filter's own step to the dose. */
        smoothingWindow: Int? = null,
    ): SensitivityEstimate? {
        val steps = config.horizon.validatedSteps
        if (steps <= 0) return withhold("validated horizon is 0 steps")

        // §3.6-D. The rolls themselves cannot catch this: the production port never reports STALE by
        // its own documented contract, and a carried-forward anchor yields a perfectly eligible fan
        // that simply describes a BG from some time ago. Stamping that `atMs = nowMs` and printing it
        // beside a live IOB is the one way this read-out could mislead badly.
        val anchor = anchorSource.current(nowMs) ?: return withhold("no anchor")
        val lastMeasured = anchor.lastMeasuredTsMs ?: return withhold("no measured reading")
        if (anchor.warmup) return withhold("sensor warm-up")
        val anchorAgeMs = nowMs - lastMeasured
        if (anchorAgeMs > config.freshnessMaxAgeMs) {
            return withhold("anchor ${anchorAgeMs / 60_000}m old (limit ${config.freshnessMaxAgeMs / 60_000}m)")
        }
        if (anchor.interpolatedFraction > config.maxInterpolatedFraction) {
            return withhold("interpolated %.2f > %.2f".format(anchor.interpolatedFraction, config.maxInterpolatedFraction))
        }

        fun request(candidate: List<CurveEvent>?, candidateU: Double) =
            ForecastRequest(
                rollStartMs = nowMs,
                fullRollSteps = steps,
                validatedSteps = steps,
                announced = emptyList(),
                candidate = candidate,
                candidateU = candidateU,
                smoothingWindow = smoothingWindow,
            )

        val baseline = port.roll(request(null, 0.0))
        val withInsulin = port.roll(request(insulin.resolve(PROBE_DOSE_U, nowMs), PROBE_DOSE_U))
        // candidateU stays 0: it is the candidate's INSULIN total, which the IOB rail and the
        // decision card read. A meal contributes none of it.
        val withCarb = port.roll(request(carb.resolve(PROBE_CARB_G, nowMs), 0.0))

        val fans = listOf(baseline, withInsulin, withCarb)
        if (fans.any { !it.eligible }) return withhold("fans ${fans.map { it.eligibility }}")

        val windows = fans.map { it.validatedWindow() }
        val n = windows[0].size
        // Ragged windows would difference two different instants and call the gap a dose response.
        if (n == 0 || windows.any { it.size != n }) return withhold("windows ${windows.map { it.size }}")

        val terminal = windows.map { it[n - 1].medianBg }
        if (terminal.any { !it.isFinite() }) return withhold("non-finite terminal $terminal")

        val insulinDrop = terminal[0] - terminal[1]
        val carbRise = terminal[2] - terminal[0]
        // Direction AND magnitude. Insulin must lower and carbohydrate must raise, by more than the
        // decode's own grain — below which the difference is noise, and dividing by it is worse.
        if (insulinDrop < MIN_RESPONSE_MGDL || carbRise < MIN_RESPONSE_MGDL) {
            return withhold("response too small: dI=%.2f dC=%.2f (floor %.2f)".format(insulinDrop, carbRise, MIN_RESPONSE_MGDL))
        }

        val isf = insulinDrop / PROBE_DOSE_U
        val icr = isf * PROBE_CARB_G / carbRise
        if (!isf.isFinite() || !icr.isFinite()) return withhold("non-finite isf=$isf icr=$icr")
        Timber.tag(TAG).i("probe ok: ISF %.1f mg/dL/U, ICR %.1f g/U (dI=%.2f dC=%.2f)", isf, icr, insulinDrop, carbRise)

        return SensitivityEstimate(
            atMs = nowMs,
            horizonMs = steps.toLong() * baseline.stepMs,
            isfMgdlPerU = isf,
            icrGPerU = icr,
        )
    }

    /** Every refusal states WHY, at INFO. A withheld estimate is indistinguishable on screen from a
     *  feature that was never wired, and the difference is one log line. */
    private fun withhold(why: String): SensitivityEstimate? {
        Timber.tag(TAG).i("probe withheld: %s", why)
        return null
    }

    companion object {
        private const val TAG = "Sensitivity"

        /** The probe dose. One unit, so the drop IS the ISF without a scaling step to get wrong. */
        const val PROBE_DOSE_U = 1.0

        /** The probe meal. Ten grams rather than one: a single gram's predicted rise sits inside the
         *  noise floor below, and the response is not assumed linear, so the divisor must be a
         *  quantity the model can actually resolve. */
        const val PROBE_CARB_G = 10.0

        /** The smallest median displacement the probe will treat as a response, in mg/dL. Under this
         *  the two fans are the same forecast to within what the decode can express, and their
         *  difference carries no sensitivity information. */
        const val MIN_RESPONSE_MGDL = 1.0

        // There is deliberately NO magnitude band on the two outputs — the author's call, made after
        // seeing what the probe actually reports on real data. The direction and noise-floor checks
        // above stay, because insulin that raises BG is not a small sensitivity but a wrong one; what
        // is gone is any judgement about whether a correctly-signed figure is too large or too small
        // to be worth showing. The read-out names its horizon instead, so a number the model believes
        // is presented as what it is rather than filtered against an assumption about the patient.
        //
        // The known cost, accepted: flooring the divisor at MIN_RESPONSE_MGDL bounds the divisor
        // without bounding the quotient, so a barely-responsive carb roll against a normal insulin
        // roll (carbRise 1.05, insulinDrop 40) still yields 381 g/U. That renders as "ICR 381g/U @2h".
    }
}
