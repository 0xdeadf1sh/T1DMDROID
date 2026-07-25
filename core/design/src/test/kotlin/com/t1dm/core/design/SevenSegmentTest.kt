package com.t1dm.core.design

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LCD's two pure kernels. The drawing is geometry no unit test can usefully assert, but the
 * digit→segment map is a lookup table where a single transposed bit yields a glyph that is *legible
 * and wrong* — a 6 that reads as a 5 — which is exactly the class of defect a countdown to one's own
 * death should not have. The expectations here are rebuilt from the named segments rather than copied
 * from the table, so the test is an independent statement of which bars each numeral lights.
 */
class SevenSegmentTest {

    private val a = 1 shl 0 // top
    private val b = 1 shl 1 // upper-right
    private val c = 1 shl 2 // lower-right
    private val d = 1 shl 3 // bottom
    private val e = 1 shl 4 // lower-left
    private val f = 1 shl 5 // upper-left
    private val g = 1 shl 6 // middle

    @Test
    fun `every numeral lights the bars it is drawn from`() {
        assertEquals(a or b or c or d or e or f, sevenSegmentMask(0))
        assertEquals(b or c, sevenSegmentMask(1))
        assertEquals(a or b or g or e or d, sevenSegmentMask(2))
        assertEquals(a or b or g or c or d, sevenSegmentMask(3))
        assertEquals(f or g or b or c, sevenSegmentMask(4))
        assertEquals(a or f or g or c or d, sevenSegmentMask(5))
        assertEquals(a or f or g or e or c or d, sevenSegmentMask(6))
        assertEquals(a or b or c, sevenSegmentMask(7))
        assertEquals(a or b or c or d or e or f or g, sevenSegmentMask(8))
        assertEquals(a or b or c or d or f or g, sevenSegmentMask(9))
    }

    @Test
    fun `the pairs a transposed bit would confuse stay distinct`() {
        // 0 is the only numeral with no middle bar; 8 is the only one with every bar.
        assertEquals(0, sevenSegmentMask(0) and g)
        assertEquals(0x7F, sevenSegmentMask(8))
        // 6 is 5 plus the lower-left; 9 is 8 minus it. Both are one bit from their neighbour.
        assertEquals(sevenSegmentMask(5) or e, sevenSegmentMask(6))
        assertEquals(sevenSegmentMask(8) and e.inv(), sevenSegmentMask(9))
        assertEquals(10, (0..9).map { sevenSegmentMask(it) }.toSet().size)
        (0..9).forEach { assertEquals(0, sevenSegmentMask(it) and 0x7F.inv()) }
    }

    @Test
    fun `a cell outside 0-9 goes dark rather than showing a wrong glyph`() {
        assertEquals(0, sevenSegmentMask(-1))
        assertEquals(0, sevenSegmentMask(10))
        assertEquals(0, sevenSegmentMask(Int.MAX_VALUE))
    }

    @Test
    fun `the span splits into days, hours, minutes and seconds`() {
        assertArrayEquals(intArrayOf(0, 0, 0, 0), ddHhMmSs(0))
        assertArrayEquals(intArrayOf(0, 0, 0, 59), ddHhMmSs(59_999))
        assertArrayEquals(intArrayOf(0, 0, 1, 0), ddHhMmSs(60_000))
        assertArrayEquals(intArrayOf(1, 0, 0, 0), ddHhMmSs(86_400_000L))
        // The shipped default chain: 2 + 29 + 59 h = 90 h = 3 d 18 h.
        assertArrayEquals(intArrayOf(3, 18, 0, 0), ddHhMmSs(90L * 3_600_000L))
        assertArrayEquals(intArrayOf(1, 2, 3, 4), ddHhMmSs(((26L * 60 + 3) * 60 + 4) * 1000))
    }

    @Test
    fun `a lapsed landmark reads zero rather than wrapping negative`() {
        assertArrayEquals(intArrayOf(0, 0, 0, 0), ddHhMmSs(-1))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), ddHhMmSs(-90L * 3_600_000L))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), ddHhMmSs(Long.MIN_VALUE))
    }

    @Test
    fun `an absurd offset saturates the day field instead of wrapping the Int`() {
        // 106 751 991 167 days would overflow an Int; the readout must saturate, not go negative.
        val fields = ddHhMmSs(Long.MAX_VALUE)
        assertEquals(99_999, fields[0])
        assertTrue(fields.drop(1).all { it >= 0 })
    }
}
