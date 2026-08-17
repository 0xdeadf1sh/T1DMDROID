package com.t1dm.ui.graph

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The exercise review's scrub: the cursor's quantisation, the forecast read-out behind it, and every
 * place it must refuse rather than answer.
 *
 * Nothing here is a layout claim — the Canvas is not testable on the host. What is testable is the
 * arithmetic the sub-panel would otherwise get silently wrong: reading a cycle's median at a wall
 * clock instant when its anchor sits behind its issue instant, the two bounds (past the horizon, and
 * past the newest reading) that stop the read-out asserting a number nothing produced, and the
 * ineligible cycles it must not quote at all — the chart can dash one, a table cannot.
 *
 * Fixture geometry mirrors [HindsightFrameTest]: cycle `c` at `T0 + c·STEP` forecasts a flat line at
 * `200 + c`, so a value identifies its cycle and a stride error lands on a different number.
 */
class SessionScrubTest {

    private val STEP = 300_000L
    private val T0 = 1_700_000_000_000L
    private val NQ = 7
    private val H = 24

    // A plot box, in the px the Canvas hands the cursor's own transform.
    private val PLOT_LEFT = 40f
    private val PLOT_RIGHT = 1000f

    private fun pred(
        c: Int,
        anchorC: Int = c,
        steps: Int = H,
        status: ForecastStatus = ForecastStatus.OK,
        stale: Boolean = false,
        median: Double? = null,
    ): ModelPrediction {
        val level = 200.0 + c
        val bands = ArrayList<Double>(steps * NQ)
        repeat(steps) { for (k in 0 until NQ) bands += level + (k - NQ / 2) * 5.0 }
        return ModelPrediction(
            modelId = "m", cycleTsMs = T0 + c * STEP, anchorTsMs = T0 + anchorC * STEP, stepMs = STEP,
            medianBg = List(steps) { median ?: level }, bandsMgdl = bands, nQuantiles = NQ,
            lastBg = level,
            status = status, backend = BackendId.EXECUTORCH_XNNPACK_FP32,
            precision = Precision.FP32, selected = true, stale = stale, latencyMs = null,
        )
    }

    private fun frameOf(rows: List<ModelPrediction>) =
        runBlocking { hindsightFrameOf(rows, UnitSpace.MgDl, null) }!!

    private fun reading(ts: Long, bg: Int) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 5, quality = 100, provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL, tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

    // ── medianAt ────────────────────────────────────────────────────────────────────────────────

    @Test fun medianAtWalksStepsFromTheAnchorNotTheIssueInstant() {
        // Cycle 6 was issued at +9 off the reading at +3 — a CGM dropout. Its step 0 sits at +3, so the
        // value an hour after the ISSUE instant is step 6, not step 12. Keyed on the issue instant the
        // read-out would quote a step that is half an hour further into the forecast than it says.
        val f = frameOf((0 until 6).map { pred(it) } + listOf(pred(9, anchorC = 3)))
        val c = f.cycleAt((T0 + 9 * STEP).toDouble())
        assertEquals(6, c)
        assertEquals(209f, f.medianAt(c, T0 + 3 * STEP)!!, 1e-3f)
        assertEquals(209f, f.medianAt(c, T0 + 9 * STEP)!!, 1e-3f)
        // The last step the block holds is the anchor plus the whole horizon.
        assertNotNull(f.medianAt(c, T0 + (3 + H) * STEP))
    }

    @Test fun medianAtRefusesOutsideTheHorizon() {
        val f = frameOf((0 until 3).map { pred(it) })
        assertNull(f.medianAt(1, T0 + (1 + H + 1) * STEP))
        assertNull(f.medianAt(1, T0 - STEP))
        // A cycle index the frame does not hold is a refusal too, not an out-of-bounds read.
        assertNull(f.medianAt(-1, T0 + STEP))
        assertNull(f.medianAt(3, T0 + STEP))
    }

    @Test fun medianAtTakesTheNearestStep() {
        val f = frameOf(listOf(pred(0)))
        // Two fifths of a step past step 2 still reads step 2; three fifths reads step 3. Both hold a
        // flat 200 here, so the assertion that matters is that neither is a refusal.
        assertNotNull(f.medianAt(0, T0 + 2 * STEP + STEP * 2 / 5))
        assertNotNull(f.medianAt(0, T0 + 2 * STEP + STEP * 3 / 5))
    }

    // ── the cursor ──────────────────────────────────────────────────────────────────────────────

    @Test fun theCursorLandsOnGridMultiples() {
        val start = T0 - 1_800_000L
        val span = 3 * 3_600_000L
        for (i in 0..40) {
            val cursor = scrubCursorOf(start, span, i / 40f, STEP)
            assertEquals("fraction ${i / 40f}", 0L, cursor % STEP)
        }
    }

    @Test fun theCursorSpansTheWindowAndClampsOutsideIt() {
        // A bout starts when the user says so, so the window's own ends are wall-clock instants off
        // the grid. Both travel limits therefore land on the grid line NEAREST the end, not on the end.
        val start = T0
        val span = 2 * 3_600_000L
        val lo = scrubCursorOf(start, span, 0f, STEP)
        val hi = scrubCursorOf(start, span, 1f, STEP)
        assertTrue(abs(lo - start) <= STEP / 2)
        assertTrue(abs(hi - (start + span)) <= STEP / 2)
        // A fraction outside 0..1 is a clamp, not an extrapolation off the end of the slider.
        assertEquals(lo, scrubCursorOf(start, span, -0.5f, STEP))
        assertEquals(hi, scrubCursorOf(start, span, 2f, STEP))
        // Monotone across the travel.
        var last = Long.MIN_VALUE
        for (i in 0..20) {
            val c = scrubCursorOf(start, span, i / 20f, STEP)
            assertTrue(c >= last)
            last = c
        }
    }

    @Test fun theMarkerIsDrawnEverywhereTheSliderCanReach() {
        // The read-out answers the clock at every position of the travel, so the marker has to be
        // somewhere for every one of them. Swept across a whole slot of window-start residuals: the
        // residual is what decides which end the snap overshoots, and it is uniform over bouts.
        //
        // Inside the window the marker is the TRACE's own transform, untouched — the box is a limit on
        // where it may be drawn, not a rescaling of the axis under it.
        val span = 2 * 3_600_000L + 1_800_000L
        val ppm = (PLOT_RIGHT - PLOT_LEFT) / span.toDouble()
        for (residual in 0 until 20) {
            val start = T0 + residual * 15_000L
            var last = Float.NEGATIVE_INFINITY
            for (i in 0..40) {
                val cursor = scrubCursorOf(start, span, i / 40f, STEP)
                val x = scrubCursorPx(cursor, start.toDouble(), ppm, PLOT_LEFT, PLOT_RIGHT)
                assertTrue("residual $residual at ${i / 40f} drew at $x", x >= PLOT_LEFT && x <= PLOT_RIGHT)
                assertTrue("residual $residual at ${i / 40f} went backwards", x >= last)
                last = x
                if (cursor in start..(start + span)) {
                    assertEquals((PLOT_LEFT + (cursor - start) * ppm).toFloat(), x, 1e-3f)
                }
            }
        }
    }

    @Test fun aCursorPastEitherEndPinsToThatEdge() {
        val span = 2 * 3_600_000L + 1_800_000L
        val ppm = (PLOT_RIGHT - PLOT_LEFT) / span.toDouble()
        // T0 sits 200 s past a grid line, so the window's END snaps forward, past the last pixel.
        val hi = scrubCursorOf(T0, span, 1f, STEP)
        assertTrue(hi > T0 + span)
        assertEquals(PLOT_RIGHT, scrubCursorPx(hi, T0.toDouble(), ppm, PLOT_LEFT, PLOT_RIGHT), 1e-3f)
        // A hundred seconds earlier the residual is under half a slot, so the START snaps backward.
        val start = T0 - 100_000L
        val lo = scrubCursorOf(start, span, 0f, STEP)
        assertTrue(lo < start)
        assertEquals(PLOT_LEFT, scrubCursorPx(lo, start.toDouble(), ppm, PLOT_LEFT, PLOT_RIGHT), 1e-3f)
    }

    // ── the read-out ────────────────────────────────────────────────────────────────────────────

    @Test fun theReadOutKeepsItsShapeWhereverTheCursorIs() {
        val f = frameOf((0 until 6).map { pred(it) })
        val frame = buildGraphFrame((0 until 6).map { reading(T0 + it * STEP, 140) })
        val labels = listOf("Local", "BG", "+30 min", "+60 min")
        for (offset in listOf(-4L * STEP, 0L, 3 * STEP, 40 * STEP)) {
            val rows = sessionScrubRows(frame, f, T0 + offset, STEP, UnitSpace.MgDl, 0)
            assertEquals(labels, rows.map { it.first })
            // The clock always answers; it is the one row that cannot be absent.
            assertNotNull(rows[0].second)
        }
    }

    @Test fun theReadOutQuotesTheForecastIssuedAtTheCursor() {
        val f = frameOf((0 until 6).map { pred(it) })
        val frame = buildGraphFrame((0 until 6).map { reading(T0 + it * STEP, 140) })
        val rows = sessionScrubRows(frame, f, T0 + 3 * STEP, STEP, UnitSpace.MgDl, 0)
        assertEquals("140", rows[1].second)
        // Cycle 3 forecasts a flat 203; both horizons are inside its 2 h reach.
        assertEquals("203", rows[2].second)
        assertEquals("203", rows[3].second)
    }

    @Test fun aCursorInAHoleQuotesNoForecastAtAll() {
        // Inference did not run for an hour. Widening the catchment or falling back to the nearest fan
        // would pin the last forecast before the hole under the thumb, indistinguishable from one
        // actually issued there.
        val f = frameOf(listOf(pred(0), pred(1), pred(14), pred(15)))
        assertEquals(-1, f.cycleAt((T0 + 7 * STEP).toDouble()))
        val frame = buildGraphFrame((0 until 16).map { reading(T0 + it * STEP, 140) })
        val rows = sessionScrubRows(frame, f, T0 + 7 * STEP, STEP, UnitSpace.MgDl, 0)
        assertNull(rows[2].second)
        assertNull(rows[3].second)
        // The measurement beside it is unaffected: the CGM ran through the hole even though the model
        // did not, and blanking both would misreport why the fan is missing.
        assertEquals("140", rows[1].second)
    }

    @Test fun theBgIsBoundedToHalfASlotRatherThanClampedToTheLastReading() {
        // `nearestIndex` clamps to the ends of the series, so a cursor two hours past the newest
        // reading answers with that reading. Printed unqualified it is a measurement asserted at an
        // instant nothing was measured at — and the review's window deliberately runs on past the bout.
        val frame = buildGraphFrame((0 until 6).map { reading(T0 + it * STEP, 140) })
        val last = T0 + 5 * STEP
        assertEquals("140", sessionScrubRows(frame, null, last, STEP, UnitSpace.MgDl, 0)[1].second)
        assertEquals("140", sessionScrubRows(frame, null, last + STEP / 2, STEP, UnitSpace.MgDl, 0)[1].second)
        assertNull(sessionScrubRows(frame, null, last + STEP, STEP, UnitSpace.MgDl, 0)[1].second)
        assertNull(sessionScrubRows(frame, null, last + 24 * STEP, STEP, UnitSpace.MgDl, 0)[1].second)
        // And before the record begins, for the same reason.
        assertNull(sessionScrubRows(frame, null, T0 - STEP, STEP, UnitSpace.MgDl, 0)[1].second)
    }

    // ── the two refusals a number cannot carry a dash for ───────────────────────────────────────

    @Test fun aDegenerateCycleQuotesNoForecast() {
        // The chart withholds this cycle's fan and dashes its median; the table has neither, so it
        // must withhold the number. Rail-pinned is finite — printed flatly it would read as a
        // forecast the app had actually stood behind.
        val f = frameOf((0 until 3).map { pred(it) } + pred(3, status = ForecastStatus.RAIL_PINNED))
        val c = f.cycleAt((T0 + 3 * STEP).toDouble())
        assertEquals(3, c)
        assertTrue(f.degenerateAt(c))
        assertFalse(f.eligible(c))
        assertNull(f.medianAt(c, T0 + 4 * STEP))
        val frame = buildGraphFrame((0 until 6).map { reading(T0 + it * STEP, 140) })
        val rows = sessionScrubRows(frame, f, T0 + 3 * STEP, STEP, UnitSpace.MgDl, 0)
        assertNull(rows[2].second)
        assertNull(rows[3].second)
        // The measurement is untouched: the CGM was fine, the forecast was not.
        assertEquals("140", rows[1].second)
    }

    @Test fun aStaleCycleQuotesNoForecast() {
        // §3.6-D: issued off an anchor already past the freshness gate, so it was never eligible to
        // drive anything and must not be re-asserted as a number in hindsight either.
        val f = frameOf((0 until 3).map { pred(it) } + pred(3, stale = true))
        val c = f.cycleAt((T0 + 3 * STEP).toDouble())
        assertTrue(f.staleAt(c))
        assertFalse(f.eligible(c))
        assertNull(f.medianAt(c, T0 + 4 * STEP))
    }

    @Test fun aNonFiniteMedianIsRefusedRatherThanRoundedToZero() {
        // `formatValue(NaN, MgDl)` is `Math.round(NaN).toString()` = "0", so a NaN reaching the table
        // asserts a forecast of 0 mg/dL. The store keeps non-finite rows — only the wire push drops
        // them — so the guard has to be here.
        val f = frameOf(listOf(pred(0, median = Double.NaN)))
        // Step 0 is the measured anchor and is finite; every forecast step past it is not.
        assertNotNull(f.medianAt(0, T0))
        assertNull(f.medianAt(0, T0 + STEP))
        assertNull(f.medianAt(0, T0 + 6 * STEP))
    }

    @Test fun anEmptyRecordAnswersTheClockAndNothingElse() {
        val rows = sessionScrubRows(GraphFrame.EMPTY, null, T0, STEP, UnitSpace.MgDl, 0)
        assertNotNull(rows[0].second)
        assertNull(rows[1].second)
        assertNull(rows[2].second)
        assertNull(rows[3].second)
    }
}
