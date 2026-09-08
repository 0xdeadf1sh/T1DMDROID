package com.t1dm.ui.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Viewport cull: clip must show identical to a full scan; emits whole array, then culled. */
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

    /** Move starts a new sub-path; End closes to a floor point, adds no clip-visible boundary. */
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

    /** Segments whose x-interval meets the plot rectangle. */
    private fun visible(segs: List<Seg>): List<Seg> =
        segs.filter { maxOf(it.x0, it.x1) >= plotLeft && minOf(it.x0, it.x1) <= plotRight }

    private fun emit(values: FloatArray, absToPx: (Double) -> Float, lo: Int, hi: Int): List<Cmd> {
        val rec = Recorder()
        emitCurveChannel(
            values, values.max(), floorY, availH, grid, step, absToPx, lo, hi, rec,
        )
        return rec.cmds
    }

    /** The whole array and the culled window, as (full, culled). */
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

    /** Strictly positive everywhere — an auto-extended basal. One run, no opening zero anywhere. */
    private fun everywherePositive() = FloatArray(n) { 0.4f + 0.3f * kotlin.math.sin(it / 37.0).toFloat() + 0.4f }

    /** A hump every 12 h, zero between — a day of meals or boluses. */
    private fun bursty() = FloatArray(n) { i ->
        val phase = i % 144
        if (phase < 36) (phase * (36 - phase)).toFloat() / 324f else 0f
    }

    @Test fun cull_keepsEveryVisibleSegment_whenTheChannelIsPositiveEverywhere() {
        val (viewStart, span) = view(2000, 6.0 * 3_600_000.0)
        val (full, culled) = bothWays(everywherePositive(), viewStart, span)
        assertEquals(visible(segments(full)), visible(segments(culled)))
    }

    @Test fun cull_keepsEveryVisibleSegment_acrossABurstyChannel() {
        val ch = bursty()
        // Over a hump, over a flat stretch, and straddling both.
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
        for (offsetBuckets in listOf(-4032L * 2, 4032L * 2)) {
            val viewStart = (grid + offsetBuckets * step).toDouble()
            val (full, culled) = bothWays(ch, viewStart, 6.0 * 3_600_000.0)
            assertTrue("nothing is visible either way", visible(segments(full)).isEmpty())
            assertEquals(visible(segments(full)), visible(segments(culled)))
        }
    }

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

    @Test fun cull_actuallyBoundsTheWork() {
        val (viewStart, span) = view(2000, 6.0 * 3_600_000.0)
        val (full, culled) = bothWays(everywherePositive(), viewStart, span)
        // 6 h of a fortnight-long array: three viewports' buckets against 4032.
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
