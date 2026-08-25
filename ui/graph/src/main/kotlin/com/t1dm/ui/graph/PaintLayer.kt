package com.t1dm.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate

/** Total width, in dp, of the corridor the annotation layer is masked out of around the BG trace. */
internal const val PAINT_CORRIDOR_DP = 7f

internal fun corridorWidthPx(dpPx: Float): Float = PAINT_CORRIDOR_DP * dpPx

internal fun corridorHalfWidthPx(dpPx: Float): Float = corridorWidthPx(dpPx) / 2f

/** Below this a stroke is invisible. */
const val MIN_STROKE_PX = 1f

/** [ppm] is px per ms (`plotWidth / viewSpanMs`). */
internal fun paintXPx(tsMs: Long, viewStartMs: Double, ppm: Double, plotLeft: Float): Float =
    (plotLeft + (tsMs - viewStartMs) * ppm).toFloat()

/** Anchored to the PLOT BOX, never the value axis, so the Y auto-fit cannot move or distort the art. */
internal fun paintYPx(yFrac: Float, plotTop: Float, plotHeight: Float): Float =
    plotTop + yFrac * plotHeight

/** The maximal unbroken runs over `[iLo, iHi]`. Must cut where the polyline cuts — [GlucoseGraph]
 *  draws segment `i → i+1` exactly when `!breakAfter(i)` — or the corridor carves a halo through a
 *  gap. A point isolated between two breaks is emitted as its own single-index run. */
inline fun forEachTraceRun(
    iLo: Int,
    iHi: Int,
    breakAfter: (Int) -> Boolean,
    run: (start: Int, endInclusive: Int) -> Unit,
) {
    if (iHi < iLo) return
    var start = iLo
    for (i in iLo until iHi) {
        if (breakAfter(i)) {
            run(start, i)
            start = i + 1
        }
    }
    run(start, iHi)
}

/** The memoised corridor mask: the stroke-to-fill outline, rebuilt only when [stale] says so and
 *  rewound in place otherwise. Held by `remember` and mutated inside the draw phase — safe only
 *  because none of its state is snapshot state. */
internal class PaintCorridor {
    private val source = Path()
    private val outline = android.graphics.Path()
    private val composeOutline: Path = outline.asComposePath()
    private val stroker = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    private var haveKey = false
    private var kTrace = 0
    private var kSmoothed = false
    private var kViewStartMs = Double.NaN
    private var kViewSpanMs = Double.NaN
    private var kYMin = Float.NaN
    private var kYMax = Float.NaN
    private var kLeft = Float.NaN
    private var kTop = Float.NaN
    private var kRight = Float.NaN
    private var kBottom = Float.NaN
    private var kWidthPx = Float.NaN
    private var empty = true

    /** Stale for this frame, RECORDING the key as a side effect: follow a true answer with
     *  [begin] / [append] / [commit] and nothing else. [traceId] is the trace object's identity. */
    fun stale(
        traceId: Int,
        smoothed: Boolean,
        viewStartMs: Double,
        viewSpanMs: Double,
        yMin: Float,
        yMax: Float,
        plotLeft: Float,
        plotTop: Float,
        plotRight: Float,
        plotBottom: Float,
        widthPx: Float,
    ): Boolean {
        if (haveKey && traceId == kTrace && smoothed == kSmoothed &&
            viewStartMs == kViewStartMs && viewSpanMs == kViewSpanMs &&
            yMin == kYMin && yMax == kYMax &&
            plotLeft == kLeft && plotTop == kTop && plotRight == kRight && plotBottom == kBottom &&
            widthPx == kWidthPx
        ) {
            return false
        }
        haveKey = true
        kTrace = traceId; kSmoothed = smoothed
        kViewStartMs = viewStartMs; kViewSpanMs = viewSpanMs
        kYMin = yMin; kYMax = yMax
        kLeft = plotLeft; kTop = plotTop; kRight = plotRight; kBottom = plotBottom
        kWidthPx = widthPx
        return true
    }

    fun begin() {
        source.rewind()
    }

    fun append(
        iLo: Int,
        iHi: Int,
        breakAfter: (Int) -> Boolean,
        xPx: (Int) -> Float,
        yPx: (Int) -> Float,
    ) {
        forEachTraceRun(iLo, iHi, breakAfter) { a, b ->
            source.moveTo(xPx(a), yPx(a))
            if (b > a) {
                for (i in a + 1..b) source.lineTo(xPx(i), yPx(i))
            } else {
                // A bare moveTo strokes to nothing; the zero-length segment lets the round cap
                // carve the disc.
                source.lineTo(xPx(a), yPx(a))
            }
        }
    }

    fun commit(widthPx: Float) {
        stroker.strokeWidth = widthPx
        outline.rewind()
        stroker.getFillPath(source.asAndroidPath(), outline)
        empty = outline.isEmpty
    }

    /** Null when nothing needs excluding. */
    val mask: Path? get() = if (empty) null else composeOutline
}

/** The two dashed passes chalk is drawn as. The pattern is in raw pixels and independent of the
 *  stroke, so one pair is built per draw call and shared by every chalk stroke on screen. */
class ChalkPens(dpPx: Float) {
    val coarse: PathEffect = PathEffect.dashPathEffect(floatArrayOf(2.6f * dpPx, 1.5f * dpPx), 0f)
    val fine: PathEffect = PathEffect.dashPathEffect(floatArrayOf(1.2f * dpPx, 2.1f * dpPx), 1.3f * dpPx)
}

/** [tool] decides cap, join and texture only: width and alpha ride on the stroke, and re-applying a
 *  tool alpha here would multiply the two. [path] is in already-projected pixels. */
fun DrawScope.strokeWithTool(
    path: Path,
    color: Color,
    widthPx: Float,
    tool: Int,
    chalk: ChalkPens,
) {
    when (tool) {
        PaintFrame.TOOL_HIGHLIGHTER ->
            // Flat nib: a round cap would bulge past the ends of a band drawn to line up with a
            // threshold.
            drawPath(path, color, style = Stroke(width = widthPx, cap = StrokeCap.Butt, join = StrokeJoin.Bevel))

        PaintFrame.TOOL_CHALK -> {
            drawPath(
                path, color,
                style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = chalk.coarse),
            )
            // Off-axis second pass: the two dash phases beat against each other and read as grain.
            translate(0.7f, -0.7f) {
                drawPath(
                    path, color.copy(alpha = color.alpha * 0.45f),
                    style = Stroke(
                        width = widthPx * 0.55f, cap = StrokeCap.Round, join = StrokeJoin.Round,
                        pathEffect = chalk.fine,
                    ),
                )
            }
        }

        else ->
            drawPath(path, color, style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

fun DrawScope.dotWithTool(at: Offset, color: Color, widthPx: Float, tool: Int) {
    if (tool == PaintFrame.TOOL_HIGHLIGHTER) {
        drawRect(color, topLeft = Offset(at.x - widthPx / 2f, at.y - widthPx / 2f), size = Size(widthPx, widthPx))
    } else {
        drawCircle(color, widthPx / 2f, at)
    }
}

/** Two culls: whole strokes in O(1) on their time bounds, then segments wholly outside the viewport
 *  ± one span, which also keeps emitted coordinates near the plot at maximum zoom. A single-point
 *  stroke draws as a dot. [scratch] is rewound and reused across every stroke. */
internal fun DrawScope.drawPaintFrame(
    paint: PaintFrame,
    viewStartMs: Double,
    viewSpanMs: Double,
    ppm: Double,
    plotLeft: Float,
    plotTop: Float,
    plotHeight: Float,
    dpPx: Float,
    scratch: Path,
    chalk: ChalkPens,
) {
    val fromMs = viewStartMs
    val toMs = viewStartMs + viewSpanMs
    val slackLo = fromMs - viewSpanMs
    val slackHi = toMs + viewSpanMs

    for (s in 0 until paint.strokeCount) {
        if (!paint.intersects(s, fromMs, toMs)) continue
        val a = paint.offsets[s]
        val b = paint.offsets[s + 1]
        val color = Color(paint.colors[s])
        val w = (paint.widthsDp[s] * dpPx).coerceAtLeast(MIN_STROKE_PX)
        val tool = paint.tools[s]

        if (b - a <= 1) {
            dotWithTool(
                Offset(
                    paintXPx(paint.tsMs[a], viewStartMs, ppm, plotLeft),
                    paintYPx(paint.yFrac[a], plotTop, plotHeight),
                ),
                color, w, tool,
            )
            continue
        }

        scratch.rewind()
        var open = false
        var emitted = false
        for (i in a until b - 1) {
            val t0 = paint.tsMs[i].toDouble()
            val t1 = paint.tsMs[i + 1].toDouble()
            if ((t0 < slackLo && t1 < slackLo) || (t0 > slackHi && t1 > slackHi)) {
                open = false
                continue
            }
            if (!open) {
                scratch.moveTo(
                    paintXPx(paint.tsMs[i], viewStartMs, ppm, plotLeft),
                    paintYPx(paint.yFrac[i], plotTop, plotHeight),
                )
                open = true
            }
            scratch.lineTo(
                paintXPx(paint.tsMs[i + 1], viewStartMs, ppm, plotLeft),
                paintYPx(paint.yFrac[i + 1], plotTop, plotHeight),
            )
            emitted = true
        }
        if (emitted) strokeWithTool(scratch, color, w, tool, chalk)
    }
}

/** [count] is passed rather than read off [capture]: the buffer is plain memory mutated from the
 *  pointer handler, and the redraw is driven by the snapshot-state count. */
internal fun DrawScope.drawLiveStroke(
    capture: StrokeCapture,
    count: Int,
    tool: Int,
    colorArgb: Int,
    widthDp: Float,
    viewStartMs: Double,
    ppm: Double,
    plotLeft: Float,
    plotTop: Float,
    plotHeight: Float,
    dpPx: Float,
    scratch: Path,
    chalk: ChalkPens,
) {
    if (count <= 0) return
    val color = Color(colorArgb)
    val w = (widthDp * dpPx).coerceAtLeast(MIN_STROKE_PX)
    if (count == 1) {
        dotWithTool(
            Offset(
                paintXPx(capture.tsAt(0), viewStartMs, ppm, plotLeft),
                paintYPx(capture.yFracAt(0), plotTop, plotHeight),
            ),
            color, w, tool,
        )
        return
    }
    scratch.rewind()
    scratch.moveTo(
        paintXPx(capture.tsAt(0), viewStartMs, ppm, plotLeft),
        paintYPx(capture.yFracAt(0), plotTop, plotHeight),
    )
    for (i in 1 until count) {
        scratch.lineTo(
            paintXPx(capture.tsAt(i), viewStartMs, ppm, plotLeft),
            paintYPx(capture.yFracAt(i), plotTop, plotHeight),
        )
    }
    strokeWithTool(scratch, color, w, tool, chalk)
}
