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

/**
 * Total width of the CORRIDOR — the band around the live BG trace that the annotation layer is
 * clipped out of. The glucose line is the one thing on this panel that must stay legible under any
 * amount of drawing, so the paint is masked away from it rather than merely drawn beneath it: a
 * scribble laid straight over the trace leaves a clean halo, not a smear the eye has to unpick.
 * Several dp, in dp so it holds its apparent thickness at widget size and on any density.
 */
internal const val PAINT_CORRIDOR_DP = 7f

/** The corridor as the stroker's pen width, in px. */
internal fun corridorWidthPx(dpPx: Float): Float = PAINT_CORRIDOR_DP * dpPx

/** Half the corridor: the exclusion RADIUS around the trace — the distance from the drawn polyline
 *  within which no annotation pixel survives. */
internal fun corridorHalfWidthPx(dpPx: Float): Float = corridorWidthPx(dpPx) / 2f

/** A stroke thinner than this is invisible; clamp so a bad width never yields a hairline nothing. */
const val MIN_STROKE_PX = 1f

/** x of an absolute epoch-ms instant under the live viewport — a pure viewport transform, so a pan
 *  never rebuilds a [PaintFrame]. [ppm] is px per ms (`plotWidth / viewSpanMs`). */
internal fun paintXPx(tsMs: Long, viewStartMs: Double, ppm: Double, plotLeft: Float): Float =
    (plotLeft + (tsMs - viewStartMs) * ppm).toFloat()

/**
 * y of a plot-box height fraction. Anchored to the PLOT BOX (`plotTop`, `plotHeight`), never the
 * composable: the box's top moves by 14 dp when the model's predicted-clock axis appears, and the same
 * stroke must survive being re-rendered at another size. Deliberately independent of the value axis —
 * the Y auto-fit rescales `[yMin, yMax]` on every window change and must never move or distort the art.
 */
internal fun paintYPx(yFrac: Float, plotTop: Float, plotHeight: Float): Float =
    plotTop + yFrac * plotHeight

/**
 * Walk the MAXIMAL unbroken runs of a drawn trace over the visible index window `[iLo, iHi]`, where
 * [breakAfter] is true at a genuine dropout following point `i`.
 *
 * This is the shared truth behind the corridor and the polyline it hugs: [GlucoseGraph] draws segment
 * `i → i+1` exactly when `!breakAfter(i)`, so the corridor must be derived from the same runs. Cut the
 * mask anywhere else and it carves a halo through a gap where no line exists — fabricating a visual
 * bridge the graph deliberately refuses to draw. A point isolated between two breaks is emitted as its
 * own single-index run, because the renderer still puts a marker there.
 *
 * Public because `:ui:game` cuts its terrain here too: the hill-climb's chasms have to fall exactly
 * where the polyline is cut, or the ground and the line stop being the same object.
 */
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

/**
 * The memoised corridor mask (item 4 — performance is the point of the primitive-array discipline).
 *
 * Deriving the band means running Skia's stroke-to-fill over the trace polyline, which costs with the
 * point count; doing it on every draw would make the mask several times more expensive than the line
 * it hugs. So the outline is rebuilt only when something it actually depends on moves — the viewport,
 * the value-axis fit, the plot box, the corridor width, or the identity of the trace object itself —
 * and reused verbatim otherwise. Both the source polyline and the outline are retained and rewound in
 * place, so a rebuild allocates nothing either; [mask] wraps the very same `android.graphics.Path`
 * every time, so the wrapper is minted once in the constructor.
 *
 * Held by `remember` and mutated inside the draw phase. That is safe precisely because none of its
 * state is snapshot state: writing to it can never invalidate a composition.
 */
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

    /**
     * Is the memoised mask out of date for this frame? Records the key as a side effect, so the caller
     * follows a true answer with [begin] / [append] / [commit] and nothing else. Fields rather than a
     * key object on purpose: this runs on every draw, including every frame of a pan.
     *
     * [traceId] is the identity of the trace object the corridor follows — a new [GraphFrame] or
     * [SmoothedTrace] is a different object, which is exactly when the polyline can have changed shape.
     */
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

    /** Start a rebuild: empty the source polyline, keeping its buffers. */
    fun begin() {
        source.rewind()
    }

    /** Append one drawn trace's visible polyline, cut at its genuine dropouts. */
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
                // A run of one is an isolated reading — the renderer still draws its marker there, and a
                // bare moveTo strokes to nothing, so close a zero-length segment and let the round cap
                // carve the disc.
                source.lineTo(xPx(a), yPx(a))
            }
        }
    }

    /**
     * Convert the appended polyline into the band's fill outline. Several corridors would compose here
     * simply by appending each trace before committing: overlapping stroke outlines share a winding
     * direction, so the non-zero fill rule unions them with no path op to fail silently. Today exactly
     * ONE BG trace is ever on screen (I5 — "Smoothed" is a swap, not an overlay), so there is one.
     */
    fun commit(widthPx: Float) {
        stroker.strokeWidth = widthPx
        outline.rewind()
        stroker.getFillPath(source.asAndroidPath(), outline)
        empty = outline.isEmpty
    }

    /** The mask to subtract, or null when no trace contributed one and nothing needs excluding. */
    val mask: Path? get() = if (empty) null else composeOutline
}

/**
 * The two dashed passes that make chalk read as chalk. Their pattern is in PIXELS and independent of
 * the stroke width, so both effects are built ONCE per draw call and shared by every chalk stroke on
 * screen — a `PathEffect` is a native object, and minting one per stroke per frame is exactly the kind
 * of quiet allocation that shows up as jank in a pan.
 *
 * The phase is fixed rather than seeded per stroke: the grain then aligns across strokes, which is
 * imperceptible, and in exchange the texture does not crawl when a stroke's identity changes.
 */
class ChalkPens(dpPx: Float) {
    val coarse: PathEffect = PathEffect.dashPathEffect(floatArrayOf(2.6f * dpPx, 1.5f * dpPx), 0f)
    val fine: PathEffect = PathEffect.dashPathEffect(floatArrayOf(1.2f * dpPx, 2.1f * dpPx), 1.3f * dpPx)
}

/**
 * Stroke one already-projected path with the character of [tool].
 *
 * The tool decides GEOMETRY and TEXTURE only — cap, join, and whether the line is broken. Width and
 * alpha ride on the stroke itself ([PaintFrame.widthsDp], and the alpha channel of its colour), because
 * the palette seeds those from the tool and then lets the user override them; re-applying a tool alpha
 * here would multiply the two and make a highlighter the user deliberately made opaque translucent again.
 *
 * Public alongside [forEachTraceRun] and for the same reason: `:ui:game` re-projects the very same
 * strokes into world space, and what a tool LOOKS like has to be decided once. Everything here is in
 * already-projected pixels, so the projection is the caller's business and the appearance is not.
 */
fun DrawScope.strokeWithTool(
    path: Path,
    color: Color,
    widthPx: Float,
    tool: Int,
    chalk: ChalkPens,
) {
    when (tool) {
        PaintFrame.TOOL_HIGHLIGHTER ->
            // Butt cap + bevel join: a highlighter has a flat nib, and a round one would bulge past the
            // ends of a band drawn to line up with a threshold.
            drawPath(path, color, style = Stroke(width = widthPx, cap = StrokeCap.Butt, join = StrokeJoin.Bevel))

        PaintFrame.TOOL_CHALK -> {
            drawPath(
                path, color,
                style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = chalk.coarse),
            )
            // A second, thinner and fainter pass at a different phase, offset off-axis: the two dash
            // patterns beat against each other, which is what reads as grain rather than as a dotted line.
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

/** A tap: the dot a zero-length stroke leaves. Square for the flat-nibbed highlighter, round otherwise. */
fun DrawScope.dotWithTool(at: Offset, color: Color, widthPx: Float, tool: Int) {
    if (tool == PaintFrame.TOOL_HIGHLIGHTER) {
        drawRect(color, topLeft = Offset(at.x - widthPx / 2f, at.y - widthPx / 2f), size = Size(widthPx, widthPx))
    } else {
        drawCircle(color, widthPx / 2f, at)
    }
}

/**
 * Paint the annotation layer. Every stroke is projected through the live viewport — absolute epoch-ms
 * to x, plot-box fraction to y — so the art scrolls with the data it was drawn over and stretches with
 * horizontal zoom, while the value axis's auto-fit leaves it untouched.
 *
 * Two culls keep a long history cheap. Whole strokes are skipped in O(1) on their scanned time bounds;
 * within a surviving stroke, segments wholly outside the viewport (± one span of slack, matching the
 * smoothed trace's own cull) are skipped and the path is cut there — which also keeps the emitted
 * coordinates near the plot rather than millions of pixels out at maximum zoom. A single-point stroke
 * is a tap, and draws as a dot: a lone `moveTo` would stroke to nothing.
 *
 * Allocation is kept out of the loop: one [scratch] path is rewound and reused across every stroke, and
 * the chalk effects are built once by the caller.
 */
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

/**
 * Paint the stroke currently under the finger. Same projection, same tool rendering, same corridor —
 * the line the user is drawing must look exactly like the one they will have drawn, including where
 * the halo cuts it, or the panel would appear to edit their work on lift-off.
 *
 * [count] is passed in rather than read off [capture] because it is the value the Canvas OBSERVES: the
 * capture buffer is plain memory mutated from the pointer handler, so the redraw is driven by a
 * snapshot-state point count and never by the buffer itself.
 */
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
