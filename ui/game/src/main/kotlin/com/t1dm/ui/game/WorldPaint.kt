package com.t1dm.ui.game

import com.t1dm.ui.graph.PaintFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The BG panel's freehand annotation layer, re-anchored into the game world — the same compressed-row
 * primitive-array discipline as [PaintFrame], one projection later.
 *
 * **Why it projects at all, rather than being drawn through the panel's own transform.** A stroke is
 * stored as `(tsMs, yFrac)`, and the game's x axis IS time, so the mapping is free: [WorldMap.worldXOf]
 * and [WorldMap.worldYOfFrac] are both affine and both already fixed for the run. Doing it ONCE at
 * track build buys two things the per-frame form cannot. The cull key becomes world x, so the camera
 * never round-trips through epoch-ms; and the stroke widths become world units, so a pen that was 6 dp
 * on a phone panel is a fixed number of metres of graffiti rather than a screen-constant smear that
 * shrinks as the camera pulls back.
 *
 * **Fully world-anchored** is the point: the art holds still while the camera moves, so the player
 * drives past and through it. Drawn BEFORE the ground fill, sky strokes read as scenery and anything
 * scribbled under the trace is buried by the terrain — occlusion for nothing, and no z-buffer.
 * Deliberately no collision: it is paint.
 */
class WorldPaint internal constructor(
    /** Packed sRGB ARGB per stroke; the colour the user chose, untouched. */
    val colors: IntArray,
    /** Stroke width in WORLD units (metres). See [PAINT_PANEL_DP] for the conversion's one assumption. */
    val widths: FloatArray,
    /** One of `PaintFrame.TOOL_*`. */
    val tools: IntArray,
    /** Scanned world-x bounds — the O(1) camera cull, the exact analogue of `PaintFrame.minTsMs`. */
    val minX: FloatArray,
    val maxX: FloatArray,
    /** Compressed-row point index; stroke `s` owns `[offsets[s], offsets[s + 1])`. */
    val offsets: IntArray,
    /** World x of every point of every stroke, concatenated in paint order. */
    val xs: FloatArray,
    /** World y (metres above the floor, y-UP) of every point. Outside `[0, worldHeight]` is legal —
     *  a finger that strayed off the plot stays off it. */
    val ys: FloatArray,
) {
    /** Strokes in PAINT order: oldest-authored first, so later strokes cover earlier ones. */
    val strokeCount: Int get() = colors.size

    val isEmpty: Boolean get() = colors.isEmpty()

    val pointCount: Int get() = xs.size

    /** Does stroke [s] overlap the world-x window `[fromX, toX]`? Intersection, not containment. */
    fun intersects(s: Int, fromX: Float, toX: Float): Boolean = maxX[s] >= fromX && minX[s] <= toX

    companion object {
        val EMPTY = WorldPaint(
            colors = IntArray(0), widths = FloatArray(0), tools = IntArray(0),
            minX = FloatArray(0), maxX = FloatArray(0), offsets = IntArray(1),
            xs = FloatArray(0), ys = FloatArray(0),
        )
    }
}

/**
 * Nominal height of the BG panel a stroke was authored over, in dp — the one number the dp→world width
 * conversion needs and the one number the store does not keep. `paint_stroke` persists `widthDp` and
 * nothing about the panel it was drawn on (which is `fillMaxWidth().weight(1f)`, so it varies with the
 * device and with what else is on the dashboard). A stroke's width is therefore mapped as a fraction of
 * a TYPICAL panel: 6 dp of pen over a ~320 dp panel becomes 6/320 of the world height. Wrong in the
 * fourth significant figure, invisible in a game.
 */
const val PAINT_PANEL_DP = 320f

/** Build the world paint layer off the main thread. */
suspend fun worldPaintOf(
    paint: PaintFrame,
    track: GameTrack,
    panelDp: Float = PAINT_PANEL_DP,
): WorldPaint = withContext(Dispatchers.Default) { buildWorldPaint(paint, track, panelDp) }

/**
 * Pure CPU transform — safe from a `@Preview` or a test.
 *
 * Strokes that miss the run's time window entirely are dropped here, on [PaintFrame]'s own scanned
 * bounds, which is the same `maxTs >= from && minTs <= to` predicate `PaintStrokeDao.observeOverlapping`
 * uses. That makes this cheap when the host has already queried the window (nothing is dropped) and
 * correct when it handed over the whole store (everything outside the run is). Either way it happens
 * ONCE — a per-frame query over the annotation layer is exactly what the primitive-array discipline
 * exists to prevent.
 */
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
        // Scanned, never read off the ends: a freehand stroke can be dragged backwards or double back
        // on itself, so its first and last points are not its extremes.
        minX[k] = lo
        maxX[k] = hi
    }
    offsets[n] = w
    return WorldPaint(colors, widths, tools, minX, maxX, offsets, xs, ys)
}
