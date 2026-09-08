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

/** FakeForecastPort is linear; by roll end it applies the whole mgdlPerU/mgdlPerG per unit. */
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
        modelIds: () -> String? = { MODEL },
    ) = SensitivityProbe(port, FakeBolusResolver(), FakeCarbResolver(), { modelIds() })

    @Test
    fun isf_and_icr_are_the_terminal_median_displacements() = runTest {
        val port = FakeForecastPort(startBg = 180.0, mgdlPerU = 15.0, mgdlPerG = 3.0)
        val est = probeOf(port).probe(now, config)!!

        assertEquals("ISF is the per-unit drop", 15.0, est.isfMgdlPerU, 1e-9)
        // 10 g raises it by 30, so one unit covers 15/3 = 5 g.
        assertEquals("ICR is the grams one unit cancels", 5.0, est.icrGPerU, 1e-9)
        assertEquals("stamped at the probe instant", now, est.atMs)
        assertEquals("horizon is the validated window", 24L * STEP_MS, est.horizonMs)
        assertEquals("stamped with the artifact it describes", MODEL, est.modelId)
    }

    @Test
    fun no_selected_model_yields_no_figure() = runTest {
        assertNull(probeOf(FakeForecastPort(), modelIds = { null }).probe(now, config))
    }

    @Test
    fun a_selection_changed_mid_probe_yields_no_figure() = runTest {
        // Three rolls are comparable only if one artifact produced all of them.
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

        val wide = probeOf(FakeForecastPort(mgdlPerU = 15.0, mgdlPerG = 0.05)).probe(now, config)!!
        assertEquals(300.0, wide.icrGPerU, 1e-6)
    }

    @Test
    fun a_zero_carb_response_yields_no_figure_at_all() = runTest {
        // ISF/0 is an infinity, which is an absence of a figure, not a figure.
        assertNull(probeOf(FakeForecastPort(mgdlPerU = 15.0, mgdlPerG = 0.0)).probe(now, config))
    }

    @Test
    fun an_empty_validated_window_withholds_the_estimate() = runTest {
        val flat = config.copy(horizon = HorizonPolicy(predictionHorizonHours = 0.0))
        assertNull(probeOf(FakeForecastPort()).probe(now, flat))
    }

    /** RollingForecaster re-anchors only candidate; announced meal lands at a different instant. */
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

    @Test
    fun a_figure_is_reported_at_any_magnitude() = runTest {
        val wide = probeOf(FakeForecastPort(mgdlPerU = 40.0, mgdlPerG = 0.105)).probe(now, config)!!
        assertEquals(40.0, wide.isfMgdlPerU, 1e-9)
        assertEquals(381.0, wide.icrGPerU, 1.0)

        // 0.5 g/U — the panel prints a decimal, not the "0g/U" a whole-gram format would give.
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
