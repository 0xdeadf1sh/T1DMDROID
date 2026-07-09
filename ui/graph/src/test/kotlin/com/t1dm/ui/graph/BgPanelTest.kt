package com.t1dm.ui.graph

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.UnitSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the pure BG-panel logic added in Phase 7A: the fixed Y-axis span (item 1), the
 * predicted-excursion detection (item 16), and the curve-overlay bucket sampler the scrub read-out
 * uses (item 3). The Canvas drawing itself is not unit-tested; these cover the numerics behind it.
 */
class BgPanelTest {

    private val STEP = 300_000L

    // ── item 1: fixed Y-axis span ────────────────────────────────────────────────────────────────

    @Test fun fixedRange_alwaysCoversConfiguredWindow() {
        // Data sits comfortably inside 20..250 → axis still spans at least the configured window.
        val (lo, hi) = fixedYRange(90f, 160f, UnitSpace.MgDl, 20, 250)
        assertTrue("floor covers 20", lo <= 20f)
        assertTrue("ceiling covers 250", hi >= 250f)
    }

    @Test fun fixedRange_growsAboveCeilingForHigh() {
        val (_, hi) = fixedYRange(90f, 360f, UnitSpace.MgDl, 20, 250)
        assertTrue("ceiling grew to fit the 360 reading", hi >= 360f)
    }

    @Test fun fixedRange_growsBelowFloorForLow() {
        val (lo, _) = fixedYRange(12f, 160f, UnitSpace.MgDl, 20, 250)
        assertTrue("floor dropped to fit the 12 reading", lo <= 12f)
    }

    @Test fun fixedRange_convertsToMmol() {
        val (lo, hi) = fixedYRange(5f, 9f, UnitSpace.MmolL, 20, 250)
        assertTrue("floor covers 20 mg/dL ≈ 1.1 mmol/L", lo <= 20f / 18.0182f)
        assertTrue("ceiling covers 250 mg/dL ≈ 13.9 mmol/L", hi >= 250f / 18.0182f)
    }

    // ── item 16: predicted excursions ────────────────────────────────────────────────────────────

    private fun pred(
        medians: List<Double>,
        selected: Boolean = true,
        status: ForecastStatus = ForecastStatus.OK,
        stale: Boolean = false,
        anchor: Long = 1_000_000_000_000L,
    ) = ModelPrediction(
        modelId = "m", cycleTsMs = anchor, anchorTsMs = anchor, stepMs = STEP,
        medianBg = medians, bandsMgdl = List(medians.size * 7) { 100.0 }, nQuantiles = 7,
        lastBg = medians.first(), status = status, backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        precision = Precision.FP32, selected = selected, stale = stale, latencyMs = null,
    )

    @Test fun excursions_flagsFirstHypoAndHyper() {
        val anchor = 1_000_000_000_000L
        val p = pred(listOf(120.0, 95.0, 65.0, 60.0), anchor = anchor)
        val out = excursionsOf(listOf(p), lowMgdl = 70, highMgdl = 180, nowMs = anchor)
        assertEquals(1, out.size)
        val hypo = out.single()
        assertFalse(hypo.hyper)
        // First crossing below 70 is step index 2 → ts = anchor + 3·STEP; ETA = 15 min.
        assertEquals(anchor + 3 * STEP, hypo.tsMs)
        assertEquals(15L, hypo.etaMin)
    }

    @Test fun excursions_reportsBothWhenMedianSwings() {
        val anchor = 2_000_000_000_000L
        val p = pred(listOf(200.0, 60.0), anchor = anchor)
        val out = excursionsOf(listOf(p), lowMgdl = 70, highMgdl = 180, nowMs = anchor)
        assertEquals(2, out.size)
        assertTrue(out.any { it.hyper })
        assertTrue(out.any { !it.hyper })
    }

    @Test fun excursions_suppressedForDegenerateForecast() {
        val p = pred(listOf(120.0, 50.0), status = ForecastStatus.COLLAPSED_BAND)
        assertTrue(excursionsOf(listOf(p), 70, 180).isEmpty())
    }

    @Test fun excursions_suppressedForStaleForecast() {
        val p = pred(listOf(120.0, 50.0), stale = true)
        assertTrue(excursionsOf(listOf(p), 70, 180).isEmpty())
    }

    @Test fun excursions_ignoresUnselectedModels() {
        val p = pred(listOf(120.0, 50.0), selected = false)
        assertTrue(excursionsOf(listOf(p), 70, 180).isEmpty())
    }

    // ── item 3: overlay bucket sampling ──────────────────────────────────────────────────────────

    @Test fun overlaySampler_returnsRatePerBucketAndZeroOutside() {
        val grid = 1_700_000_000_000L
        val frame = buildCurveOverlay(
            carb = doubleArrayOf(0.0, 2.0, 5.0, 0.0),
            insulin = doubleArrayOf(0.0, 0.0, 0.1, 0.3),
            gridStartMs = grid,
            stepMs = STEP,
        )
        assertEquals(2f, frame.carbAt(grid + STEP), 1e-6f)
        assertEquals(5f, frame.carbAt(grid + 2 * STEP), 1e-6f)
        assertEquals(0.3f, frame.insulinAt(grid + 3 * STEP), 1e-6f)
        assertEquals(0f, frame.carbAt(grid - STEP), 1e-6f)          // before the grid
        assertEquals(0f, frame.insulinAt(grid + 99 * STEP), 1e-6f)  // after the grid
    }
}
