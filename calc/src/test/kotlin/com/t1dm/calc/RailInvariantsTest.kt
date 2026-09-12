package com.t1dm.calc

import com.t1dm.core.model.ForecastStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The blocking CI gate. */
class RailInvariantsTest {

    private val now = 1_900_000_000_000L

    @Test
    fun refuses_when_forecast_missing() = runTest {
        val advisor = advisorOf(
            port = FakeForecastPort(forceEligibility = ForecastEligibility.MISSING),
            anchor = fakeAnchor(now), iob = fakeIob(now),
        )
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig())
        assertTrue("a missing forecast must refuse", r is AdviceResult.Refused)
    }

    @Test
    fun refuses_when_forecast_degenerate() = runTest {
        val advisor = advisorOf(
            port = FakeForecastPort(forceEligibility = ForecastEligibility.DEGENERATE, forceStatus = ForecastStatus.RAIL_PINNED),
            anchor = fakeAnchor(now), iob = fakeIob(now),
        )
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig())
        assertTrue("a degenerate forecast must refuse", r is AdviceResult.Refused)
        assertTrue((r as AdviceResult.Refused).reasons.first().contains("degenerate", ignoreCase = true))
    }

    @Test
    fun refuses_when_no_selected_model() = runTest {
        val advisor = advisorOf(FakeForecastPort(), anchor = fakeAnchor(now), iob = fakeIob(now), backend = null)
        assertTrue(advisor.recommendBolus(now, emptyList(), CalcConfig()) is AdviceResult.Refused)
    }

    /** §3.6-E: the fp32 XNNPACK CPU authority is the only backend a dose may be scored on. */
    @Test
    fun only_the_fp32_cpu_authority_is_trustworthy() {
        assertTrue(
            BackendInfo(com.t1dm.core.model.BackendId.EXECUTORCH_XNNPACK_FP32).trustworthy,
        )
        for (bid in com.t1dm.core.model.BackendId.entries) {
            if (bid == com.t1dm.core.model.BackendId.EXECUTORCH_XNNPACK_FP32) continue
            assertFalse(
                "$bid must never be trustworthy for dosing",
                BackendInfo(bid).trustworthy,
            )
        }
    }

    @Test
    fun refuses_when_the_selected_model_is_not_on_the_authority() = runTest {
        for (bid in listOf(
            com.t1dm.core.model.BackendId.STUB,
            com.t1dm.core.model.BackendId.UNKNOWN,
        )) {
            val backend = BackendInfo(bid)
            val advisor = advisorOf(FakeForecastPort(), anchor = fakeAnchor(now), iob = fakeIob(now), backend = backend)
            assertTrue(
                "dosing must fail closed on $bid",
                advisor.recommendBolus(now, emptyList(), CalcConfig()) is AdviceResult.Refused,
            )
        }
    }

    @Test
    fun each_rail_blocks_not_passes_on_ineligible_fan() {
        for (elig in listOf(ForecastEligibility.MISSING, ForecastEligibility.DEGENERATE, ForecastEligibility.STALE)) {
            val bad = PredFan(3.0, emptyList(), STEP_MS, 24, ForecastStatus.COLLAPSED_BAND, elig)
            assertTrue("baseline gate must block $elig", Rails.baselineDegeneracy(bad) is RailVerdict.Block)
            assertTrue("predicted-low veto must block $elig", Rails.predictedLowVeto(bad, CalcConfig()) is RailVerdict.Block)
        }
    }

    @Test
    fun iob_ceiling_blocks_nonzero_dose_when_iob_unknown() {
        val v = Rails.iobCeiling(iob = IobSnapshot(iobU = null, cobG = 0.0, lastLoggedDoseTsMs = null), candidateU = 4.0, config = CalcConfig())
        assertTrue("unknown IOB + nonzero dose must block", v is RailVerdict.Block)
        assertEquals(RailVerdict.Pass, Rails.iobCeiling(IobSnapshot(null, 0.0, null), 0.0, CalcConfig()))
    }

    @Test
    fun all_rails_off_is_identity_over_randomized_scenarios() = runTest {
        val rng = Random(42)
        repeat(60) {
            val start = 90.0 + rng.nextDouble() * 180.0        // 90..270 mg/dL start
            val sens = 8.0 + rng.nextDouble() * 20.0           // mg/dL per U
            val port = FakeForecastPort(startBg = start, mgdlPerU = sens)
            val config = CalcConfig(rails = RailToggles.ALL_OFF, objective = randomObjective(rng))
            val advisor = advisorOf(port, anchor = fakeAnchor(now, currentBg = start), iob = fakeIob(now))

            val r = advisor.recommendBolus(now, emptyList(), config)
            assertTrue("rails-off must never refuse a fresh eligible forecast", r is AdviceResult.Recommended)
            r as AdviceResult.Recommended
            assertEquals("rails-off best == argmin", r.ranked.first().doseU, r.best.doseU, 0.0)
            assertTrue("rails-off adds no rail notes", r.railNotes.isEmpty())
            assertFalse("rails-off forces no confirmation", r.requiresConfirmation)
            assertNull("rails-off is not a rescue", r.rescueCarbsG)
        }
    }

    private fun randomObjective(rng: Random): Objective = when (rng.nextInt(3)) {
        0 -> Objective.MinTimeOutOfRange
        1 -> Objective.MinKovatchevRisk
        else -> Objective.HitTargetAtTime(atMsFromNow = 60 * 60_000L)
    }

    @Test
    fun predicted_low_veto_pulls_the_dose_back_from_a_low_tail() = runTest {
        // Aggressive sensitivity + low-ish start, so large doses drive a predicted low.
        val port = FakeForecastPort(startBg = 130.0, mgdlPerU = 40.0)
        val advisor = advisorOf(port, anchor = fakeAnchor(now, currentBg = 130.0), iob = fakeIob(now, iobU = 0.0))
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig()) as AdviceResult.Recommended
        val safe = r.best.doseU == 0.0 || (r.best.fan.minMedianBg() ?: 0.0) >= CalcConfig().predictedLowThresholdMgdl
        assertTrue("veto must keep the recommended dose out of predicted-low territory", safe)
    }

    @Test
    fun the_veto_ignores_a_low_that_lies_beyond_the_validated_window() = runTest {
        // The dip lies in the extrapolated tail only.
        val steps = List(48) { i -> FanStep(medianBg = if (i < 24) 140.0 else 50.0, lowerBg = 40.0, upperBg = 240.0) }
        val fan = PredFan(
            candidateU = 2.0,
            steps = steps,
            stepMs = 5 * 60_000L,
            validatedSteps = 24,
            worstStatus = ForecastStatus.OK,
            eligibility = ForecastEligibility.ELIGIBLE,
        )
        assertEquals(RailVerdict.Pass, Rails.predictedLowVeto(fan, CalcConfig()))
    }

    @Test
    fun the_veto_still_blocks_a_low_inside_the_validated_window() = runTest {
        val steps = List(48) { i -> FanStep(medianBg = if (i < 12) 140.0 else 55.0, lowerBg = 40.0, upperBg = 240.0) }
        val fan = PredFan(
            candidateU = 2.0,
            steps = steps,
            stepMs = 5 * 60_000L,
            validatedSteps = 24,
            worstStatus = ForecastStatus.OK,
            eligibility = ForecastEligibility.ELIGIBLE,
        )
        assertTrue("a median low inside the validated window must veto", Rails.predictedLowVeto(fan, CalcConfig()) is RailVerdict.Block)
    }

    @Test
    fun a_wide_band_alone_no_longer_vetoes() = runTest {
        // In-range median, lower edge under the floor throughout.
        val steps = List(48) { FanStep(medianBg = 150.0, lowerBg = 45.0, upperBg = 255.0) }
        val fan = PredFan(
            candidateU = 3.0,
            steps = steps,
            stepMs = 5 * 60_000L,
            validatedSteps = 24,
            worstStatus = ForecastStatus.OK,
            eligibility = ForecastEligibility.ELIGIBLE,
        )
        assertEquals(RailVerdict.Pass, Rails.predictedLowVeto(fan, CalcConfig()))
    }

    @Test
    fun a_wide_fan_still_yields_a_nonzero_dose_end_to_end() = runTest {
        // Base 60/growth 3.0 keeps lower edge under 70 all roll; default band (5/0.6) too narrow.
        val port = FakeForecastPort(startBg = 260.0, mgdlPerU = 15.0, bandBase = 60.0, bandGrowthPerStep = 3.0)
        val advisor = advisorOf(port, anchor = fakeAnchor(now, currentBg = 260.0), iob = fakeIob(now, iobU = 0.0))
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig()) as AdviceResult.Recommended
        assertTrue(
            "a wide band alone must not pin the advisor at 0 U (got ${r.best.doseU} U; notes=${r.railNotes})",
            r.best.doseU > 0.0,
        )
        assertNull("a hyper start with an in-range median is not a rescue", r.rescueCarbsG)
    }

    @Test
    fun an_empty_validated_window_fails_closed() = runTest {
        val steps = List(48) { FanStep(medianBg = 150.0, lowerBg = 140.0, upperBg = 160.0) }
        val fan = PredFan(
            candidateU = 1.0,
            steps = steps,
            stepMs = 5 * 60_000L,
            validatedSteps = 0,
            worstStatus = ForecastStatus.OK,
            eligibility = ForecastEligibility.ELIGIBLE,
        )
        assertTrue("no validated window means the low risk is unverifiable", Rails.predictedLowVeto(fan, CalcConfig()) is RailVerdict.Block)
    }

    @Test
    fun iob_unknown_forces_zero_dose_fallback() = runTest {
        val port = FakeForecastPort(startBg = 240.0, mgdlPerU = 15.0) // hyper: bolus wanted
        val advisor = advisorOf(port, anchor = fakeAnchor(now), iob = IobSnapshot(null, 0.0, null))
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig()) as AdviceResult.Recommended
        assertEquals("unknown IOB must fall back to 0 U", 0.0, r.best.doseU, 0.0)
        assertTrue(r.railNotes.any { it.contains("IOB", ignoreCase = true) })
    }

    @Test
    fun long_log_gap_with_nonzero_dose_is_mandatory_confirmation() = runTest {
        val port = FakeForecastPort(startBg = 240.0, mgdlPerU = 15.0)
        val advisor = advisorOf(port, anchor = fakeAnchor(now), iob = fakeIob(now, iobU = 0.0, lastLoggedMinAgo = 4 * 60))
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig()) as AdviceResult.Recommended
        assertTrue("a nonzero dose was expected", r.best.doseU > 0.0)
        assertTrue("long log gap must force confirmation", r.requiresConfirmation)
        assertTrue(r.card.requiresConfirmation)
        assertTrue(r.card.confirmationReasons.isNotEmpty())
    }

    @Test
    fun hypo_now_takes_the_carb_rescue_path_and_withholds_insulin() = runTest {
        val port = FakeForecastPort(startBg = 62.0, mgdlPerU = 15.0)
        val advisor = advisorOf(port, anchor = fakeAnchor(now, currentBg = 62.0), iob = fakeIob(now))
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig()) as AdviceResult.Recommended
        assertEquals("hypo path withholds insulin", 0.0, r.best.doseU, 0.0)
        assertNotNull("hypo path recommends rescue carbs", r.rescueCarbsG)
        assertTrue(r.rescueCarbsG!! > 0.0)
        assertTrue(r.requiresConfirmation)
    }

    @Test
    fun decision_card_carries_every_point_of_decision_field() = runTest {
        val port = FakeForecastPort(startBg = 230.0, mgdlPerU = 15.0)
        val advisor = advisorOf(port, anchor = fakeAnchor(now, ageMin = 3, interpolatedFraction = 0.1), iob = fakeIob(now, iobU = 1.5, lastLoggedMinAgo = 20))
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig()) as AdviceResult.Recommended
        val c = r.card
        assertEquals(3L, c.ageOfLastRealReadingMin)
        assertEquals(0.1, c.interpolatedFraction, 1e-9)
        assertEquals(com.t1dm.core.model.BackendId.EXECUTORCH_XNNPACK_FP32, c.backend)
        assertEquals(1.5, c.assumedIobU!!, 1e-9)
        assertEquals(20L, c.minSinceLastLoggedDose)
        assertNotNull("band width surfaced", c.bandWidthMgdl)
    }
}
