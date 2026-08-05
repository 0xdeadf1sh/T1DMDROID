package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.ForecastStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model-probed ISF/ICR estimator, against the deterministic [FakeForecastPort] whose linear
 * response makes the expected figures exact: at the end of the roll the fake has applied the whole
 * of `mgdlPerU` per candidate unit and the whole of `mgdlPerG` per carb gram.
 *
 * The arithmetic test is the smaller half. The rest is the fail-closed contract — this estimate is
 * shown beside a patient's live IOB, so every branch that cannot justify a number must produce no
 * number rather than a plausible one.
 */
class SensitivityProbeTest {

    private val now = 1_900_000_000_000L
    private val config = CalcConfig()
    private val MODEL = "t1dm-ft-2026-08"

    private class FakeCarbResolver : CarbResolver {
        override suspend fun resolve(grams: Double, atMs: Long): List<CurveEvent> =
            listOf(CurveEvent(atMs, STEP_MS, CurveKind.CARB, grams, listOf(grams)))
    }

    private fun probeOf(
        port: ForecastPort,
        anchor: AnchorInfo? = fakeAnchor(now),
        modelIds: () -> String? = { MODEL },
    ) = SensitivityProbe(port, FakeBolusResolver(), FakeCarbResolver(), { anchor }, { modelIds() })

    @Test
    fun isf_and_icr_are_the_terminal_median_displacements() = runTest {
        val port = FakeForecastPort(startBg = 180.0, mgdlPerU = 15.0, mgdlPerG = 3.0)
        val est = probeOf(port).probe(now, config)!!

        // 1 U lowers the terminal median by mgdlPerU.
        assertEquals("ISF is the per-unit drop", 15.0, est.isfMgdlPerU, 1e-9)
        // 10 g raises it by 30, so one unit covers 15/3 = 5 g.
        assertEquals("ICR is the grams one unit cancels", 5.0, est.icrGPerU, 1e-9)
        assertEquals("stamped at the probe instant", now, est.atMs)
        assertEquals("horizon is the validated window", 24L * STEP_MS, est.horizonMs)
        assertEquals("stamped with the artifact it describes", MODEL, est.modelId)
    }

    // ── the estimate belongs to one model ─────────────────────────────────────────────────────────

    @Test
    fun no_selected_model_yields_no_figure() = runTest {
        assertNull(probeOf(FakeForecastPort(), modelIds = { null }).probe(now, config))
    }

    @Test
    fun a_selection_changed_mid_probe_yields_no_figure() = runTest {
        // Three rolls are comparable only if one artifact produced all of them; a switch between the
        // first and last would otherwise be reported as the new model's sensitivity.
        val ids = ArrayDeque(listOf("model-a", "model-b"))
        val probe = probeOf(FakeForecastPort(), modelIds = { ids.removeFirstOrNull() ?: "model-b" })
        assertNull(probe.probe(now, config))
    }

    @Test
    fun the_ratio_tracks_the_carb_response_independently_of_the_insulin_one() = runTest {
        val est = probeOf(FakeForecastPort(mgdlPerU = 50.0, mgdlPerG = 4.0)).probe(now, config)!!
        assertEquals(50.0, est.isfMgdlPerU, 1e-9)
        assertEquals(12.5, est.icrGPerU, 1e-9)
    }

    @Test
    fun three_rolls_and_no_more() = runTest {
        val port = FakeForecastPort()
        probeOf(port).probe(now, config)
        assertEquals("baseline + insulin + carb, each one window", 3, port.rollCount)
    }

    @Test
    fun a_non_eligible_fan_withholds_the_estimate() = runTest {
        for (elig in listOf(ForecastEligibility.DEGENERATE, ForecastEligibility.STALE, ForecastEligibility.MISSING)) {
            val port = FakeForecastPort(forceEligibility = elig, forceStatus = ForecastStatus.OK)
            assertNull("$elig must withhold", probeOf(port).probe(now, config))
        }
    }

    // ── a response that was obtained is REPORTED, whatever it says ────────────────────────────────
    // This is the read-out's remaining job. A model whose marginal insulin response is wrong-signed
    // is a fact about the artifact, and it was invisible while the probe filtered it out: the panel
    // showed nothing, which is indistinguishable from the feature being broken. That is how a real
    // model defect stayed hidden until the probe was instrumented by hand.

    @Test
    fun an_insulin_response_in_the_wrong_direction_is_reported_not_hidden() = runTest {
        val est = probeOf(FakeForecastPort(mgdlPerU = -15.0, mgdlPerG = 3.0)).probe(now, config)!!
        assertEquals("a model that RAISES BG on insulin says so", -15.0, est.isfMgdlPerU, 1e-9)
        assertEquals(-5.0, est.icrGPerU, 1e-9)
    }

    @Test
    fun a_carb_response_in_the_wrong_direction_is_reported_not_hidden() = runTest {
        val est = probeOf(FakeForecastPort(mgdlPerU = 15.0, mgdlPerG = -3.0)).probe(now, config)!!
        assertEquals(15.0, est.isfMgdlPerU, 1e-9)
        assertEquals("a negative carb response inverts the ratio", -5.0, est.icrGPerU, 1e-9)
    }

    @Test
    fun a_tiny_response_is_reported_not_hidden() = runTest {
        val flat = probeOf(FakeForecastPort(mgdlPerU = 0.5, mgdlPerG = 3.0)).probe(now, config)!!
        assertEquals(0.5, flat.isfMgdlPerU, 1e-9)

        // A barely-responsive carb roll divides to a large ratio. Reported, not filtered.
        val wide = probeOf(FakeForecastPort(mgdlPerU = 15.0, mgdlPerG = 0.05)).probe(now, config)!!
        assertEquals(300.0, wide.icrGPerU, 1e-6)
    }

    @Test
    fun a_zero_carb_response_yields_no_figure_at_all() = runTest {
        // The one arithmetic that produces no number rather than a bad one: ISF/0 is an infinity,
        // which is an absence of a figure, not a figure. The panel renders this as N/A.
        assertNull(probeOf(FakeForecastPort(mgdlPerU = 15.0, mgdlPerG = 0.0)).probe(now, config))
    }

    @Test
    fun an_empty_validated_window_withholds_the_estimate() = runTest {
        val flat = config.copy(horizon = HorizonPolicy(predictionHorizonHours = 0.0))
        assertNull(probeOf(FakeForecastPort()).probe(now, flat))
    }

    /**
     * The regression that matters most. `announced` and `candidate` are NOT interchangeable: the
     * production [RollingForecaster] re-anchors only the candidate onto the prediction zone's first
     * bucket. A meal passed as `announced` therefore lands at a different instant than the dose it is
     * ratioed against — biasing ICR, and dropping the leading Ra bucket outright once the anchor
     * ages. Both counterfactuals must ride `candidate`.
     */
    @Test
    fun both_counterfactuals_ride_candidate_so_the_forecaster_re_anchors_them_alike() = runTest {
        val seen = mutableListOf<ForecastRequest>()
        val inner = FakeForecastPort()
        val port = object : ForecastPort {
            override suspend fun roll(request: ForecastRequest): PredFan {
                seen.add(request)
                return inner.roll(request)
            }
        }
        probeOf(port).probe(now, config)

        assertEquals(3, seen.size)
        assertTrue("no roll may use announced", seen.all { it.announced.isEmpty() })
        assertNull("baseline injects nothing", seen[0].candidate)
        assertEquals(
            "the insulin probe is an INSULIN candidate",
            listOf(CurveKind.INSULIN),
            seen[1].candidate!!.map { it.kind },
        )
        assertEquals(
            "the carb probe is a CARB candidate, not an announced meal",
            listOf(CurveKind.CARB),
            seen[2].candidate!!.map { it.kind },
        )
        assertEquals("a meal contributes no candidate insulin", 0.0, seen[2].candidateU, 0.0)
    }

    // ── the anchor gate (§3.6-D) ──────────────────────────────────────────────────────────────────
    // The rolls cannot catch any of these: the production port never reports STALE by its own
    // documented contract, so a carried-forward anchor yields a perfectly ELIGIBLE fan describing a
    // BG from some time ago.

    @Test
    fun no_anchor_at_all_withholds_the_estimate() = runTest {
        assertNull(probeOf(FakeForecastPort(), anchor = null).probe(now, config))
    }

    @Test
    fun an_anchor_with_no_measured_reading_withholds_the_estimate() = runTest {
        val anchor = fakeAnchor(now, hasMeasured = false)
        assertNull(probeOf(FakeForecastPort(), anchor).probe(now, config))
    }

    @Test
    fun a_warm_up_anchor_withholds_the_estimate() = runTest {
        assertNull(probeOf(FakeForecastPort(), fakeAnchor(now, warmup = true)).probe(now, config))
    }

    @Test
    fun a_stale_anchor_withholds_the_estimate() = runTest {
        val limitMin = config.freshnessMaxAgeMs / 60_000L
        assertNull(
            "past the freshness limit",
            probeOf(FakeForecastPort(), fakeAnchor(now, ageMin = limitMin + 1)).probe(now, config),
        )
        // The boundary itself still stands: the gate is "older than", not "at least".
        assertNotNull(
            "at the limit is still current",
            probeOf(FakeForecastPort(), fakeAnchor(now, ageMin = limitMin)).probe(now, config),
        )
    }

    @Test
    fun a_mostly_fabricated_anchor_withholds_the_estimate() = runTest {
        val fabricated = fakeAnchor(now, interpolatedFraction = config.maxInterpolatedFraction + 0.01)
        assertNull(probeOf(FakeForecastPort(), fabricated).probe(now, config))
    }

    @Test
    fun a_figure_is_reported_at_any_magnitude() = runTest {
        val wide = probeOf(FakeForecastPort(mgdlPerU = 40.0, mgdlPerG = 0.105)).probe(now, config)!!
        assertEquals(40.0, wide.isfMgdlPerU, 1e-9)
        assertEquals(381.0, wide.icrGPerU, 1.0)

        // 0.5 g/U, which the panel renders with a decimal rather than rounding it to the "0g/U" a
        // whole-gram format would print.
        val tiny = probeOf(FakeForecastPort(mgdlPerU = 5.0, mgdlPerG = 10.0)).probe(now, config)!!
        assertEquals(0.5, tiny.icrGPerU, 1e-9)

        val steep = probeOf(FakeForecastPort(mgdlPerU = 500.0, mgdlPerG = 50.0)).probe(now, config)!!
        assertEquals(500.0, steep.isfMgdlPerU, 1e-9)
    }

    @Test
    fun the_smoothing_window_is_pinned_across_all_three_rolls() = runTest {
        val seen = mutableListOf<Int?>()
        val inner = FakeForecastPort()
        val port = object : ForecastPort {
            override suspend fun roll(request: ForecastRequest): PredFan {
                seen.add(request.smoothingWindow)
                return inner.roll(request)
            }
        }
        probeOf(port).probe(now, config, smoothingWindow = 11)
        assertEquals("every roll carries the pinned window", listOf<Int?>(11, 11, 11), seen)
    }
}
