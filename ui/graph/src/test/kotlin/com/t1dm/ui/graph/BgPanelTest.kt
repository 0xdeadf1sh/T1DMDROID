package com.t1dm.ui.graph

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.RolledForecast
import com.t1dm.core.model.UnitSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BgPanelTest {

    private val STEP = 300_000L

    @Test fun curveOverlayIndexAtRejectsThePreGridWindow() {
        // Integer division truncates toward zero; the 5min pre-grid used to resolve to bucket 0.
        val g = 1_700_000_000_000L / STEP * STEP
        val f = buildCurveOverlay(doubleArrayOf(9.0, 1.0, 2.0), doubleArrayOf(0.5, 0.1, 0.2), g, STEP)
        assertEquals(-1, f.indexAt(g - 1))
        assertEquals(-1, f.indexAt(g - STEP))
        assertEquals(0f, f.carbAt(g - 1), 0f)
        assertEquals(0f, f.insulinAt(g - 1), 0f)
        assertEquals(0, f.indexAt(g))
        assertEquals(9f, f.carbAt(g), 1e-6f)
        assertEquals(2, f.indexAt(g + 2 * STEP))
        assertEquals(-1, f.indexAt(g + 3 * STEP))
    }

    @Test fun fixedRange_alwaysCoversConfiguredWindow() {
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
        // First crossing below 70 is step index 2 ⇒ ts = anchor + 3·STEP; ETA = 15 min.
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
        assertEquals(0f, frame.carbAt(grid - STEP), 1e-6f)
        assertEquals(0f, frame.insulinAt(grid + 99 * STEP), 1e-6f)
    }

    @Test fun basalSeries_carriedWithIndependentScale() {
        val grid = 1_700_000_000_000L
        // A 24 h basal spreads its dose ~1/300 of a bolus gamma peak: basal ≈ 0.01, bolus ≈ 3.
        val basal = DoubleArray(8) { 0.01 }
        val bolus = doubleArrayOf(0.0, 0.0, 1.5, 3.0, 1.0, 0.0, 0.0, 0.0)
        // The model channel is basal + bolus SUMMED (model-io-curves.md).
        val combined = DoubleArray(8) { bolus[it] + basal[it] }
        val frame = buildCurveOverlay(carb = DoubleArray(8), insulin = combined, gridStartMs = grid, stepMs = STEP, basal = basal)
        assertEquals(3.01f, frame.insulinMax, 1e-4f)
        assertEquals(0.01f, frame.basalMax, 1e-6f)
        assertEquals(0.01f, frame.basalAt(grid + 5 * STEP), 1e-6f)
        val noBasal = buildCurveOverlay(carb = DoubleArray(8), insulin = combined, gridStartMs = grid, stepMs = STEP)
        assertEquals(0f, noBasal.basalMax, 1e-6f)
    }

    private fun overlay(insulinPerStep: DoubleArray, gridStart: Long, carbPerStep: DoubleArray = DoubleArray(insulinPerStep.size)) =
        buildCurveOverlay(carb = carbPerStep, insulin = insulinPerStep, gridStartMs = gridStart, stepMs = STEP)

    @Test fun noFutureInsulin_true_whenNonEmptyOverlayHasAllZeroFutureInsulin() {
        val now = 1_700_000_000_000L
        val insulin = DoubleArray(48) { 0.0 }
        val carb = DoubleArray(48) { if (it == 4) 6.0 else 0.0 }
        val f = overlay(insulin, now, carb)
        assertFalse("carb makes it non-empty", f.isEmpty)
        assertTrue(noFutureInsulinOverForecast(f, emptyList(), now))
    }

    @Test fun noFutureInsulin_true_whenReconstructableChannelIsGenuinelyAllZero() {
        val now = 1_700_000_000_000L
        val f = overlay(DoubleArray(48) { 0.0 }, now)
        assertTrue("flat-zero channel still counts as empty for rendering", f.isEmpty)
        assertTrue("but the advisory keys on size, not isEmpty", noFutureInsulinOverForecast(f, emptyList(), now))
    }

    @Test fun noFutureInsulin_false_whenBolusTailCoversHorizon() {
        val now = 1_700_000_000_000L
        // A bolus action tail at buckets 10..16, ~1 h ahead and inside the 3 h default horizon.
        val insulin = DoubleArray(48) { if (it in 10..16) 0.30 else 0.0 }
        assertFalse(noFutureInsulinOverForecast(overlay(insulin, now), emptyList(), now))
    }

    @Test fun noFutureInsulin_false_whenBasalScheduleCoversHorizon() {
        val now = 1_700_000_000_000L
        // An auto-extended basal, summed in: thin but non-zero everywhere.
        val insulin = DoubleArray(48) { 0.01 }
        assertFalse(noFutureInsulinOverForecast(overlay(insulin, now), emptyList(), now))
    }

    @Test fun noFutureInsulin_true_whenBolusTailExpiredInThePast() {
        // A short-DIA bolus whose action tail expires BEFORE `now`.
        val now = 1_700_000_000_000L
        val gridStart = now - 20 * STEP // 20 past buckets, then `now`, then future
        val insulin = DoubleArray(40) { if (it in 2..8) 0.4 else 0.0 } // action only in the past
        assertTrue(noFutureInsulinOverForecast(overlay(insulin, gridStart), emptyList(), now))
    }

    @Test fun noFutureInsulin_true_whenInsulinLandsPastTheHorizonEnd() {
        val now = 1_700_000_000_000L
        // Bucket 40 is past the 36-bucket (3 h) horizon, so nothing covers the window.
        val insulin = DoubleArray(48) { if (it == 40) 0.5 else 0.0 }
        assertTrue(noFutureInsulinOverForecast(overlay(insulin, now), emptyList(), now))
    }

    @Test fun noFutureInsulin_forecastEndExtendsTheHorizon() {
        val now = 1_700_000_000_000L
        // Bucket 40 is past the 3h default; a forecast reaching bucket 44 extends the horizon.
        val insulin = DoubleArray(48) { if (it == 40) 0.5 else 0.0 }
        val fc = pred(medians = List(44) { 110.0 }, anchor = now) // horizon end = now + 44·STEP
        assertFalse(noFutureInsulinOverForecast(overlay(insulin, now), listOf(fc), now))
    }

    @Test fun noFutureInsulin_false_whenOverlayHasNoBuckets() {
        // No buckets at all: nothing to reason about, so no warning.
        assertFalse(noFutureInsulinOverForecast(CurveOverlayFrame.EMPTY, emptyList(), 1_700_000_000_000L))
    }

    private fun reading(ts: Long, bg: Int, flag: ReadingFlag = ReadingFlag.NORMAL) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 5, quality = 100, provenance = ReadingProvenance.MEASURED, flag = flag,
        tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

    @Test fun smoothedTrace_alignsAppliesSmootherAndConvertsUnit() {
        val t0 = 1_700_000_000_000L
        val readings = listOf(reading(t0, 100), reading(t0 + STEP, 120), reading(t0 + 2 * STEP, 140))
        // +5 mg/dL, so the alignment and the mg/dL source are observable.
        val trace = buildSmoothedTrace(readings, UnitSpace.MgDl, smoothMgdl = { it.map { v -> v + 5.0 }.toDoubleArray() })
        assertEquals(3, trace.size)
        assertEquals(t0 + STEP, trace.tsMs[1])
        assertEquals(125f, trace.ys[1], 1e-4f)                    // 120 smoothed(+5), stays mg/dL
        val mmol = buildSmoothedTrace(readings, UnitSpace.MmolL, smoothMgdl = { it.copyOf() })
        assertEquals((140f / 18.0182f), mmol.ys[2], 1e-4f)
    }

    @Test fun smoothedTrace_breaksOnDropout() {
        val t0 = 1_700_000_000_000L
        // A 45-min gap, past the 30-min default.
        val readings = listOf(reading(t0, 100), reading(t0 + 9 * STEP, 110), reading(t0 + 10 * STEP, 120))
        val trace = buildSmoothedTrace(readings, UnitSpace.MgDl, smoothMgdl = { it.copyOf() })
        assertTrue("gap after point 0", trace.breakAfter[0])
        assertFalse("contiguous after point 1", trace.breakAfter[1])
    }

    @Test fun smoothedTrace_offWindowDrawsTheRawSignal() {
        // "Off" (window 1) makes the smoother identity: overlay must LIE ON raw, not fail to EMPTY.
        val t0 = 1_700_000_000_000L
        val readings = listOf(reading(t0, 96), reading(t0 + STEP, 131), reading(t0 + 2 * STEP, 118))
        val trace = buildSmoothedTrace(readings, UnitSpace.MgDl, smoothMgdl = { it.copyOf() })
        assertEquals(3, trace.size)
        assertEquals(96f, trace.ys[0], 1e-4f)
        assertEquals(131f, trace.ys[1], 1e-4f)
        assertEquals(118f, trace.ys[2], 1e-4f)
    }

    @Test fun smoothedTrace_lengthMismatchFailsClosed() {
        val t0 = 1_700_000_000_000L
        val readings = listOf(reading(t0, 100), reading(t0 + STEP, 120))
        // A length change yields EMPTY rather than a misaligned draw.
        val trace = buildSmoothedTrace(readings, UnitSpace.MgDl, smoothMgdl = { doubleArrayOf(1.0) })
        assertTrue(trace.isEmpty)
    }

    private fun rolled(
        medians: DoubleArray,
        anchor: Long = 1_700_000_000_000L,
        validatedSteps: Int = 24,
        degenerate: Boolean = false,
        requestedHours: Double = 6.0,
    ) = RolledForecast(
        anchorTsMs = anchor, stepMs = STEP,
        medianBg = medians,
        lowerBg = DoubleArray(medians.size) { medians[it] - 5 },
        upperBg = DoubleArray(medians.size) { medians[it] + 5 },
        validatedSteps = validatedSteps, requestedHours = requestedHours,
        eligible = !degenerate, degenerate = degenerate, reason = null,
        completedRolls = medians.size / 24, requestedRolls = 3,
    )

    @Test fun rolledSeries_buildsUnitConvertedFanWithTimestamps() {
        val anchor = 1_700_000_000_000L
        val rf = rolled(DoubleArray(48) { 120.0 }, anchor = anchor)
        val s = buildRolledSeries(rf, UnitSpace.MgDl, null)!!
        assertEquals(48, s.size)
        // Step i is at anchor + (i+1)·STEP — the same convention as the cycle forecast.
        assertEquals(anchor + STEP, s.tsMs[0])
        assertEquals(anchor + 48 * STEP, s.tsMs[47])
        assertEquals(120f, s.median[0], 1e-4f)
        val mmol = buildRolledSeries(rf, UnitSpace.MmolL, null)!!
        assertEquals(120f / 18.0182f, mmol.median[0], 1e-4f)
    }

    @Test fun rolledSeries_splitsValidatedFromExtrapolated() {
        // 48 steps = 4 h; the first 24 are validated.
        val s = buildRolledSeries(rolled(DoubleArray(48) { 120.0 }), UnitSpace.MgDl, null)!!
        assertEquals(24, s.validatedSteps)
        assertEquals(24, s.extrapolatedSteps)
    }

    /** Decides ink and whether other fans keep §8.4 correction; every reaching case is pinned. */
    @Test fun rolledSeries_paintsBandOnlyWhenTheTailIsLongEnoughAndSound() {
        val twoHours = buildRolledSeries(rolled(DoubleArray(24) { 120.0 }, requestedHours = 2.0), UnitSpace.MgDl, null)!!
        assertFalse("a roll at the validated horizon paints no band", twoHours.paintsBand())
        for (steps in intArrayOf(6, 12, 18, 24)) {
            val short = buildRolledSeries(
                rolled(DoubleArray(steps) { 120.0 }, validatedSteps = steps, requestedHours = steps / 12.0),
                UnitSpace.MgDl, null,
            )!!
            assertFalse("a ${steps / 12.0} h roll paints no band", short.paintsBand())
        }
        val justOver = buildRolledSeries(rolled(DoubleArray(25) { 120.0 }), UnitSpace.MgDl, null)!!
        assertTrue("a tail of two steps opens the band", justOver.paintsBand())
        val fourHours = buildRolledSeries(rolled(DoubleArray(48) { 120.0 }), UnitSpace.MgDl, null)!!
        assertTrue("a 4 h roll paints a band", fourHours.paintsBand())
        val degenerate = buildRolledSeries(
            rolled(DoubleArray(48) { 120.0 }, degenerate = true), UnitSpace.MgDl, null,
        )!!
        assertFalse("a degenerate roll paints no band at any length", degenerate.paintsBand())
    }

    @Test fun rolledSeries_emptyRollDrawsNothing() {
        assertNull(buildRolledSeries(RolledForecast.NONE, UnitSpace.MgDl, null))
        assertNull(buildRolledSeries(null, UnitSpace.MgDl, null))
    }

    /** Safety pin: excursionsOf takes only List<ModelPrediction>; RolledForecast can't alert. */
    @Test fun rolledForecast_cannotReachTheAlertingPath() {
        val anchor = 1_700_000_000_000L
        // A roll whose tail collapses to 20 mg/dL far past the validated 2 h.
        val medians = DoubleArray(48) { if (it >= 30) 20.0 else 120.0 }
        val rf = rolled(medians, anchor = anchor)
        val series = buildRolledSeries(rf, UnitSpace.MgDl, null)!!
        assertTrue("extrapolated tail is drawn", series.extrapolatedSteps > 0)
        assertTrue("tail reaches hypo", series.median.any { it <= 20f })
        assertTrue(excursionsOf(emptyList(), lowMgdl = 70, highMgdl = 180, nowMs = anchor).isEmpty())
    }

    /** 2026-01-07T00:00Z — a Wednesday. */
    private val WED_JAN_7 = 1_767_744_000_000L

    @Test fun axisDate_readsAsMonthOrdinalWeekday() {
        assertEquals("January 7th, Wednesday", formatAxisDate(WED_JAN_7, 0))
    }

    @Test fun axisDate_namesTheLocalDay_notTheUtcOne() {
        // 23:00 UTC on the 6th is already the 7th an hour east.
        val late = WED_JAN_7 - 3_600_000L
        assertEquals("January 7th, Wednesday", formatAxisDate(late, 60))
        assertEquals("January 6th, Tuesday", formatAxisDate(late, 0))
    }

    @Test fun axisDate_ordinalSuffixes() {
        val jan1 = 1_767_225_600_000L // 2026-01-01T00:00Z
        val expected = mapOf(
            1 to "1st", 2 to "2nd", 3 to "3rd", 4 to "4th", 10 to "10th",
            11 to "11th", 12 to "12th", 13 to "13th", 20 to "20th",
            21 to "21st", 22 to "22nd", 23 to "23rd", 31 to "31st",
        )
        for ((day, token) in expected) {
            val s = formatAxisDate(jan1 + (day - 1) * 86_400_000L, 0)
            assertTrue("day $day rendered as \"$s\"", s.startsWith("January $token, "))
        }
    }
}
