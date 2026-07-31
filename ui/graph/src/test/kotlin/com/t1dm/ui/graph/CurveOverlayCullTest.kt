package com.t1dm.ui.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curve overlay's viewport cull, held to the one thing that licenses it: **what the clip rectangle
 * can show is identical to what a full scan of the channel would have shown.**
 *
 * The draw itself is not reachable from a host test, so [emitCurveChannel] emits its path commands into
 * a [CurvePathSink] and these tests record them. Each case emits TWICE — once over the whole array (the
 * behaviour before the cull) and once over the culled window — reconstructs both as segment lists, and
 * asserts the two agree on every segment that intersects the plot rectangle. A segment lying wholly
 * outside it may differ freely; that is precisely the licence being claimed.
 *
 * The interesting case is a channel that is POSITIVE ACROSS THE WHOLE WINDOW, which an auto-extended
 * basal schedule makes ordinary: the run then has no opening zero to back-scan to, so the cull must be
 * safe without one.
 *
 * Two things carry that, and the tests pin both. The window is widened by a full viewport span on each
 * side, which puts the re-opened run's wall a whole plot width outside the clip — narrow that margin to
 * nothing and [cull_keepsEveryVisibleSegment_whenTheChannelIsPositiveEverywhere] and its two siblings
 * fail, which is how one knows the comparison below discriminates at all. And the run is entered at the
 * PREVIOUS bucket's own vertex, so the emitted polyline is right for any window, not merely for one
 * padded generously enough to hide a wrong one.
 */
class CurveOverlayCullTest {

    private val grid = 1_700_000_000_000L
    private val step = 300_000L
    private val n = 4032                     // MAX_OVERLAY_STEPS — ~14 days of 5-min buckets
    private val plotLeft = 40f
    private val plotRight = 400f
    private val floorY = 200f
    private val availH = 60f

    /** A viewport of [spanMs] whose LEFT edge sits at bucket [startBucket]. */
    private fun view(startBucket: Int, spanMs: Double) = (grid + startBucket.toLong() * step).toDouble() to spanMs

    private fun projector(viewStartMs: Double, viewSpanMs: Double): (Double) -> Float {
        val ppm = (plotRight - plotLeft).toDouble() / viewSpanMs
        return { ms -> (plotLeft + (ms - viewStartMs) * ppm).toFloat() }
    }

    // ── recording sink ───────────────────────────────────────────────────────────────────────────

    private sealed interface Cmd
    private data class Move(val x: Float, val y: Float) : Cmd
    private data class Line(val x: Float, val y: Float) : Cmd
    private data object End : Cmd

    private class Recorder : CurvePathSink {
        val cmds = ArrayList<Cmd>()
        override fun moveTo(x: Float, y: Float) { cmds.add(Move(x, y)) }
        override fun lineTo(x: Float, y: Float) { cmds.add(Line(x, y)) }
        override fun endRun() { cmds.add(End) }
    }

    private data class Seg(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /** The emitted commands as drawn segments, in order. A [Move] starts a new sub-path; [End] only
     *  closes the fill's polygon back to a point already on the floor, so it adds no boundary the clip
     *  can distinguish and is not a segment here. */
    private fun segments(cmds: List<Cmd>): List<Seg> {
        val out = ArrayList<Seg>()
        var cx = Float.NaN
        var cy = Float.NaN
        for (c in cmds) when (c) {
            is Move -> { cx = c.x; cy = c.y }
            is Line -> { out.add(Seg(cx, cy, c.x, c.y)); cx = c.x; cy = c.y }
            End -> Unit
        }
        return out
    }

    /** Segments whose x-interval meets the plot rectangle — everything the clip can possibly paint. */
    private fun visible(segs: List<Seg>): List<Seg> =
        segs.filter { maxOf(it.x0, it.x1) >= plotLeft && minOf(it.x0, it.x1) <= plotRight }

    private fun emit(values: FloatArray, absToPx: (Double) -> Float, lo: Int, hi: Int): List<Cmd> {
        val rec = Recorder()
        emitCurveChannel(
            values, values.max(), floorY, availH, grid, step, absToPx, lo, hi, rec,
        )
        return rec.cmds
    }

    /** Emit the whole array and the culled window, and return (full, culled) command lists. */
    private fun bothWays(values: FloatArray, viewStartMs: Double, viewSpanMs: Double): Pair<List<Cmd>, List<Cmd>> {
        val frame = buildCurveOverlay(
            carb = DoubleArray(values.size) { values[it].toDouble() },
            insulin = DoubleArray(values.size),
            gridStartMs = grid,
            stepMs = step,
        )
        val absToPx = projector(viewStartMs, viewSpanMs)
        val lo = frame.clampedIndexAt(viewStartMs - viewSpanMs)
        val hi = frame.clampedIndexAt(viewStartMs + 2.0 * viewSpanMs)
        return emit(values, absToPx, 0, values.size - 1) to emit(values, absToPx, lo, hi)
    }

    // ── channels ─────────────────────────────────────────────────────────────────────────────────

    /** Strictly positive everywhere — an auto-extended basal. One run, no opening zero anywhere. */
    private fun everywherePositive() = FloatArray(n) { 0.4f + 0.3f * kotlin.math.sin(it / 37.0).toFloat() + 0.4f }

    /** Bursty: a 3 h gamma-ish hump every 12 h, zero between — a day's meals or boluses. */
    private fun bursty() = FloatArray(n) { i ->
        val phase = i % 144
        if (phase < 36) (phase * (36 - phase)).toFloat() / 324f else 0f
    }

    // ── the identity claim ───────────────────────────────────────────────────────────────────────

    @Test fun cull_keepsEveryVisibleSegment_whenTheChannelIsPositiveEverywhere() {
        val (viewStart, span) = view(2000, 6.0 * 3_600_000.0)
        val (full, culled) = bothWays(everywherePositive(), viewStart, span)
        assertEquals(visible(segments(full)), visible(segments(culled)))
    }

    @Test fun cull_keepsEveryVisibleSegment_acrossABurstyChannel() {
        val ch = bursty()
        // Sweep the viewport across a fortnight: over a hump, over a flat stretch, and straddling both.
        for (startBucket in 0 until n - 100 step 53) {
            val (viewStart, span) = view(startBucket, 6.0 * 3_600_000.0)
            val (full, culled) = bothWays(ch, viewStart, span)
            assertEquals(
                "viewport at bucket $startBucket",
                visible(segments(full)),
                visible(segments(culled)),
            )
        }
    }

    @Test fun cull_keepsEveryVisibleSegment_atEveryZoom() {
        val ch = bursty()
        for (hours in listOf(0.25, 1.0, 3.0, 6.0, 12.0, 24.0, 72.0, 24.0 * 14)) {
            val (viewStart, span) = view(1800, hours * 3_600_000.0)
            val (full, culled) = bothWays(ch, viewStart, span)
            assertEquals("span ${hours}h", visible(segments(full)), visible(segments(culled)))
        }
    }

    @Test fun cull_keepsEveryVisibleSegment_atBothEndsOfTheGrid() {
        val ch = everywherePositive()
        for (startBucket in listOf(0, 1, 2, n - 80, n - 2, n - 1)) {
            val (viewStart, span) = view(startBucket, 6.0 * 3_600_000.0)
            val (full, culled) = bothWays(ch, viewStart, span)
            assertEquals("bucket $startBucket", visible(segments(full)), visible(segments(culled)))
        }
    }

    @Test fun cull_showsNothingWhenTheViewportIsOffTheGridEntirely() {
        val ch = bursty()
        // A fortnight before the grid, and a fortnight after it.
        for (offsetBuckets in listOf(-4032L * 2, 4032L * 2)) {
            val viewStart = (grid + offsetBuckets * step).toDouble()
            val (full, culled) = bothWays(ch, viewStart, 6.0 * 3_600_000.0)
            assertTrue("nothing is visible either way", visible(segments(full)).isEmpty())
            assertEquals(visible(segments(full)), visible(segments(culled)))
        }
    }

    // ── the entry that makes the above true ──────────────────────────────────────────────────────

    @Test fun midRunEntry_risesToThePreviousBucketsVertex_notToTheFloor() {
        val ch = everywherePositive()
        val (viewStart, span) = view(2000, 6.0 * 3_600_000.0)
        val absToPx = projector(viewStart, span)
        val frame = buildCurveOverlay(
            carb = DoubleArray(n) { ch[it].toDouble() }, insulin = DoubleArray(n),
            gridStartMs = grid, stepMs = step,
        )
        val lo = frame.clampedIndexAt(viewStart - span)
        val hi = frame.clampedIndexAt(viewStart + 2.0 * span)
        assertTrue("the case only exists mid-run", lo > 0 && ch[lo - 1] > 0f)

        val cmds = emit(ch, absToPx, lo, hi)
        val peak = ch.max()
        val xLeft = absToPx((grid + lo.toLong() * step).toDouble())
        val yPrev = floorY - (ch[lo - 1] / peak) * availH * 0.92f
        assertEquals(Move(xLeft, floorY), cmds[0])
        assertEquals("enters at the previous bucket's own vertex", Line(xLeft, yPrev), cmds[1])
        // …and that vertex is a full plot width outside the clip, which is why the wall below it cannot
        // be seen.
        assertTrue("the artificial left wall is off-screen", xLeft < plotLeft - (plotRight - plotLeft) + 1f)
    }

    @Test fun openRunAtTheRightEdge_closesOffScreen() {
        val ch = everywherePositive()
        val (viewStart, span) = view(2000, 6.0 * 3_600_000.0)
        val absToPx = projector(viewStart, span)
        val frame = buildCurveOverlay(
            carb = DoubleArray(n) { ch[it].toDouble() }, insulin = DoubleArray(n),
            gridStartMs = grid, stepMs = step,
        )
        val hi = frame.clampedIndexAt(viewStart + 2.0 * span)
        val cmds = emit(ch, absToPx, frame.clampedIndexAt(viewStart - span), hi)
        assertEquals(End, cmds.last())
        val closing = cmds[cmds.size - 2] as Line
        assertEquals("closes to the floor", floorY, closing.y, 1e-4f)
        assertTrue("the closing wall is off-screen", closing.x > plotRight + (plotRight - plotLeft) - 1f)
    }

    // ── and that the cull is not vacuous ─────────────────────────────────────────────────────────

    @Test fun cull_actuallyBoundsTheWork() {
        val (viewStart, span) = view(2000, 6.0 * 3_600_000.0)
        val (full, culled) = bothWays(everywherePositive(), viewStart, span)
        // 6 h of a fortnight-long array: three viewports' worth of buckets against 4032.
        assertTrue("full scan touches the whole array", full.size > 4000)
        assertTrue("culled draws ~3 viewports (was ${culled.size})", culled.size < 240)
    }

    @Test fun clampedIndexAt_neverEscapesTheArray() {
        val frame = buildCurveOverlay(
            carb = DoubleArray(10) { 1.0 }, insulin = DoubleArray(10), gridStartMs = grid, stepMs = step,
        )
        assertEquals(0, frame.clampedIndexAt(grid.toDouble() - 1e12))
        assertEquals(0, frame.clampedIndexAt(grid.toDouble()))
        assertEquals(3, frame.clampedIndexAt((grid + 3 * step).toDouble()))
        assertEquals(3, frame.clampedIndexAt((grid + 3 * step).toDouble() + step / 2.0))
        assertEquals(9, frame.clampedIndexAt(grid.toDouble() + 1e12))
        assertEquals("an empty frame still answers a legal index", 0, CurveOverlayFrame.EMPTY.clampedIndexAt(0.0))
    }

    @Test fun emit_drawsNothingForAFlatOrEmptyChannel() {
        val absToPx = projector(grid.toDouble(), 6.0 * 3_600_000.0)
        val rec = Recorder()
        emitCurveChannel(FloatArray(64), 0f, floorY, availH, grid, step, absToPx, 0, 63, rec)
        assertTrue(rec.cmds.isEmpty())
        val rec2 = Recorder()
        emitCurveChannel(FloatArray(0), 1f, floorY, availH, grid, step, absToPx, 0, 0, rec2)
        assertTrue(rec2.cmds.isEmpty())
    }
}
