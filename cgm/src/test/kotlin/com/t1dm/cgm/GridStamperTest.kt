package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Test

class GridStamperTest {

    private val sourceId = CgmSourceId("aidexx:test")
    private val base = 300_000L * 5_666_667L

    private fun decode(minFromStart: Int, glucose: Int) =
        AidexCodec.decode(AidexCodec.encode(minFromStart = minFromStart, glucose = glucose))!!

    @Test
    fun `snaps to the nearest 5-minute grid instant`() {
        val gs = GridStamper()
        // Past the half-grid (150 s) rounds up to the next slot.
        assertEquals(base + 300_000, gs.snap(base + 160_000))
        // Before the half-grid rounds down.
        assertEquals(base, gs.snap(base + 120_000))
    }

    @Test
    fun `linearly interpolates a three-slot gap between MEASURED points`() {
        val gs = GridStamper()
        gs.stamp(sourceId, decode(100, 100), ReadingFlag.NORMAL, base, 0, -60)
        val out = gs.stamp(sourceId, decode(103, 130), ReadingFlag.NORMAL, base + 900_000, 0, -60)

        assertEquals(listOf(110, 120, 130), out.map { it.bgMgdl })
        assertEquals(base + 300_000, out[0].tsMs)
        assertEquals(ReadingProvenance.INTERPOLATED, out[0].provenance)
        assertEquals(ReadingProvenance.INTERPOLATED, out[1].provenance)
        assertEquals(ReadingProvenance.MEASURED, out[2].provenance)
    }

    @Test
    fun `a WARMUP reading does not anchor interpolation`() {
        val gs = GridStamper()
        val warm = gs.stamp(sourceId, decode(10, 90), ReadingFlag.WARMUP, base, 0, null)
        val out = gs.stamp(sourceId, decode(70, 120), ReadingFlag.NORMAL, base + 900_000, 0, null)

        assertEquals(ReadingFlag.WARMUP, warm[0].flag)
        assertEquals(ReadingProvenance.MEASURED, warm[0].provenance)
        // No fabricated line across the suppressed warm-up value.
        assertEquals(listOf(120), out.map { it.bgMgdl })
        assertEquals(ReadingProvenance.MEASURED, out[0].provenance)
    }
}
