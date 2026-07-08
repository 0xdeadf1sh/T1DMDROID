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
    fun hypo_is_scored_off_the_lower_band_not_the_median() {
        // Two fans with an identical, in-range median but different band widths: the wider band dips
        // into hypo on its lower edge and MUST score worse under a hypo-weighted objective.
        val config = CalcConfig(objective = Objective.MinKovatchevRisk, asymmetry = Asymmetry(hypoWeight = 5.0, hyperWeight = 1.0))
        val tightMedian85 = fan(List(24) { 85.0 }, half = 5.0)   // lower ~80, above the 70 floor
        val wideMedian85 = fan(List(24) { 85.0 }, half = 25.0)   // lower ~60, into hypo
        assertTrue(Scoring.scoreFan(wideMedian85, config) > Scoring.scoreFan(tightMedian85, config))
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
    fun hit_target_at_time_penalises_deviation_at_the_requested_step() {
        val config = CalcConfig(objective = Objective.HitTargetAtTime(atMsFromNow = 60 * 60_000L)) // step 12
        val target = config.target.targetMgdl
        val onIt = fan(List(24) { target })
        val off = fan(List(24) { target + 60.0 })
        assertTrue(Scoring.scoreFan(off, config) > Scoring.scoreFan(onIt, config))
    }
}
