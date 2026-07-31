package com.t1dm.calc

import com.t1dm.core.model.ForecastStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Objective-scoring invariants: monotonicity, horizon discounting, lower-band hypo, fail-closed. */
class ObjectiveScoringTest {

    private fun fan(medians: List<Double>, half: Double = 5.0, validated: Int = 24): PredFan =
        PredFan(
            candidateU = 0.0,
            steps = medians.map { FanStep(it, it - half, it + half) },
            stepMs = STEP_MS,
            validatedSteps = validated,
            worstStatus = ForecastStatus.OK,
            eligibility = ForecastEligibility.ELIGIBLE,
        )

    @Test
    fun ineligible_fan_scores_to_infinity() {
        val bad = PredFan(1.0, emptyList(), STEP_MS, 24, ForecastStatus.NON_FINITE, ForecastEligibility.DEGENERATE)
        assertEquals(Double.POSITIVE_INFINITY, Scoring.scoreFan(bad, CalcConfig()), 0.0)
    }

    @Test
    fun kovatchev_risk_falls_as_a_hyper_forecast_approaches_target() {
        val config = CalcConfig(objective = Objective.MinKovatchevRisk)
        val hyper = fan(List(30) { 260.0 })
        val nearer = fan(List(30) { 190.0 })
        val onTarget = fan(List(30) { 110.0 })
        assertTrue(Scoring.scoreFan(hyper, config) > Scoring.scoreFan(nearer, config))
        assertTrue(Scoring.scoreFan(nearer, config) > Scoring.scoreFan(onTarget, config))
    }

    @Test
    fun hypo_is_scored_off_the_median_and_band_width_is_inert() {
        // Hypo is scored off the MEDIAN. Two fans with an identical in-range median and very
        // different band widths must therefore score IDENTICALLY: widening a band can no longer, by
        // itself, make a candidate look hypo-risky. That inertness is the point of the change — the
        // old lower-band rule penalised every nonzero dose on a realistically wide fan.
        val config = CalcConfig(objective = Objective.MinKovatchevRisk, asymmetry = Asymmetry(hypoWeight = 5.0, hyperWeight = 1.0))
        val tightMedian85 = fan(List(24) { 85.0 }, half = 5.0)
        val wideMedian85 = fan(List(24) { 85.0 }, half = 25.0)
        assertEquals(Scoring.scoreFan(tightMedian85, config), Scoring.scoreFan(wideMedian85, config), 1e-9)
    }

    @Test
    fun a_lower_median_still_scores_worse_under_a_hypo_weighted_objective() {
        // The protection that must survive: a candidate whose MEDIAN sits lower is penalised more.
        // Band width is now inert, but the direction of the hypo preference is not.
        val config = CalcConfig(objective = Objective.MinKovatchevRisk, asymmetry = Asymmetry(hypoWeight = 5.0, hyperWeight = 1.0))
        val inRange = fan(List(24) { 110.0 }, half = 10.0)
        val lowMedian = fan(List(24) { 62.0 }, half = 10.0)
        assertTrue(Scoring.scoreFan(lowMedian, config) > Scoring.scoreFan(inRange, config))
    }

    @Test
    fun horizon_discounts_steps_beyond_the_validated_window() {
        val config = CalcConfig(objective = Objective.MinTimeOutOfRange)
        // A hyper excursion entirely inside the validated window vs the same excursion entirely beyond it.
        val inside = fan(List(24) { 250.0 } + List(36) { 110.0 }, validated = 24)
        val beyond = fan(List(24) { 110.0 } + List(36) { 250.0 }, validated = 24)
        val sIn = Scoring.scoreFan(inside, config)
        val sBeyond = Scoring.scoreFan(beyond, config)
        assertTrue("validated-window excursions must dominate selection", sIn > sBeyond)
        // The beyond-window contribution is discounted, not free.
        assertTrue(sBeyond > 0.0)
    }

    @Test
    fun asymmetry_lets_the_user_punish_hypo_harder_than_hyper() {
        val hypoHeavy = CalcConfig(objective = Objective.MinTimeOutOfRange, asymmetry = Asymmetry(hypoWeight = 10.0, hyperWeight = 1.0))
        val symmetric = CalcConfig(objective = Objective.MinTimeOutOfRange, asymmetry = Asymmetry(hypoWeight = 1.0, hyperWeight = 1.0))
        val lowFan = fan(List(24) { 60.0 })
        assertTrue(Scoring.scoreFan(lowFan, hypoHeavy) > Scoring.scoreFan(lowFan, symmetric))
    }

    @Test
    fun hit_target_bg_carries_an_intrinsic_hypo_guard_off_the_median() {
        // The Bolus advisor forces HitTargetBg; its hypo guard must be intrinsic, not resting on the
        // user-disableable predicted-low veto rail. Scoring never consults the rails, so this holds
        // regardless of RailToggles.predictedLowVeto. The guard now reads the MEDIAN: a fan whose
        // median sits below the floor pays the lbgi term ON TOP of its median-to-target distance,
        // so it scores worse than a fan the same distance ABOVE target.
        val config = CalcConfig(
            objective = Objective.HitTargetBg(targetMgdl = 110.0),
            asymmetry = Asymmetry(hypoWeight = 5.0, hyperWeight = 1.0),
        )
        // The two fans sit the SAME absolute distance from target — 50 mg/dL below and above — under
        // SYMMETRIC weights, so the median-to-target term contributes identically to both and cancels.
        // The only thing that can separate them is the intrinsic hypo term. Delete that line from
        // Scoring and this assertion fails; separating the fans by median alone would not, because
        // the deviation term carries that unaided.
        val symmetric = CalcConfig(
            objective = Objective.HitTargetBg(targetMgdl = 110.0),
            asymmetry = Asymmetry(hypoWeight = 1.0, hyperWeight = 1.0),
        )
        val belowFloor = fan(List(24) { 60.0 }, half = 5.0)   // 50 below target, and under the 70 floor
        val aboveTarget = fan(List(24) { 160.0 }, half = 5.0) // 50 above target, no hypo term
        assertTrue(
            "the intrinsic hypo term must be what separates equal deviations",
            Scoring.scoreFan(belowFloor, symmetric) > Scoring.scoreFan(aboveTarget, symmetric),
        )
        // The configured asymmetry is still applied on top, independently of that term.
        assertTrue(Scoring.scoreFan(belowFloor, config) > Scoring.scoreFan(belowFloor, symmetric))
        // Dead-on target scores to the floor of zero.
        val onTarget = fan(List(24) { 110.0 }, half = 5.0)
        assertEquals(0.0, Scoring.scoreFan(onTarget, config), 0.0)
        // And band width alone changes nothing.
        assertEquals(
            Scoring.scoreFan(onTarget, config),
            Scoring.scoreFan(fan(List(24) { 110.0 }, half = 50.0), config),
            1e-9,
        )
    }

    @Test
    fun hit_target_at_time_penalises_deviation_at_the_requested_step() {
        val config = CalcConfig(objective = Objective.HitTargetAtTime(atMsFromNow = 60 * 60_000L)) // step 12
        val target = config.target.targetMgdl
        val onIt = fan(List(24) { target })
        val off = fan(List(24) { target + 60.0 })
        assertTrue(Scoring.scoreFan(off, config) > Scoring.scoreFan(onIt, config))
    }
}
