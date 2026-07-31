package com.t1dm.ui.graph

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [visibleRange] against the per-point test it replaces.
 *
 * The smoothed trace's draw used to walk the whole history and ask of each point "is `t` within one
 * span either side of the viewport?"; it now binary-searches the range that predicate admits. The
 * predicate is monotone, so the two must select the identical set — and that is exactly what is
 * asserted here, over a history far longer than any viewport, at every zoom and at both ends.
 */
class SmoothedTraceRangeTest {

    private val t0 = 1_700_000_000_000L
    private val step = 300_000L

    /** A year of 5-min points with two real dropouts, built through the production path. */
    private fun trace(n: Int = 105_120, gapAt: Set<Int> = setOf(20_000, 60_000)): SmoothedTrace {
        val src = CgmSourceId("test")
        val out = ArrayList<CgmReading>(n)
        var ts = t0
        for (i in 0 until n) {
            ts += if (i in gapAt) 6 * step else step   // a 30-min hole ⇒ a genuine break
            out.add(
                CgmReading(
                    sourceId = src, tsMs = ts, bgMgdl = 100 + (i % 60),
                    trendTenthsPerMin = 0, minFromStart = 100, quality = 100,
                    provenance = ReadingProvenance.MEASURED, flag = ReadingFlag.NORMAL,
                    tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
                ),
            )
        }
        return buildSmoothedTrace(out, UnitSpace.MgDl, smoothMgdl = { it })
    }

    /** The predicate the draw loop applied to every point, brute-forced. */
    private fun byScan(tr: SmoothedTrace, viewStartMs: Double, viewSpanMs: Double): List<Int> {
        val lo = viewStartMs - viewSpanMs
        val hi = viewStartMs + viewSpanMs + viewSpanMs
        return (0 until tr.size).filter { tr.tsMs[it].toDouble() >= lo && tr.tsMs[it].toDouble() <= hi }
    }

    private fun assertSameSelection(tr: SmoothedTrace, viewStartMs: Double, viewSpanMs: Double, what: String) {
        assertEquals(what, byScan(tr, viewStartMs, viewSpanMs), tr.visibleRange(viewStartMs, viewSpanMs).toList())
    }

    @Test fun range_selectsExactlyWhatThePerPointCullSelected() {
        val tr = trace()
        val last = tr.tsMs[tr.size - 1]
        for (hours in listOf(0.25, 1.0, 3.0, 6.0, 12.0, 24.0, 24.0 * 30)) {
            val span = hours * 3_600_000.0
            // Live view (right edge), a mid-history pan, and the very start.
            for (start in listOf(last - span.toLong(), t0 + (last - t0) / 3, t0)) {
                assertSameSelection(tr, start.toDouble(), span, "span=${hours}h start=$start")
            }
        }
    }

    @Test fun range_isExactAtAPointsOwnInstant() {
        val tr = trace(n = 500, gapAt = emptySet())
        val span = 6.0 * 3_600_000.0
        // A viewport whose ± one span boundaries land EXACTLY on stored instants, and a half-step either
        // side of them — where a `toLong()` truncation instead of ceil/floor would admit or drop a point.
        val anchor = tr.tsMs[200].toDouble() + span
        for (nudge in listOf(-1.0, -0.5, 0.0, 0.5, 1.0, -step / 2.0, step / 2.0)) {
            assertSameSelection(tr, anchor + nudge, span, "nudge=$nudge")
        }
    }

    @Test fun range_isEmptyWhenTheViewportIsOffTheTraceEntirely() {
        val tr = trace(n = 500, gapAt = emptySet())
        val span = 6.0 * 3_600_000.0
        val before = tr.visibleRange(t0.toDouble() - 400.0 * 24 * 3_600_000.0, span)
        val after = tr.visibleRange(tr.tsMs[tr.size - 1].toDouble() + 400.0 * 24 * 3_600_000.0, span)
        assertTrue(before.isEmpty())
        assertTrue(after.isEmpty())
        assertTrue(SmoothedTrace.EMPTY.visibleRange(t0.toDouble(), span).isEmpty())
    }

    @Test fun range_actuallyBoundsTheWork() {
        val tr = trace()
        val span = 6.0 * 3_600_000.0
        val n = tr.visibleRange(tr.tsMs[tr.size - 1].toDouble() - span, span).count()
        assertTrue("a year of points is ${tr.size}", tr.size > 100_000)
        assertTrue("three viewports' worth, not a year (was $n)", n < 250)
    }
}
