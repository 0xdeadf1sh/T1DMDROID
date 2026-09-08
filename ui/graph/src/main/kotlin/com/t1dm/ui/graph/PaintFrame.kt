package com.t1dm.ui.graph

import com.t1dm.core.model.PaintStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Immutable snapshot of the annotation layer, off-thread. x=abs epoch-ms, y=plot-box fraction. */
class PaintFrame internal constructor(
    /** Row id per stroke, in paint order. */
    val ids: LongArray,
    /** Packed sRGB ARGB per stroke. */
    val colors: IntArray,
    val widthsDp: FloatArray,
    /** One of the `TOOL_*` ids; see [toolIdOf]. */
    val tools: IntArray,
    val minTsMs: LongArray,
    val maxTsMs: LongArray,
    /** Compressed-row point index; length `strokeCount + 1`, ascending, `offsets[0] == 0`. */
    val offsets: IntArray,
    /** Absolute epoch-ms per point, concatenated in paint order. */
    val tsMs: LongArray,
    /** Plot-box height fraction per point; outside `[0, 1]` is legal and clipped at draw time. */
    val yFrac: FloatArray,
) {
    val strokeCount: Int get() = ids.size

    val isEmpty: Boolean get() = ids.isEmpty()

    val pointCount: Int get() = tsMs.size

    /** Intersection, not containment; edges inclusive, matching observeOverlapping in :data. */
    fun intersects(s: Int, fromMs: Double, toMs: Double): Boolean =
        maxTsMs[s] >= fromMs && minTsMs[s] <= toMs

    companion object {
        const val TOOL_FINE = 0

        const val TOOL_MARKER = 1

        const val TOOL_CHALK = 2

        const val TOOL_HIGHLIGHTER = 3

        /** Geometry is [TOOL_FINE]'s, so an older build resolving onto it still draws correctly. */
        const val TOOL_BROAD = 4

        /** Total: a future name resolves to [TOOL_FINE], not throwing or vanishing. */
        fun toolIdOf(tool: String): Int = when (tool) {
            "marker" -> TOOL_MARKER
            "chalk" -> TOOL_CHALK
            "highlighter" -> TOOL_HIGHLIGHTER
            "broad" -> TOOL_BROAD
            else -> TOOL_FINE
        }

        val EMPTY = PaintFrame(
            ids = LongArray(0), colors = IntArray(0), widthsDp = FloatArray(0), tools = IntArray(0),
            minTsMs = LongArray(0), maxTsMs = LongArray(0), offsets = IntArray(1),
            tsMs = LongArray(0), yFrac = FloatArray(0),
        )
    }
}

/** Render-side thinning gate: both are far below one pixel at the graph's maximum zoom. */
private const val MIN_STEP_MS = 200L
private const val MIN_STEP_FRAC = 5e-4f

/** Off the main thread (§2.3, GraphFrame row). */
suspend fun paintFrameOf(
    strokes: List<PaintStroke>,
    maxPointsPerStroke: Int = 4096,
): PaintFrame = withContext(Dispatchers.Default) { buildPaintFrame(strokes, maxPointsPerStroke) }

/** Pure, safe from @Preview/test. Past maxPointsPerStroke a stroke is strided, endpoints kept. */
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

/** Endpoints always survive. */
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
