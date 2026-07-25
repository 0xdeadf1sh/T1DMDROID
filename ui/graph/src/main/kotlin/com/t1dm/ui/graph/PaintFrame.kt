package com.t1dm.ui.graph

import com.t1dm.core.model.PaintStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An immutable, primitive-array snapshot of the BG panel's freehand ANNOTATION layer, ready to draw —
 * the same discipline as [GraphFrame] / [SmoothedTrace]: decode, ordering, thinning and bounds are all
 * done ONCE off the main thread in [buildPaintFrame]; [GlucoseGraph] only maps these arrays to pixels.
 * No boxing, and no `PaintStroke` (nor any other domain type) is retained by the Canvas.
 *
 * The layer belongs to the in-app BG panel alone: nothing here is reachable from the widget, the
 * notification, or the watch link, and nothing reads it back — no calculator, no model channel, no
 * §3.6 rail. It is display-only user data in the strictest sense.
 *
 * **Layout.** Points live in ONE pair of flat arrays ([tsMs] / [yFrac]) with a compressed-row
 * [offsets] index: stroke `s` owns `[offsets[s], offsets[s + 1])`. Hundreds of strokes therefore cost
 * a fixed nine arrays rather than two per stroke, which is what keeps the pan loop free of pointer
 * chasing. Per-stroke attributes are parallel arrays indexed by `s`.
 *
 * **Coordinates are viewport-independent**, exactly as in [GraphFrame]: x is an absolute epoch-ms
 * instant, y a fraction of the PLOT BOX height (0 = plot top, 1 = plot bottom). Panning and horizontal
 * zoom are therefore pure viewport transforms over the same frame — no rebuild — and the graph's Y
 * auto-fit, which rescales the value axis on every window change, can never stretch or shear the art.
 * Consequently the frame is NOT built per viewport: it is built when the stroke list changes, and the
 * Canvas culls each stroke in O(1) against [minTsMs] / [maxTsMs].
 *
 * Bounds are scanned, never read off the polyline ends: a freehand stroke can be dragged backwards or
 * double back on itself, so `tsMs.first()`/`last()` are not its extremes. They are computed over the
 * THINNED points, so they describe exactly what will be drawn.
 */
class PaintFrame internal constructor(
    /** Row id of each stroke, in paint order (used by the later erase/undo tooling). */
    val ids: LongArray,
    /** Packed sRGB ARGB per stroke. */
    val colors: IntArray,
    val widthsDp: FloatArray,
    /** One of the `TOOL_*` ids; see [toolIdOf] for why an unknown name is not an error. */
    val tools: IntArray,
    /** Earliest drawn instant of each stroke — the left half of the O(1) viewport cull. */
    val minTsMs: LongArray,
    /** Latest drawn instant of each stroke. */
    val maxTsMs: LongArray,
    /** Compressed-row point index; length `strokeCount + 1`, ascending, `offsets[0] == 0`. */
    val offsets: IntArray,
    /** Absolute epoch-ms of every point of every stroke, concatenated in paint order. */
    val tsMs: LongArray,
    /** Plot-box height fraction of every point; outside `[0, 1]` is legal and clipped at draw time. */
    val yFrac: FloatArray,
) {
    /** Strokes in PAINT order: oldest-authored first, so later strokes cover earlier ones. */
    val strokeCount: Int get() = ids.size

    val isEmpty: Boolean get() = ids.isEmpty()

    /** Total drawn points across every stroke — the frame's real cost, not [strokeCount]. */
    val pointCount: Int get() = tsMs.size

    /** Does stroke [s] intersect the window `[fromMs, toMs]`? Intersection, not containment: a stroke
     *  drawn across a wider window, or scrolled half off the current one, must still be drawn. Both
     *  edges are inclusive, matching `PaintStrokeDao.observeOverlapping`'s predicate in `:data`. */
    fun intersects(s: Int, fromMs: Double, toMs: Double): Boolean =
        maxTsMs[s] >= fromMs && minTsMs[s] <= toMs

    companion object {
        /** A thin, round-capped line — the annotating pencil, and the fallback for anything unknown. */
        const val TOOL_FINE = 0

        /** Medium, round-capped; character comes from the colour's alpha rather than from geometry. */
        const val TOOL_MARKER = 1

        /** The one TEXTURED tool: a broken, grainy line rendered as two dashed passes. */
        const val TOOL_CHALK = 2

        /** Wide and FLAT-capped, for banding a region rather than drawing on it. */
        const val TOOL_HIGHLIGHTER = 3

        /**
         * The flood pen. Round-capped and round-joined exactly like [TOOL_FINE] — a miter would throw
         * spikes hundreds of pixels long at this width and a bevel would notch every corner of a sweep
         * — so it shares the renderer's default arm and is told apart only to keep [tools] honest.
         * That the geometry is identical is also why an older build, which resolves the name onto
         * [TOOL_FINE], draws the stroke correctly rather than merely drawing something.
         */
        const val TOOL_BROAD = 4

        /**
         * Resolve the domain's OPEN text tool name ([com.t1dm.core.model.PaintTool.key]) onto a render
         * id. Total by construction: a stroke authored by a later build (or restored from a config
         * backup) resolves to [TOOL_FINE] and still draws, rather than throwing on a closed enum or
         * vanishing from the panel.
         */
        fun toolIdOf(tool: String): Int = when (tool) {
            "marker" -> TOOL_MARKER
            "chalk" -> TOOL_CHALK
            "highlighter" -> TOOL_HIGHLIGHTER
            "broad" -> TOOL_BROAD
            else -> TOOL_FINE // "fine", and anything a later build invents
        }

        val EMPTY = PaintFrame(
            ids = LongArray(0), colors = IntArray(0), widthsDp = FloatArray(0), tools = IntArray(0),
            minTsMs = LongArray(0), maxTsMs = LongArray(0), offsets = IntArray(1),
            tsMs = LongArray(0), yFrac = FloatArray(0),
        )
    }
}

/**
 * Two consecutive samples closer than this in time AND in height are the same point as far as the
 * screen is concerned. A pointer emits samples at display rate, so a finger that pauses mid-stroke
 * deposits dozens of coincident points; dropping them is pure win. The thresholds are deliberately far
 * below one pixel at the graph's MAXIMUM zoom (a 15-minute span across ~1000 px is ~900 ms/px, and the
 * panel is ~600 px tall) so thinning can never be seen, only felt.
 */
private const val MIN_STEP_MS = 200L
private const val MIN_STEP_FRAC = 5e-4f

/** Build the paint layer off the main thread (SPEC.private.md §2.3, GraphFrame row). */
suspend fun paintFrameOf(
    strokes: List<PaintStroke>,
    maxPointsPerStroke: Int = 4096,
): PaintFrame = withContext(Dispatchers.Default) { buildPaintFrame(strokes, maxPointsPerStroke) }

/**
 * Pure CPU transform (no coroutines) — safe from a `@Preview` or a test. Drops point-less strokes,
 * orders by authoring time (`createdAtMs`, `id` breaking ties within a millisecond — the same order
 * `:data` reads them back in, so what covers what is decided once and in one place), thins coincident
 * samples, and scans each stroke's true time bounds.
 *
 * [maxPointsPerStroke] is a last-resort guard against a pathological stroke (a finger held down for
 * minutes): past it the stroke is strided, endpoints kept. Thinning normally lands far below it.
 */
fun buildPaintFrame(strokes: List<PaintStroke>, maxPointsPerStroke: Int = 4096): PaintFrame {
    val kept = strokes.asSequence()
        .filter { !it.isEmpty }
        .sortedWith(compareBy({ it.createdAtMs }, { it.id }))
        .toList()
    if (kept.isEmpty()) return PaintFrame.EMPTY

    val n = kept.size
    val ts = arrayOfNulls<LongArray>(n)
    val ys = arrayOfNulls<FloatArray>(n)
    val offsets = IntArray(n + 1)
    for (s in 0 until n) {
        val st = kept[s]
        val t = thin(st.tsMs, st.yFrac, maxPointsPerStroke.coerceAtLeast(2))
        ts[s] = t.first
        ys[s] = t.second
        offsets[s + 1] = offsets[s] + t.first.size
    }

    val total = offsets[n]
    val flatTs = LongArray(total)
    val flatY = FloatArray(total)
    val minTs = LongArray(n)
    val maxTs = LongArray(n)
    for (s in 0 until n) {
        val src = ts[s]!!
        val srcY = ys[s]!!
        System.arraycopy(src, 0, flatTs, offsets[s], src.size)
        System.arraycopy(srcY, 0, flatY, offsets[s], srcY.size)
        var lo = src[0]
        var hi = src[0]
        for (i in 1 until src.size) {
            if (src[i] < lo) lo = src[i]
            if (src[i] > hi) hi = src[i]
        }
        minTs[s] = lo
        maxTs[s] = hi
    }

    return PaintFrame(
        ids = LongArray(n) { kept[it].id },
        colors = IntArray(n) { kept[it].colorArgb },
        widthsDp = FloatArray(n) { kept[it].widthDp },
        tools = IntArray(n) { PaintFrame.toolIdOf(kept[it].tool) },
        minTsMs = minTs,
        maxTsMs = maxTs,
        offsets = offsets,
        tsMs = flatTs,
        yFrac = flatY,
    )
}

/** Drop samples that land on top of their predecessor, then stride if the result is still absurd.
 *  Endpoints always survive — a stroke's first and last points are where the user put the pen down
 *  and lifted it, and thinning them would shorten visible art. */
private fun thin(tsMs: LongArray, yFrac: FloatArray, maxPoints: Int): Pair<LongArray, FloatArray> {
    val n = tsMs.size
    if (n <= 2) return tsMs.copyOf() to yFrac.copyOf()
    val ot = LongArray(n)
    val oy = FloatArray(n)
    var m = 0
    ot[0] = tsMs[0]; oy[0] = yFrac[0]; m = 1
    for (i in 1 until n - 1) {
        val dt = tsMs[i] - ot[m - 1]
        val dy = yFrac[i] - oy[m - 1]
        if (kotlin.math.abs(dt) >= MIN_STEP_MS || kotlin.math.abs(dy) >= MIN_STEP_FRAC) {
            ot[m] = tsMs[i]; oy[m] = yFrac[i]; m++
        }
    }
    ot[m] = tsMs[n - 1]; oy[m] = yFrac[n - 1]; m++

    if (m <= maxPoints) return ot.copyOf(m) to oy.copyOf(m)
    val step = (m - 1).toDouble() / (maxPoints - 1).toDouble()
    val st = LongArray(maxPoints)
    val sy = FloatArray(maxPoints)
    for (k in 0 until maxPoints) {
        val i = Math.round(k * step).toInt().coerceIn(0, m - 1)
        st[k] = ot[i]; sy[k] = oy[i]
    }
    st[maxPoints - 1] = ot[m - 1]; sy[maxPoints - 1] = oy[m - 1]
    return st to sy
}
