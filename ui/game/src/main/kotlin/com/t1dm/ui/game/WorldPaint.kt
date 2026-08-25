package com.t1dm.ui.game

import com.t1dm.ui.graph.PaintFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [PaintFrame] projected into the world once at track build: cull keys become world x and widths
 *  become world metres. No collision. */
class WorldPaint internal constructor(
    /** Packed sRGB ARGB per stroke. */
    val colors: IntArray,
    /** Stroke width in world metres; see [PAINT_PANEL_DP]. */
    val widths: FloatArray,
    /** One of `PaintFrame.TOOL_*`. */
    val tools: IntArray,
    /** Scanned world-x bounds, for the O(1) camera cull. */
    val minX: FloatArray,
    val maxX: FloatArray,
    /** Compressed-row point index; stroke `s` owns `[offsets[s], offsets[s + 1])`. */
    val offsets: IntArray,
    /** World x of every point, concatenated in paint order. */
    val xs: FloatArray,
    /** World y in metres above the floor, y-UP. Outside `[0, worldHeight]` is legal. */
    val ys: FloatArray,
) {
    /** Paint order: oldest first, so later strokes cover earlier ones. */
    val strokeCount: Int get() = colors.size

    val isEmpty: Boolean get() = colors.isEmpty()

    val pointCount: Int get() = xs.size

    /** Intersection, not containment. */
    fun intersects(s: Int, fromX: Float, toX: Float): Boolean = maxX[s] >= fromX && minX[s] <= toX

    companion object {
        val EMPTY = WorldPaint(
            colors = IntArray(0), widths = FloatArray(0), tools = IntArray(0),
            minX = FloatArray(0), maxX = FloatArray(0), offsets = IntArray(1),
            xs = FloatArray(0), ys = FloatArray(0),
        )
    }
}

/** Nominal dp height of the BG panel a stroke was authored over. `paint_stroke` keeps `widthDp` but
 *  nothing about the panel, so a width is mapped as a fraction of a typical one. */
const val PAINT_PANEL_DP = 320f

suspend fun worldPaintOf(
    paint: PaintFrame,
    track: GameTrack,
    panelDp: Float = PAINT_PANEL_DP,
): WorldPaint = withContext(Dispatchers.Default) { buildWorldPaint(paint, track, panelDp) }

/** Pure; safe from a `@Preview` or a test. Strokes missing the run's window are dropped here, on the
 *  same overlap predicate as `PaintStrokeDao.observeOverlapping`. */
fun buildWorldPaint(paint: PaintFrame, track: GameTrack, panelDp: Float = PAINT_PANEL_DP): WorldPaint {
    if (paint.isEmpty || track.heights.isEmpty()) return WorldPaint.EMPTY
    val from = track.startMs.toDouble()
    val to = track.endMs.toDouble()

    val keep = IntArray(paint.strokeCount)
    var n = 0
    var total = 0
    for (s in 0 until paint.strokeCount) {
        if (!paint.intersects(s, from, to)) continue
        keep[n++] = s
        total += paint.offsets[s + 1] - paint.offsets[s]
    }
    if (n == 0) return WorldPaint.EMPTY

    val map = track.map
    val worldPerDp = map.worldHeight / panelDp
    val colors = IntArray(n)
    val widths = FloatArray(n)
    val tools = IntArray(n)
    val minX = FloatArray(n)
    val maxX = FloatArray(n)
    val offsets = IntArray(n + 1)
    val xs = FloatArray(total)
    val ys = FloatArray(total)

    var w = 0
    for (k in 0 until n) {
        val s = keep[k]
        colors[k] = paint.colors[s]
        widths[k] = paint.widthsDp[s] * worldPerDp
        tools[k] = paint.tools[s]
        offsets[k] = w
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (i in paint.offsets[s] until paint.offsets[s + 1]) {
            val x = map.worldXOf(paint.tsMs[i])
            xs[w] = x
            ys[w] = map.worldYOfFrac(paint.yFrac[i])
            if (x < lo) lo = x
            if (x > hi) hi = x
            w++
        }
        // Scanned, not read off the ends: a stroke can be dragged backwards or double back on itself.
        minX[k] = lo
        maxX[k] = hi
    }
    offsets[n] = w
    return WorldPaint(colors, widths, tools, minX, maxX, offsets, xs, ys)
}
