package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ── The valueless path ──────────────────────────────────────────────────────────────────────
    //
    // A source whose wire glucose field reads zero during warm-up must file that as an ABSENT value, not
    // as 0 mg/dL — a zero there would be banded URGENT_LOW by the threshold alarm and sent on the wire.
    // The primitives overload exists for exactly that, and the AiDEX path (whose decode carries a
    // non-null Int) is unaffected by it.

    @Test
    fun `the overload files a warm-up reading with no value at all`() {
        val gs = GridStamper()
        val out = gs.stamp(
            sourceId = sourceId,
            bgMgdl = null,
            trendTenthsPerMin = null,
            minFromStart = 15,
            quality = null,
            flag = ReadingFlag.WARMUP,
            rxWallMs = base,
            tzOffsetMin = 0,
            rssi = -70,
        )
        val row = out.single()
        assertNull("zero on the wire is absent, never 0 mg/dL", row.bgMgdl)
        assertEquals(ReadingFlag.WARMUP, row.flag)
        assertEquals(ReadingProvenance.MEASURED, row.provenance)
        assertEquals(base, row.tsMs)
        assertEquals(15, row.minFromStart)
    }

    @Test
    fun `a valueless row neither anchors nor becomes an interpolated line`() {
        val gs = GridStamper()
        // Fifteen warm-up samples with no value, then the first real reading. There is nothing to
        // interpolate FROM, so nothing may be drawn between them.
        for (i in 0 until 15) {
            gs.stamp(
                sourceId, null, null, i * 3, null, ReadingFlag.WARMUP,
                base + i * 180_000L, 0, null,
            )
        }
        val first = gs.stamp(
            sourceId, 91, null, 45, null, ReadingFlag.NORMAL,
            base + 45 * 60_000L, 0, null,
        )
        assertEquals("the first real reading fabricates nothing behind it", 1, first.size)
        assertEquals(91, first.single().bgMgdl)
    }

    @Test
    fun `a NORMAL row with no value cannot become an interpolation endpoint either`() {
        val gs = GridStamper()
        gs.stamp(sourceId, 100, null, 0, null, ReadingFlag.NORMAL, base, 0, null)
        // A NORMAL flag with no value: it must not anchor, and the reading after it must not be joined to
        // the row before it across a gap neither of them measured.
        gs.stamp(sourceId, null, null, 3, null, ReadingFlag.NORMAL, base + 300_000, 0, null)
        val out = gs.stamp(sourceId, 160, null, 15, null, ReadingFlag.NORMAL, base + 900_000, 0, null)
        assertEquals(listOf(160), out.map { it.bgMgdl })
    }

    @Test
    fun `a reading whose instant precedes the last one fabricates nothing`() {
        val gs = GridStamper()
        gs.stamp(sourceId, 120, null, 30, null, ReadingFlag.NORMAL, base + 900_000, 0, null)
        // Filing on a source's own sample clock makes a backwards step reachable, where a receive instant
        // never could: a re-delivered sample, or one whose derived instant fell back to its delivery.
        // Interpolation must not run backwards across it.
        val out = gs.stamp(sourceId, 100, null, 27, null, ReadingFlag.NORMAL, base, 0, null)
        assertEquals(listOf(100), out.map { it.bgMgdl })
        assertEquals(base, out.single().tsMs)
    }

    @Test
    fun `the AiDEX path still interpolates through the overload it delegates to`() {
        // The delegation must be invisible: this is the same assertion as the interpolation test above,
        // stated again after the refactor so a regression in the delegate shows up as a lost feature.
        val gs = GridStamper()
        gs.stamp(sourceId, decode(100, 100), ReadingFlag.NORMAL, base, 0, -60)
        val out = gs.stamp(sourceId, decode(103, 130), ReadingFlag.NORMAL, base + 900_000, 0, -60)
        assertEquals(listOf(110, 120, 130), out.map { it.bgMgdl })
    }
}
