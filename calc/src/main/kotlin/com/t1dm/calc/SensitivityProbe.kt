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
 * **What it refuses, and what it reports.** Null means *no model response was obtained* — a stale,
 * warm-up, absent or mostly-fabricated anchor, no selected model, a non-eligible fan, an empty or
 * ragged validated window, or arithmetic that yields no finite number. The panel renders that as
 * `N/A`, so an absent figure is legible as an absence rather than as a feature that failed to appear.
 *
 * A response that WAS obtained is reported whatever it says, including a negative ISF, and the panel
 * marks it. This is deliberate and is not the fail-closed stance relaxing: fail-closed governs
 * numbers that could be acted on, and nothing downstream can act on this one. What it buys is the
 * only view of a model's marginal behaviour available outside a debug build — a wrong-signed ISF is
 * a fact about the artifact, and filtering it out hid a real model defect behind a blank line.
 *
 * It never throws and never fabricates a figure. Nothing here can reach a dose: the result is a
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
    /** The selected model's id, read fresh. Sampled either side of the three rolls so a selection
     *  changed mid-probe yields no figure rather than one stamped with the wrong artifact. */
    private val selectedModelId: suspend () -> String?,
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

        val modelBefore = selectedModelId() ?: return withhold("no selected model")

        val baseline = port.roll(request(null, 0.0))
        val withInsulin = port.roll(request(insulin.resolve(PROBE_DOSE_U, nowMs), PROBE_DOSE_U))
        // candidateU stays 0: it is the candidate's INSULIN total, which the IOB rail and the
        // decision card read. A meal contributes none of it.
        val withCarb = port.roll(request(carb.resolve(PROBE_CARB_G, nowMs), 0.0))

        // The three rolls are only comparable to each other if one artifact produced all three.
        val modelAfter = selectedModelId()
        if (modelAfter != modelBefore) return withhold("model changed mid-probe: $modelBefore -> $modelAfter")

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

        // NO direction or magnitude filter. A wrong-signed or implausible response is what the model
        // actually said, and saying it is the point: this read-out doubles as the one place a model's
        // marginal behaviour is visible without a debug build. Only arithmetic that produces no
        // number at all refuses below — a zero carb response divides to an infinity, which is not a
        // figure to display but an absence of one.
        //
        // The panel marks a wrong-signed figure rather than hiding it. The §3.6-D gates above are a
        // different matter and still refuse: those are about the INPUT not being current, where there
        // is no model response to report honestly in the first place.
        val isf = insulinDrop / PROBE_DOSE_U
        val icr = isf * PROBE_CARB_G / carbRise
        if (!isf.isFinite() || !icr.isFinite()) {
            return withhold("non-finite: dI=%.2f dC=%.2f isf=%s icr=%s".format(insulinDrop, carbRise, isf, icr))
        }
        Timber.tag(TAG).i("probe: ISF %.1f mg/dL/U, ICR %.1f g/U (dI=%.2f dC=%.2f)", isf, icr, insulinDrop, carbRise)

        return SensitivityEstimate(
            atMs = nowMs,
            horizonMs = steps.toLong() * baseline.stepMs,
            isfMgdlPerU = isf,
            icrGPerU = icr,
            modelId = modelBefore,
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

        /** The probe meal. Ten grams rather than one: a single gram's predicted rise is small enough
         *  to sit inside the decode's own grain, and the response is not assumed linear, so the
         *  divisor must be a quantity the model can actually resolve. */
        const val PROBE_CARB_G = 10.0

        // No direction filter, no noise floor, no magnitude band — all three were removed
        // deliberately, in that order, as it became clear the read-out's real job is to say what the
        // model believes rather than to vouch for it. A model whose marginal insulin response is
        // wrong-signed is exactly what an author needs to see, and it was invisible while the probe
        // filtered it out: the app showed nothing, which is indistinguishable from the feature being
        // broken. The panel marks such a figure and names the horizon it was measured at.
    }
}
