package com.t1dm.ui.graph

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphFrameTest {

    private val GRID = 300_000L                         // 5-min grid, epoch-ms
    private val T0 = 1_700_000_000_000L

    private fun reading(ts: Long, bg: Int, flag: ReadingFlag = ReadingFlag.NORMAL, tz: Int = 0) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 5, quality = 100, provenance = ReadingProvenance.MEASURED, flag = flag,
        tzOffsetMin = tz, rxWallMs = ts, rssi = -60,
    )

    @Test fun frameCarriesTheNewestOffset_notTheOldest() {
        // A history begun at +120 and continuing past a DST transition must render on +60, the
        // offset the phone keeps now.
        val summer = (0 until 10).map { reading(T0 + it * GRID, 120, tz = 120) }
        val winter = (0 until 10).map { reading(T0 + (10 + it) * GRID, 120, tz = 60) }
        assertEquals(60, buildGraphFrame(summer + winter).tzOffsetMin)
        // Input order must not matter: the frame sorts by time, and the offset follows that sort.
        assertEquals(60, buildGraphFrame(winter + summer).tzOffsetMin)
    }

    @Test fun denseContinuousSeriesHasNoFabricatedBreaks() {
        // Far past maxPoints (6000) so it decimates; every raw gap is one grid step ⇒ zero breaks.
        val n = 288 * 90
        val readings = (0 until n).map { reading(T0 + it * GRID, 100 + it % 40) }
        val frame = buildGraphFrame(readings)
        assertTrue("dense series must decimate below its raw count", frame.size < n)
        assertTrue("continuous data must produce no fabricated breaks", frame.breakAfter.none { it })
    }

    @Test fun realDropoutSurvivesDecimation() {
        // Total > 6000 ⇒ decimates, yet the one real dropout must still cut the polyline.
        val half = 4000
        val first = (0 until half).map { reading(T0 + it * GRID, 100 + it % 40) }
        val resume = first.last().tsMs + 3 * 60 * 60 * 1000L
        val second = (0 until half).map { reading(resume + it * GRID, 100 + it % 40) }
        val frame = buildGraphFrame(first + second)
        assertTrue("series must decimate", frame.size < half * 2)
        assertTrue("the real dropout survives decimation", frame.breakAfter.any { it })
    }

    @Test fun smallContiguousSeriesHasNoBreaks() {
        // Under maxPoints ⇒ no decimation.
        val readings = (0 until 100).map { reading(T0 + it * GRID, 100 + it % 40) }
        val frame = buildGraphFrame(readings)
        assertEquals(100, frame.size)
        assertTrue(frame.breakAfter.none { it })
    }

    @Test fun smallSeriesRealGapBreaks() {
        // A 45-min dropout, past the 30-min default, under maxPoints so no decimation.
        val first = (0 until 10).map { reading(T0 + it * GRID, 100 + it % 40) }
        val resume = first.last().tsMs + 45 * 60 * 1000L
        val second = (0 until 10).map { reading(resume + it * GRID, 100 + it % 40) }
        val frame = buildGraphFrame(first + second)
        assertEquals(20, frame.size)
        assertTrue(frame.breakAfter.any { it })
    }
}
