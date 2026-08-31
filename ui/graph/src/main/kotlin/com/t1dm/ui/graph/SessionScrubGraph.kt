package com.t1dm.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.rememberTextMeasurer
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.logMarkerIcon
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.UnitSpace
import kotlin.math.abs

/** A fixed-viewport chart over one bout: the glucose that happened, with the forecast issued at the
 *  cursor drawn forward over it. The viewport is its arguments — no pinch, pan or auto-follow. Where
 *  [HindsightFrame.cycleAt] finds nothing the fan is absent; there is no nearest-fallback. */
@Composable
fun SessionScrubGraph(
    frame: GraphFrame,
    cursorMs: Long,
    windowStartMs: Long,
    windowSpanMs: Long,
    sessionStartMs: Long,
    sessionEndMs: Long,
    modifier: Modifier = Modifier,
    hindsight: HindsightFrame? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    thresholds: AlertThresholds? = null,
    /** Loaded over the review window rather than taken from the live Logs feed, which is bounded at
     *  400 rows and would be empty for a bout from last month. */
    logMarkers: List<LogMarker> = emptyList(),
    tzOffsetMin: Int = 0,
    rangeMinMgdl: Int? = null,
    rangeMaxMgdl: Int? = null,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labels = remember { GraphLabelCache() }
    // Draw-phase scratch: the cursor moves at pointer rate.
    val fanPath = remember { Path() }
    val tracePath = remember { Path() }
    // Inks from the SEMANTIC roles, not the Material projection: a mark must be the colour of the
    // curve channel it stands for wherever it is drawn.
    val dpPx = density.density
    val semantics = LocalT1dmSemantics.current
    val carbMarkPainter = rememberVectorPainter(logMarkerIcon(CurveKind.CARB))
    val insulinMarkPainter = rememberVectorPainter(logMarkerIcon(CurveKind.INSULIN))
    val exerciseMarkPainter = rememberVectorPainter(logMarkerIcon(CurveKind.EXERCISE))
    val carbTint = remember(semantics.secondary) { ColorFilter.tint(semantics.secondary) }
    val insulinTint = remember(semantics.inRange) { ColorFilter.tint(semantics.inRange) }
    val exerciseTint = remember(semantics.primary) { ColorFilter.tint(semantics.primary) }
    val carbLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.CARB) }
    val insulinLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.INSULIN) }
    val exerciseLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.EXERCISE) }
    val markSepPx = logMarkerSeparationPx(dpPx)
    val markSizePx = LOG_MARKER_DP * dpPx
    val traceStroke = remember { Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round) }

    // From [GraphInsets], never transcribed, so both panels sit on the same axis.
    val leftPx = with(density) { GraphInsets.Left.toPx() }
    val rightPx = with(density) { GraphInsets.Right.toPx() }
    val topPx = with(density) { GraphInsets.top(false).toPx() }
    val bottomPx = with(density) { GraphInsets.Bottom.toPx() }

    Canvas(modifier) {
        val plotLeft = leftPx
        val plotTop = topPx
        val plotRight = size.width - rightPx
        val plotBottom = size.height - bottomPx
        val plotWidth = (plotRight - plotLeft).toDouble().coerceAtLeast(1.0)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
        val viewStartMs = windowStartMs.toDouble()
        val viewSpanMs = windowSpanMs.toDouble().coerceAtLeast(1.0)
        val ppm = plotWidth / viewSpanMs

        // Over the WHOLE frame, not a visible slice: the axis is settled once and holds still while
        // the thumb travels, or two read-outs would be incomparable.
        var yMin = Float.POSITIVE_INFINITY
        var yMax = Float.NEGATIVE_INFINITY
        for (i in 0 until frame.size) {
            if (frame.ys[i] < yMin) yMin = frame.ys[i]
            if (frame.ys[i] > yMax) yMax = frame.ys[i]
        }
        if (!yMin.isFinite() || !yMax.isFinite()) { yMin = 0f; yMax = 1f }
        if (rangeMinMgdl != null && rangeMaxMgdl != null && unit != UnitSpace.Kovatchev) {
            val (a, b) = fixedYRange(yMin, yMax, unit, rangeMinMgdl, rangeMaxMgdl)
            yMin = a; yMax = b
        } else {
            val minSpanY = minValueSpan(unit)
            if (yMax - yMin < minSpanY) {
                val mid = (yMax + yMin) / 2f
                yMin = mid - minSpanY / 2f; yMax = mid + minSpanY / 2f
            }
            val padY = (yMax - yMin) * 0.08f
            yMin -= padY; yMax += padY
        }
        val ppv = plotHeight / (yMax - yMin)

        fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
        fun yToPx(v: Float): Float = plotBottom - (v - yMin) * ppv

        drawGraphFurniture(
            unit = unit,
            tzOffsetMin = tzOffsetMin,
            plotLeft = plotLeft, plotTop = plotTop, plotRight = plotRight, plotBottom = plotBottom,
            viewStartMs = viewStartMs, viewSpanMs = viewSpanMs,
            yMin = yMin, yMax = yMax,
            thresholds = thresholds,
            predictedClock = null,
            measurer = measurer,
            cs = cs,
            labels = labels,
        )

        clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
            // The bout itself, shaded: the window reaches past both ends, so nothing else says which
            // stretch of the trace was the exercise.
            val sx0 = absToPx(sessionStartMs.toDouble()).coerceIn(plotLeft, plotRight)
            val sx1 = absToPx(sessionEndMs.toDouble()).coerceIn(plotLeft, plotRight)
            if (sx1 - sx0 > 0.5f) {
                drawRect(
                    cs.primary.copy(alpha = 0.10f),
                    topLeft = Offset(sx0, plotTop),
                    size = Size(sx1 - sx0, plotBottom - plotTop),
                )
            }

            // Inside the clip and BEFORE the trace, per [drawLogMarkers]: an icon must never sit on
            // top of the glucose line, since a hypo excursion drops into the band these lanes occupy.
            if (logMarkers.isNotEmpty()) {
                drawLogMarkers(
                    clusterLogMarkers(
                        insulinLane.marks, windowStartMs.toDouble(), windowSpanMs.toDouble(),
                        plotLeft, plotRight, markSepPx,
                    ),
                    insulinMarkPainter, insulinTint, markSizePx,
                    logMarkerLaneTop(CurveKind.INSULIN, plotBottom, dpPx),
                )
                drawLogMarkers(
                    clusterLogMarkers(
                        carbLane.marks, windowStartMs.toDouble(), windowSpanMs.toDouble(),
                        plotLeft, plotRight, markSepPx,
                    ),
                    carbMarkPainter, carbTint, markSizePx,
                    logMarkerLaneTop(CurveKind.CARB, plotBottom, dpPx),
                )
                drawLogMarkers(
                    clusterLogMarkers(
                        exerciseLane.marks, windowStartMs.toDouble(), windowSpanMs.toDouble(),
                        plotLeft, plotRight, markSepPx,
                    ),
                    exerciseMarkPainter, exerciseTint, markSizePx,
                    logMarkerLaneTop(CurveKind.EXERCISE, plotBottom, dpPx),
                )
            }

            // Breaks are honoured: a dropout must not be bridged by a line nothing measured.
            if (!frame.isEmpty) {
                val path = tracePath
                path.reset()
                var open = false
                fun flush() {
                    if (open) { drawPath(path, cs.primary, style = traceStroke); path.reset(); open = false }
                }
                for (i in 0 until frame.size) {
                    val x = absToPx(frame.absMs(i))
                    val y = yToPx(frame.ys[i])
                    if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
                    if (i < frame.size - 1 && frame.breakAfter[i]) flush()
                }
                flush()
            }

            // The second accent, as on the BG panel: a hindsight fan, not a live one.
            hindsight?.let { hf ->
                if (!hf.isEmpty) {
                    val c = hf.cycleAt(cursorMs.toDouble())
                    if (c >= 0) {
                        drawHindsightFan(
                            hf, c, AbsToPx(::absToPx), ValToPx(::yToPx),
                            cs.secondary, cs.secondary, fanPath,
                        )
                    }
                }
            }

            val cx = scrubCursorPx(cursorMs, viewStartMs, ppm, plotLeft, plotRight)
            drawLine(
                cs.onSurface.copy(alpha = 0.55f),
                Offset(cx, plotTop), Offset(cx, plotBottom),
                strokeWidth = 1.5f,
            )
        }
    }
}

/** HELD inside the plot box rather than dropped for leaving it: the window's ends are wall-clock
 *  while [scrubCursorOf] snaps to the grid, so at either travel limit the cursor lands up to half a
 *  slot outside. [ppm] is px per ms. */
internal fun scrubCursorPx(
    cursorMs: Long,
    viewStartMs: Double,
    ppm: Double,
    plotLeft: Float,
    plotRight: Float,
): Float = (plotLeft + (cursorMs - viewStartMs) * ppm).toFloat()
    .coerceIn(plotLeft, plotRight.coerceAtLeast(plotLeft))

/** The instant a slider at [fraction] of the window points at, snapped to the nearest multiple of
 *  [gridMs]. [gridMs] is a parameter because the grid is `:data`'s fact and this module may not
 *  restate it — pass `T1dmRepository.GRID_MS`. */
fun scrubCursorOf(windowStartMs: Long, windowSpanMs: Long, fraction: Float, gridMs: Long): Long {
    if (gridMs <= 0L) return windowStartMs
    val f = fraction.coerceIn(0f, 1f).toDouble()
    val raw = windowStartMs + (f * windowSpanMs.coerceAtLeast(0L)).toLong()
    return Math.floorDiv(raw + gridMs / 2, gridMs) * gridMs
}

/** How far past the cursor the read-out quotes the swept forecast. */
private val SCRUB_LOOKAHEAD = listOf("+30 min" to 1_800_000L, "+60 min" to 3_600_000L)

/** The read-out as (label, value) pairs. ALWAYS the same four rows, null where there is nothing to
 *  say, so the box cannot resize under a travelling thumb. The BG is bounded to half a grid slot; a
 *  degenerate or stale cycle yields no number at all, since a table cannot dash a median. */
fun sessionScrubRows(
    frame: GraphFrame,
    hindsight: HindsightFrame?,
    cursorMs: Long,
    gridMs: Long,
    unit: UnitSpace,
    tzOffsetMin: Int,
): List<Pair<String, String?>> {
    val cycle = hindsight?.cycleAt(cursorMs.toDouble()) ?: -1
    val rows = ArrayList<Pair<String, String?>>(2 + SCRUB_LOOKAHEAD.size)
    rows.add("Local" to formatClock(cursorMs, tzOffsetMin))
    rows.add("BG" to measuredAt(frame, cursorMs, gridMs)?.let { formatValue(it, unit) })
    for ((label, offsetMs) in SCRUB_LOOKAHEAD) {
        // No guard on `cycle`: `medianAt` refuses an index it does not hold, an instant outside the
        // horizon and a cycle that was never eligible — one refusal, where all three are visible.
        val v = hindsight?.medianAt(cycle, cursorMs + offsetMs)
        rows.add(label to v?.let { formatValue(it, unit) })
    }
    return rows
}

private fun measuredAt(frame: GraphFrame, cursorMs: Long, gridMs: Long): Float? {
    if (frame.isEmpty) return null
    val i = frame.nearestIndex(cursorMs.toDouble())
    if (i < 0) return null
    val half = (gridMs / 2).coerceAtLeast(1L)
    return if (abs(frame.absMs(i) - cursorMs) <= half) frame.ys[i] else null
}
