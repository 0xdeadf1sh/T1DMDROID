package com.t1dm.ui.graph

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for [buildGraphFrame]'s dropout-break logic (the "chopped up" 30d/90d regression):
 * a genuine >maxGapMin gap in the RAW series must survive min/max decimation, while decimation's own
 * point spacing must never fabricate a break. The Canvas drawing is not unit-tested; these cover the
 * `breakAfter` booleans behind it.
 */
class GraphFrameTest {

    private val GRID = 300_000L                         // 5-min grid, epoch-ms
    private val T0 = 1_700_000_000_000L

    private fun reading(ts: Long, bg: Int, flag: ReadingFlag = ReadingFlag.NORMAL) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 5, quality = 100, provenance = ReadingProvenance.MEASURED, flag = flag,
        tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

    @Test fun denseContinuousSeriesHasNoFabricatedBreaks() {
        // A 90-day series on the exact 5-min grid — the reported "chopped up" scenario. It far exceeds
        // maxPoints (6000) so it decimates; every raw gap is one grid step, so a correct frame has ZERO
        // breaks. (The pre-fix code, breaking on post-decimation spacing, fabricated thousands here.)
        val n = 288 * 90
        val readings = (0 until n).map { reading(T0 + it * GRID, 100 + it % 40) }
        val frame = buildGraphFrame(readings)
        assertTrue("dense series must decimate below its raw count", frame.size < n)
        assertTrue("continuous data must produce no fabricated breaks", frame.breakAfter.none { it })
    }

    @Test fun realDropoutSurvivesDecimation() {
        // Two dense contiguous halves separated by a genuine 3-hour dropout. Total > 6000 ⇒ decimates,
        // yet the one real >maxGapMin gap must still cut the polyline.
        val half = 4000
        val first = (0 until half).map { reading(T0 + it * GRID, 100 + it % 40) }
        val resume = first.last().tsMs + 3 * 60 * 60 * 1000L
        val second = (0 until half).map { reading(resume + it * GRID, 100 + it % 40) }
        val frame = buildGraphFrame(first + second)
        assertTrue("series must decimate", frame.size < half * 2)
        assertTrue("the real dropout survives decimation", frame.breakAfter.any { it })
    }

    @Test fun smallContiguousSeriesHasNoBreaks() {
        // Under maxPoints ⇒ no decimation; contiguous data breaks nowhere.
        val readings = (0 until 100).map { reading(T0 + it * GRID, 100 + it % 40) }
        val frame = buildGraphFrame(readings)
        assertEquals(100, frame.size)
        assertTrue(frame.breakAfter.none { it })
    }

    @Test fun smallSeriesRealGapBreaks() {
        // Ten readings, a 45-min dropout (> the 30-min default), then ten more — under maxPoints so no
        // decimation; the raw break must show through verbatim.
        val first = (0 until 10).map { reading(T0 + it * GRID, 100 + it % 40) }
        val resume = first.last().tsMs + 45 * 60 * 1000L
        val second = (0 until 10).map { reading(resume + it * GRID, 100 + it % 40) }
        val frame = buildGraphFrame(first + second)
        assertEquals(20, frame.size)
        assertTrue(frame.breakAfter.any { it })
    }
}
