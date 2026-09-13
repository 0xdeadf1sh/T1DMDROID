package com.t1dm.ui.graph

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SessionScrubTest {

    private val STEP = 300_000L
    private val T0 = 1_700_000_000_000L

    // Plot box, in px.
    private val PLOT_LEFT = 40f
    private val PLOT_RIGHT = 1000f

    private fun reading(ts: Long, bg: Int) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 5, quality = 100, provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL, tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

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
        val frame = buildGraphFrame((0 until 6).map { reading(T0 + it * STEP, 140) })
        for (offset in listOf(-4L * STEP, 0L, 3 * STEP, 40 * STEP)) {
            val rows = sessionScrubRows(frame, T0 + offset, STEP, UnitSpace.MgDl, 0)
            assertEquals(listOf("Local", "BG"), rows.map { it.first })
            // The clock is the one row that cannot be absent.
            assertNotNull(rows[0].second)
        }
    }

    @Test fun theBgIsBoundedToHalfASlotRatherThanClampedToTheLastReading() {
        // nearestIndex clamps to the ends, so past the newest reading it answers with that reading.
        val frame = buildGraphFrame((0 until 6).map { reading(T0 + it * STEP, 140) })
        val last = T0 + 5 * STEP
        assertEquals("140", sessionScrubRows(frame, last, STEP, UnitSpace.MgDl, 0)[1].second)
        assertEquals("140", sessionScrubRows(frame, last + STEP / 2, STEP, UnitSpace.MgDl, 0)[1].second)
        assertNull(sessionScrubRows(frame, last + STEP, STEP, UnitSpace.MgDl, 0)[1].second)
        assertNull(sessionScrubRows(frame, last + 24 * STEP, STEP, UnitSpace.MgDl, 0)[1].second)
        assertNull(sessionScrubRows(frame, T0 - STEP, STEP, UnitSpace.MgDl, 0)[1].second)
    }

    @Test fun anEmptyRecordAnswersTheClockAndNothingElse() {
        val rows = sessionScrubRows(GraphFrame.EMPTY, T0, STEP, UnitSpace.MgDl, 0)
        assertNotNull(rows[0].second)
        assertNull(rows[1].second)
    }
}
