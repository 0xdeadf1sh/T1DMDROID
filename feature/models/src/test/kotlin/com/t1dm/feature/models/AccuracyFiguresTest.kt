package com.t1dm.feature.models

import com.t1dm.core.model.CgEgaRegion
import com.t1dm.core.model.PointBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two partitions the figures assemble on this side. Everything else they draw is the core's own
 * number, rendered; these two are re-partitioned here, so they are the only arithmetic in the
 * figures that can be wrong on its own.
 */
class AccuracyFiguresTest {

    private fun block(a: Double, ab: Double, d: Double, e: Double) = PointBlock(
        rmsePoint = 20.0, maePoint = 15.0, rmseWinmean = 18.0, maeWinmean = 13.0, mard = 9.0,
        clarkeA = a, clarkeAb = ab, clarkeD = d, clarkeE = e, skillPoint = 0.3,
    )

    @Test
    fun `the five zones partition the window`() {
        val s = clarkeShares(block(a = 82.0, ab = 96.0, d = 3.0, e = 1.0))
        assertEquals(listOf(82f, 14f, 0f, 3f, 1f), s)
        assertEquals(100f, s.sum(), 1e-3f)
    }

    /** C is not published by the core; it is what the other four leave. */
    @Test
    fun `zone C absorbs the remainder`() {
        val s = clarkeShares(block(a = 70.0, ab = 80.0, d = 5.0, e = 2.0))
        assertEquals(13f, s[2], 1e-4f)
        assertEquals(100f, s.sum(), 1e-3f)
    }

    /** Float noise must not turn a full partition into a negative slice. */
    @Test
    fun `an over-full partition clamps rather than inverts`() {
        val s = clarkeShares(block(a = 90.0, ab = 100.0000001, d = 0.0, e = 0.0))
        assertTrue(s.all { it >= 0f })
        assertEquals(0f, s[2], 1e-4f)
    }

    /** A partial partition would render as a plausible shape, which is worse than no shape. */
    @Test
    fun `a non-finite input yields no bar at all`() {
        assertEquals(emptyList<Float>(), clarkeShares(block(a = Double.NaN, ab = 96.0, d = 3.0, e = 1.0)))
        assertEquals(emptyList<Float>(), clarkeShares(block(a = 82.0, ab = 96.0, d = 3.0, e = Double.NaN)))
    }

    /** A region that held no point has no triple — an empty track, and the count says why. */
    @Test
    fun `an empty CG-EGA region draws nothing and carries its zero`() {
        val row = cgEgaRow("hypo", CgEgaRegion(apPct = null, bePct = null, epPct = null, nAp = 0, nBe = 0, nEp = 0))
        assertEquals(emptyList<Float>(), row.shares)
        assertEquals("n=0", row.note)
    }

    @Test
    fun `a populated CG-EGA region carries its own denominator`() {
        val row = cgEgaRow("eu", CgEgaRegion(apPct = 91.0, bePct = 7.0, epPct = 2.0, nAp = 910, nBe = 70, nEp = 20))
        assertEquals(listOf(91f, 7f, 2f), row.shares)
        assertEquals("n=1000", row.note)
    }
}
