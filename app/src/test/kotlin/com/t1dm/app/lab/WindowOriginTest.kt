package com.t1dm.app.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a reconstruction's context window starts.
 *
 * The property that matters is one line long and was silently broken: the origin lands on an
 * ABSOLUTE patch boundary. When it does not, the panel's patch-aligned selection maps onto
 * fractional patch indices, the span straddles a boundary, and the run reports one patch more than
 * the drag covered — which a selection clamped to the model's envelope can then still exceed.
 */
class WindowOriginTest {

    private val stepMs = 300_000L
    private val patchSize = 6
    private val patchMs = patchSize * stepMs

    private fun originMs(gridStartMs: Long, from: Int) = gridStartMs + from.toLong() * stepMs

    private fun aligned(gridStartMs: Long, from: Int) = originMs(gridStartMs, from) % patchMs == 0L

    /** A grid start that is NOT itself a patch boundary — the ordinary case, since it is a reading. */
    private val skewed = (1_700_000_000_000L / stepMs * stepMs) + 2 * stepMs

    @Test
    fun the_origin_lands_on_an_absolute_patch_boundary() {
        val out = alignedWindowOrigin(preferred = 400, gridStartMs = skewed, size = 1200, steps = 600, patchSize = patchSize)!!
        assertTrue(aligned(skewed, out))
        assertTrue(out <= 400)
        assertTrue(out + 600 <= 1200)
    }

    /**
     * The case that was wrong: the window cannot slide, so the preferred origin is already at the
     * end of the series. Clamping into the series AFTER aligning undid the alignment here — and this
     * is the ordinary case for a fill near the newest reading, which is when fills are made.
     */
    @Test
    fun an_origin_at_the_end_of_the_series_is_still_aligned() {
        val size = 1000
        val steps = 600
        for (skew in 0 until patchSize) {
            val grid = (1_700_000_000_000L / patchMs * patchMs) + skew * stepMs
            val out = alignedWindowOrigin(preferred = size, gridStartMs = grid, size = size, steps = steps, patchSize = patchSize)!!
            assertTrue("skew $skew produced an unaligned origin", originMs(grid, out) % patchMs == 0L)
            assertTrue(out + steps <= size)
            assertTrue(out >= 0)
        }
    }

    /** And at the other end, where walking down would leave the series entirely. */
    @Test
    fun an_origin_at_the_start_of_the_series_is_still_aligned() {
        for (skew in 0 until patchSize) {
            val grid = (1_700_000_000_000L / patchMs * patchMs) + skew * stepMs
            val out = alignedWindowOrigin(preferred = 0, gridStartMs = grid, size = 1200, steps = 600, patchSize = patchSize)!!
            assertTrue("skew $skew produced an unaligned origin", originMs(grid, out) % patchMs == 0L)
            assertTrue(out >= 0)
        }
    }

    /**
     * With an aligned origin, a selection of N whole patches occupies exactly N patch indices. This
     * is the arithmetic `runSpan` does, and the reason the length checks became unreachable.
     */
    @Test
    fun an_aligned_origin_makes_a_span_exactly_as_long_as_the_drag() {
        val grid = skewed
        val size = 1200
        val steps = 600
        val from = alignedWindowOrigin(preferred = 500, gridStartMs = grid, size = size, steps = steps, patchSize = patchSize)!!
        // A selection snapped to absolute boundaries, four patches long, inside the window.
        val startMs = (grid + 520L * stepMs) / patchMs * patchMs
        val endMs = startMs + 4 * patchMs
        val gapStart = ((startMs - grid) / stepMs).toInt()
        val gapEnd = ((endMs - grid) / stepMs).toInt()
        assertEquals(0, (gapStart - from) % patchSize)

        val firstPatch = (gapStart - from) / patchSize
        val lastPatch = (gapEnd - 1 - from) / patchSize
        assertEquals("four patches drawn, four patches masked", 4, lastPatch - firstPatch + 1)
    }

    @Test
    fun a_series_shorter_than_one_window_has_no_origin() {
        assertNull(alignedWindowOrigin(preferred = 0, gridStartMs = skewed, size = 100, steps = 600, patchSize = patchSize))
        assertNull(alignedWindowOrigin(preferred = 0, gridStartMs = skewed, size = 1200, steps = 0, patchSize = patchSize))
    }
}
