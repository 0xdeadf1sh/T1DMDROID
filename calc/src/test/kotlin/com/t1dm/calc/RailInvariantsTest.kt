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

/**
 * The `rail-invariants` property tests (Phase 4 §7, risk S15) — the **blocking** CI gate. They
 * assert the three load-bearing safety properties across randomized inputs:
 *
 *  1. **fail-closed** on missing / DEGENERATE / STALE / collapsed-band input — an enabled rail BLOCKS,
 *     never silently passes;
 *  2. **all-rails-off = identity** — with every optional rail disabled the recommendation is exactly
 *     the objective's argmin, unaltered;
 *  3. **no actuator** — structurally there is no code path from an [AdviceResult] to insulin delivery
 *     (asserted in [NoActuatorStructuralTest]).
 */
class RailInvariantsTest {

    private val now = 1_900_000_000_000L

    // ── (1) Fail-closed on each bad-input class ─────────────────────────────────────────

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
    fun refuses_when_anchor_stale() = runTest {
        val advisor = advisorOf(
            port = FakeForecastPort(),
            anchor = fakeAnchor(now, ageMin = 40), iob = fakeIob(now), // 40 min > 15 min default
        )
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig())
        assertTrue("a stale anchor must refuse", r is AdviceResult.Refused)
        assertTrue((r as AdviceResult.Refused).reasons.first().contains("stale", ignoreCase = true))
    }

    @Test
    fun refuses_when_no_measured_reading() = runTest {
        val advisor = advisorOf(FakeForecastPort(), anchor = fakeAnchor(now, hasMeasured = false), iob = fakeIob(now))
        assertTrue(advisor.recommendBolus(now, emptyList(), CalcConfig()) is AdviceResult.Refused)
    }

    @Test
    fun refuses_when_no_anchor_at_all() = runTest {
        val advisor = advisorOf(FakeForecastPort(), anchor = null, iob = fakeIob(now))
        assertTrue(advisor.recommendBolus(now, emptyList(), CalcConfig()) is AdviceResult.Refused)
    }

    @Test
    fun refuses_when_no_selected_model() = runTest {
        val advisor = advisorOf(FakeForecastPort(), anchor = fakeAnchor(now), iob = fakeIob(now), backend = null)
        assertTrue(advisor.recommendBolus(now, emptyList(), CalcConfig()) is AdviceResult.Refused)
    }

    @Test
    fun refuses_when_fp16_disagrees() = runTest {
        val backend = BackendInfo(com.t1dm.core.model.BackendId.EXECUTORCH_NEURON_FP16, com.t1dm.core.model.Precision.FP16, agreementOk = false)
        val advisor = advisorOf(FakeForecastPort(), anchor = fakeAnchor(now), iob = fakeIob(now), backend = backend)
        assertTrue(advisor.recommendBolus(now, emptyList(), CalcConfig()) is AdviceResult.Refused)
    }

    @Test
    fun authority_pinned_dosing_survives_a_non_agreeing_displayed_gpu() = runTest {
        // The root-cause fix (§3.6-E): the switcher governs the DISPLAYED forecast only; dose advice is
        // ALWAYS computed on the fp32 XNNPACK CPU authority. So even while a non-agreeing Vulkan GPU
        // renders what the user sees, `:calc` consumes an authority-produced forecast and the advisor
        // EMITS advice — the backend-agreement refusal never arises in normal use. This is exactly the
        // BackendInfo the composition root builds when Vulkan is selected: backend == the CPU authority
        // (trustworthy by construction), displayedBackend == the GPU (informational only).
        val authorityWhileGpuDisplayed = BackendInfo(
            backend = com.t1dm.core.model.BackendId.EXECUTORCH_XNNPACK_FP32,
            precision = com.t1dm.core.model.Precision.FP32,
            agreementOk = null,
            displayedBackend = com.t1dm.core.model.BackendId.EXECUTORCH_VULKAN_FP32,
        )
        assertTrue("authority-pinned dosing is trustworthy regardless of the displayed backend",
            authorityWhileGpuDisplayed.trustworthy)
        val advisor = advisorOf(
            FakeForecastPort(startBg = 230.0, mgdlPerU = 15.0),
            anchor = fakeAnchor(now), iob = fakeIob(now, iobU = 0.0),
            backend = authorityWhileGpuDisplayed,
        )
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig())
        assertTrue("a non-agreeing DISPLAYED backend must NOT refuse when dosing runs on the authority",
            r is AdviceResult.Recommended)
        r as AdviceResult.Recommended
        // The forecast :calc consumed is the AUTHORITY's: the decision card records the fp32 CPU path,
        // never the GPU the user is looking at.
        assertEquals(com.t1dm.core.model.BackendId.EXECUTORCH_XNNPACK_FP32, r.card.backend)
        assertEquals(com.t1dm.core.model.Precision.FP32, r.card.precision)
        // …and a small NON-BLOCKING note discloses that the GPU only rendered the display.
        assertTrue("a non-blocking display-provenance note is surfaced",
            r.railNotes.any { it.contains("rendered by", ignoreCase = true) && it.contains("CPU authority", ignoreCase = true) })
    }

    @Test
    fun gpu_backend_cannot_feed_calc_without_agreement() = runTest {
        // §3.6-E / issue 20 STEP 6: selecting a GPU/NPU backend must NOT silently feed the dosing
        // path. Even at fp32, a NON-AUTHORITATIVE backend (the Vulkan GPU delegate) is trustworthy
        // for a dose ONLY once it has PASSED the fp32-agreement probe — never on precision alone.
        // The composition root now feeds `:calc` the authority (never a raw GPU BackendInfo), so this
        // is satisfied BY CONSTRUCTION; the assertions below still pin the DoseAdvisor's fail-closed
        // contract directly — a bad BackendInfo reaching it (defence in depth) must STILL block.
        val vulkanUnproven = BackendInfo(
            com.t1dm.core.model.BackendId.EXECUTORCH_VULKAN_FP32,
            com.t1dm.core.model.Precision.FP32,
            agreementOk = null,
        )
        assertFalse("fp32 GPU without an agreement probe must not be trustworthy", vulkanUnproven.trustworthy)
        val advisor = advisorOf(FakeForecastPort(), anchor = fakeAnchor(now), iob = fakeIob(now), backend = vulkanUnproven)
        assertTrue(
            "dosing must fail closed on an unproven GPU backend",
            advisor.recommendBolus(now, emptyList(), CalcConfig()) is AdviceResult.Refused,
        )
        // The authoritative CPU path stays trusted; the GPU path becomes trusted ONLY once it agrees,
        // and a measured DISAGREEMENT keeps it out of :calc.
        assertTrue(BackendInfo(com.t1dm.core.model.BackendId.EXECUTORCH_XNNPACK_FP32, com.t1dm.core.model.Precision.FP32, null).trustworthy)
        assertTrue(BackendInfo(com.t1dm.core.model.BackendId.EXECUTORCH_VULKAN_FP32, com.t1dm.core.model.Precision.FP32, agreementOk = true).trustworthy)
        assertFalse(BackendInfo(com.t1dm.core.model.BackendId.EXECUTORCH_VULKAN_FP32, com.t1dm.core.model.Precision.FP32, agreementOk = false).trustworthy)
        // The fp16 Vulkan GPU delegate is held to the SAME gate: fp16 alone is never trustworthy for a
        // dose; only a PASSED agreement probe (agreementOk == true) admits it, and a FAIL keeps it out.
        assertFalse("fp16 GPU without a probe must not be trustworthy",
            BackendInfo(com.t1dm.core.model.BackendId.EXECUTORCH_VULKAN_FP16, com.t1dm.core.model.Precision.FP16, agreementOk = null).trustworthy)
        assertFalse("fp16 GPU that FAILS the probe must not be trustworthy",
            BackendInfo(com.t1dm.core.model.BackendId.EXECUTORCH_VULKAN_FP16, com.t1dm.core.model.Precision.FP16, agreementOk = false).trustworthy)
        assertTrue(BackendInfo(com.t1dm.core.model.BackendId.EXECUTORCH_VULKAN_FP16, com.t1dm.core.model.Precision.FP16, agreementOk = true).trustworthy)
    }

    @Test
    fun each_rail_blocks_not_passes_on_ineligible_fan() {
        // Direct unit assertion of the "an enabled rail never passes bad input" contract.
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
        // …but a zero dose is always safe.
        assertEquals(RailVerdict.Pass, Rails.iobCeiling(IobSnapshot(null, 0.0, null), 0.0, CalcConfig()))
    }

    // ── (2) All-rails-off = identity ────────────────────────────────────────────────────

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
            // Identity: the chosen dose is exactly the objective argmin (ranked-best), with no rail edits.
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

    // ── Rail behaviours (protective, not just present) ──────────────────────────────────

    @Test
    fun predicted_low_veto_pulls_the_dose_back_from_a_low_tail() = runTest {
        // Aggressive sensitivity + low-ish start ⇒ large doses drive a predicted low; the veto must
        // choose a dose whose MEDIAN never crosses the floor inside the VALIDATED window, or fall
        // back to 0 U. Both halves changed together: the rail reads the median, not the τ=.05 edge,
        // and only the validated prefix, not the extrapolated tail.
        val port = FakeForecastPort(startBg = 130.0, mgdlPerU = 40.0)
        val advisor = advisorOf(port, anchor = fakeAnchor(now, currentBg = 130.0), iob = fakeIob(now, iobU = 0.0))
        val r = advisor.recommendBolus(now, emptyList(), CalcConfig()) as AdviceResult.Recommended
        val safe = r.best.doseU == 0.0 || (r.best.fan.minMedianBg() ?: 0.0) >= CalcConfig().predictedLowThresholdMgdl
        assertTrue("veto must keep the recommended dose out of predicted-low territory", safe)
    }

    @Test
    fun the_veto_ignores_a_low_that_lies_beyond_the_validated_window() = runTest {
        // The regression that motivated the change: a fan whose median only dips under the floor in
        // the EXTRAPOLATED tail must not block a dose. Previously any such dip — and, reading the
        // band, almost every roll had one — vetoed every candidate including the do-nothing
        // baseline, so the advisor could return nothing but 0 U.
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
        // The mechanism that pinned the advisor at 0 U: a median comfortably in range with a band
        // whose lower edge sits under the floor throughout. That must now pass.
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
        // THE regression this whole change exists for, and the one the gate was missing: a hyper
        // start with a realistically WIDE fan. `FakeForecastPort`'s default band (base 5, growth
        // 0.6) is far too narrow to have ever reproduced the bug, which is why :calc stayed green
        // throughout the period the advisor could only return 0 U. At base 60 / growth 3.0 the
        // lower edge sits under the 70 floor across the whole roll, so the old lower-band veto
        // blocked every candidate — including the do-nothing baseline — and the loop fell through
        // to zero. The median never approaches the floor, so the rail must now pass.
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
        val port = FakeForecastPort(startBg = 240.0, mgdlPerU = 15.0) // hyper ⇒ a bolus is otherwise wanted
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
        assertEquals(com.t1dm.core.model.Precision.FP32, c.precision)
        assertEquals(1.5, c.assumedIobU!!, 1e-9)
        assertEquals(20L, c.minSinceLastLoggedDose)
        assertNotNull("band width surfaced", c.bandWidthMgdl)
    }
}
