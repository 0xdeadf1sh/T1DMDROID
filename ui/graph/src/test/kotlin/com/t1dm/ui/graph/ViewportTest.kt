package com.t1dm.ui.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Switching sensors must not move the clock. */
class ViewportTest {

    private val hour = 3_600_000.0
    private val minSpan = 15 * 60_000.0
    private val maxSpan = 30 * 24 * hour

    /** The two wears the user actually had: one 18 days long, one four hours, ending together. */
    private val endMs = 1_000_000_000.0
    private val longRecordStart = endMs - 18 * 24 * hour
    private val shortRecordStart = endMs - 4 * hour

    @Test
    fun `a window inside both records survives the switch unmoved`() {
        val span = 3 * hour
        val start = endMs - 3.5 * hour
        val (onLong, _) = clampViewport(start, span, longRecordStart, endMs, minSpan, maxSpan)
        val (onShort, _) = clampViewport(onLong, span, shortRecordStart, endMs, minSpan, maxSpan)
        assertEquals(start, onLong, 0.0)
        assertEquals("the clock moved between two sensors", start, onShort, 0.0)
    }

    /** The regression: a record shorter than the window was centred, throwing the start away. */
    @Test
    fun `a record shorter than the window keeps the reader's own start`() {
        val span = 6 * hour
        val start = endMs - 5 * hour
        val (got, _) = clampViewport(start, span, shortRecordStart, endMs, minSpan, maxSpan)
        assertEquals(start, got, 0.0)
        assertTrue("the whole record must stay visible", got <= shortRecordStart)
        assertTrue(got + span >= endMs)
    }

    @Test
    fun `a start that would push the record off either edge is pulled back just far enough`() {
        val span = 6 * hour
        val tooLate = clampViewport(endMs, span, shortRecordStart, endMs, minSpan, maxSpan).first
        assertEquals("the record must not fall off the left", shortRecordStart, tooLate, 0.0)

        val tooEarly = clampViewport(endMs - 20 * hour, span, shortRecordStart, endMs, minSpan, maxSpan).first
        assertEquals("nor off the right", endMs - span, tooEarly, 0.0)
    }

    @Test
    fun `a window narrower than the record still cannot leave it`() {
        val span = 2 * hour
        assertEquals(
            longRecordStart,
            clampViewport(longRecordStart - 5 * hour, span, longRecordStart, endMs, minSpan, maxSpan).first,
            0.0,
        )
        assertEquals(
            endMs - span,
            clampViewport(endMs + 5 * hour, span, longRecordStart, endMs, minSpan, maxSpan).first,
            0.0,
        )
    }

    @Test
    fun `the span is held between its bounds`() {
        assertEquals(minSpan, clampViewport(endMs, 1.0, longRecordStart, endMs, minSpan, maxSpan).second, 0.0)
        assertEquals(
            maxSpan,
            clampViewport(endMs, maxSpan * 10, longRecordStart, endMs, minSpan, maxSpan).second,
            0.0,
        )
    }
}
