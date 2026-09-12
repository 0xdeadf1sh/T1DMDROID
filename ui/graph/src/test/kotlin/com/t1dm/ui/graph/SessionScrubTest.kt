package com.t1dm.ui.graph

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
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

/** Fixture: cycle `c` sits at `T0 + c·STEP`, forecasts flat `200 + c` — a value names its cycle. */
class SessionScrubTest {

    private val STEP = 300_000L
    private val T0 = 1_700_000_000_000L
    private val NQ = 7
    private val H = 24

    // Plot box, in px.
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
            selected = true, stale = stale, latencyMs = null,
        )
    }

    private fun frameOf(rows: List<ModelPrediction>) =
        runBlocking { hindsightFrameOf(rows, UnitSpace.MgDl, null) }!!

    private fun reading(ts: Long, bg: Int) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 5, quality = 100, provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL, tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )


    @Test fun medianAtWalksStepsFromTheAnchorNotTheIssueInstant() {
        // Step 0 sits at the ANCHOR, not at the issue instant: here +3 for a cycle issued at +9.
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
        // An index the frame does not hold refuses rather than reading out of bounds.
        assertNull(f.medianAt(-1, T0 + STEP))
        assertNull(f.medianAt(3, T0 + STEP))
    }

    @Test fun medianAtTakesTheNearestStep() {
        val f = frameOf(listOf(pred(0)))
        // Flat 200 either side of the nearest-step boundary, so what matters is neither refuses.
        assertNotNull(f.medianAt(0, T0 + 2 * STEP + STEP * 2 / 5))
        assertNotNull(f.medianAt(0, T0 + 2 * STEP + STEP * 3 / 5))
    }


    @Test fun theCursorLandsOnGridMultiples() {
        val start = T0 - 1_800_000L
        val span = 3 * 3_600_000L
        for (i in 0..40) {
            val cursor = scrubCursorOf(start, span, i / 40f, STEP)
            assertEquals("fraction ${i / 40f}", 0L, cursor % STEP)
        }
    }

    @Test fun theCursorSpansTheWindowAndClampsOutsideIt() {
        // The window's ends are off-grid wall clock; a travel limit lands on the nearest grid line.
        val start = T0
        val span = 2 * 3_600_000L
        val lo = scrubCursorOf(start, span, 0f, STEP)
        val hi = scrubCursorOf(start, span, 1f, STEP)
        assertTrue(abs(lo - start) <= STEP / 2)
        assertTrue(abs(hi - (start + span)) <= STEP / 2)
        assertEquals(lo, scrubCursorOf(start, span, -0.5f, STEP))
        assertEquals(hi, scrubCursorOf(start, span, 2f, STEP))
        var last = Long.MIN_VALUE
        for (i in 0..20) {
            val c = scrubCursorOf(start, span, i / 20f, STEP)
            assertTrue(c >= last)
            last = c
        }
    }

    @Test fun theMarkerIsDrawnEverywhereTheSliderCanReach() {
        // Inside the window the marker is the trace's own transform; the box only limits drawing.
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
        // T0 sits 200 s past a grid line, so the window's end snaps forward past the last pixel.
        val hi = scrubCursorOf(T0, span, 1f, STEP)
        assertTrue(hi > T0 + span)
        assertEquals(PLOT_RIGHT, scrubCursorPx(hi, T0.toDouble(), ppm, PLOT_LEFT, PLOT_RIGHT), 1e-3f)
        // 100 s earlier the residual is under half a slot, so the start snaps backward.
        val start = T0 - 100_000L
        val lo = scrubCursorOf(start, span, 0f, STEP)
        assertTrue(lo < start)
        assertEquals(PLOT_LEFT, scrubCursorPx(lo, start.toDouble(), ppm, PLOT_LEFT, PLOT_RIGHT), 1e-3f)
    }


    @Test fun theReadOutKeepsItsShapeWhereverTheCursorIs() {
        val f = frameOf((0 until 6).map { pred(it) })
        val frame = buildGraphFrame((0 until 6).map { reading(T0 + it * STEP, 140) })
        val labels = listOf("Local", "BG", "+30 min", "+60 min")
        for (offset in listOf(-4L * STEP, 0L, 3 * STEP, 40 * STEP)) {
            val rows = sessionScrubRows(frame, f, T0 + offset, STEP, UnitSpace.MgDl, 0)
            assertEquals(labels, rows.map { it.first })
            // The clock is the one row that cannot be absent.
            assertNotNull(rows[0].second)
        }
    }

    @Test fun theReadOutQuotesTheForecastIssuedAtTheCursor() {
        val f = frameOf((0 until 6).map { pred(it) })
        val frame = buildGraphFrame((0 until 6).map { reading(T0 + it * STEP, 140) })
        val rows = sessionScrubRows(frame, f, T0 + 3 * STEP, STEP, UnitSpace.MgDl, 0)
        assertEquals("140", rows[1].second)
        // Both horizons are inside cycle 3's reach.
        assertEquals("203", rows[2].second)
        assertEquals("203", rows[3].second)
    }

    @Test fun aCursorInAHoleQuotesNoForecastAtAll() {
        // Falling back to the nearest fan would pin a forecast under a thumb it was not issued for.
        val f = frameOf(listOf(pred(0), pred(1), pred(14), pred(15)))
        assertEquals(-1, f.cycleAt((T0 + 7 * STEP).toDouble()))
        val frame = buildGraphFrame((0 until 16).map { reading(T0 + it * STEP, 140) })
        val rows = sessionScrubRows(frame, f, T0 + 7 * STEP, STEP, UnitSpace.MgDl, 0)
        assertNull(rows[2].second)
        assertNull(rows[3].second)
        // The CGM ran through the hole; blanking it too would misreport why the fan is missing.
        assertEquals("140", rows[1].second)
    }

    @Test fun theBgIsBoundedToHalfASlotRatherThanClampedToTheLastReading() {
        // nearestIndex clamps to the ends, so past the newest reading it answers with that reading.
        val frame = buildGraphFrame((0 until 6).map { reading(T0 + it * STEP, 140) })
        val last = T0 + 5 * STEP
        assertEquals("140", sessionScrubRows(frame, null, last, STEP, UnitSpace.MgDl, 0)[1].second)
        assertEquals("140", sessionScrubRows(frame, null, last + STEP / 2, STEP, UnitSpace.MgDl, 0)[1].second)
        assertNull(sessionScrubRows(frame, null, last + STEP, STEP, UnitSpace.MgDl, 0)[1].second)
        assertNull(sessionScrubRows(frame, null, last + 24 * STEP, STEP, UnitSpace.MgDl, 0)[1].second)
        assertNull(sessionScrubRows(frame, null, T0 - STEP, STEP, UnitSpace.MgDl, 0)[1].second)
    }


    @Test fun aDegenerateCycleQuotesNoForecast() {
        // Rail-pinned is finite: printed flatly it would read as a forecast the app stood behind.
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
        assertEquals("140", rows[1].second)
    }

    @Test fun aStaleCycleQuotesNoForecast() {
        // §3.6-D.
        val f = frameOf((0 until 3).map { pred(it) } + pred(3, stale = true))
        val c = f.cycleAt((T0 + 3 * STEP).toDouble())
        assertTrue(f.staleAt(c))
        assertFalse(f.eligible(c))
        assertNull(f.medianAt(c, T0 + 4 * STEP))
    }

    @Test fun aNonFiniteMedianIsRefusedRatherThanRoundedToZero() {
        // formatValue(NaN, MgDl) rounds to "0"; store keeps non-finite rows, so the guard is here.
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
