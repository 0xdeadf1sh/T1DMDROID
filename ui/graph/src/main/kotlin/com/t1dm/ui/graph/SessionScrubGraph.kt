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

/**
 * A fixed-viewport chart over one bout: the glucose that actually happened, with the forecast issued
 * at the cursor drawn forward over it.
 *
 * It lives in `:ui:graph` rather than in the panel that shows it because everything it needs is
 * internal here — [drawHindsightFan], [AbsToPx]/[ValToPx], [fixedYRange]. Re-winding a fan in a
 * feature module would duplicate the τ-column→band pairing [buildPredSeries] fixed, and a second
 * y-axis rule would put two scales on the same picture.
 *
 * It is a smaller SIBLING of [GlucoseGraph], not a mode of it. The viewport is its arguments — no
 * pinch, no pan, no auto-follow, no gesture of any kind — because the cursor is driven from a slider
 * outside it, and the window is the bout plus its aftermath rather than anything the user chooses.
 *
 * Where [HindsightFrame.cycleAt] finds nothing the fan is simply absent. The catchment is NOT widened
 * and there is no nearest-fallback: over warm-up, a thermal pause or a phone that was off, no forecast
 * was issued, and pinning the last one before the hole would drag it under the finger looking exactly
 * like one that had been.
 */
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
    /**
     * The carbohydrate and insulin logged over the review window, as time-axis marks.
     *
     * A separate parameter because [GraphFrame] carries CGM readings and nothing else — and loaded
     * over the review window rather than taken from the live Logs feed, which is bounded at 400 rows
     * and would be empty for a bout from last month.
     */
    logMarkers: List<LogMarker> = emptyList(),
    tzOffsetMin: Int = 0,
    rangeMinMgdl: Int? = null,
    rangeMaxMgdl: Int? = null,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labels = remember { GraphLabelCache() }
    // Draw-phase scratch, hoisted for the reason [GlucoseGraph] hoists its own: the cursor moves at
    // pointer rate and every one of these is a constant the draw would otherwise re-allocate.
    val fanPath = remember { Path() }
    val tracePath = remember { Path() }
    // The marker layer, resolved exactly as [GlucoseGraph] resolves it so a mark means the same
    // thing on both panels. The inks come from the SEMANTIC roles rather than the Material
    // projection for the reason that panel gives: the glyphs are shape-fixed, the tint is the only
    // thing the theme still says about a mark, and a mark must be the colour of the curve channel
    // it stands for wherever it is drawn.
    val dpPx = density.density
    val semantics = LocalT1dmSemantics.current
    val carbMarkPainter = rememberVectorPainter(logMarkerIcon(CurveKind.CARB))
    val insulinMarkPainter = rememberVectorPainter(logMarkerIcon(CurveKind.INSULIN))
    val carbTint = remember(semantics.secondary) { ColorFilter.tint(semantics.secondary) }
    val insulinTint = remember(semantics.inRange) { ColorFilter.tint(semantics.inRange) }
    val carbLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.CARB) }
    val insulinLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.INSULIN) }
    val markSepPx = logMarkerSeparationPx(dpPx)
    val markSizePx = LOG_MARKER_DP * dpPx
    val traceStroke = remember { Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round) }

    // Measured from [GraphInsets], never transcribed: the BG panel's own frame, so a value read here
    // and a value read there sit on the same axis.
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

        // Over the WHOLE frame rather than a visible slice: the caller loads exactly this window and
        // the viewport never moves, so the axis is settled once and holds still while the thumb
        // travels. A scale that re-fitted under the cursor would make two read-outs incomparable.
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
            // The bout itself, shaded. The window deliberately reaches past both ends — the forecast a
            // bout provoked lands after it — so without this there is nothing saying which stretch of
            // the trace was the exercise.
            val sx0 = absToPx(sessionStartMs.toDouble()).coerceIn(plotLeft, plotRight)
            val sx1 = absToPx(sessionEndMs.toDouble()).coerceIn(plotLeft, plotRight)
            if (sx1 - sx0 > 0.5f) {
                drawRect(
                    cs.primary.copy(alpha = 0.10f),
                    topLeft = Offset(sx0, plotTop),
                    size = Size(sx1 - sx0, plotBottom - plotTop),
                )
            }

            // The logged carbohydrate and insulin, in their two lanes.
            //
            // Inside the clip and BEFORE the trace, per [drawLogMarkers]'s own instruction: an icon
            // must never sit on top of the glucose line, because a hypoglycaemic excursion drops
            // into exactly the band these lanes occupy. `plotBottom` is the furniture pass's plot
            // floor rather than `size.height`, so the lanes overlay the plot's lower band and
            // neither the value axis nor its scale moves.
            //
            // Clustered here rather than in a `remember`: this viewport is fixed by the arguments,
            // so the moving `viewStartMs`/`viewSpanMs` [GlucoseGraph] memoises against cannot change,
            // and keying a `remember` on the canvas size would add a composition dependency this
            // panel does not otherwise have.
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
            }

            // The measured trace. Breaks are honoured, so a dropout stays a dropout rather than being
            // bridged with a line nothing measured.
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

            // The forecast issued at the cursor's own cycle, in the second accent — the same hue the BG
            // panel's sweep wears, and for the same reason: it is a hindsight fan, not a live one.
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

/**
 * Where the cursor's marker is drawn, in px, under the same viewport transform the trace is drawn with:
 * [viewStartMs] is the window's start, [ppm] is px per ms.
 *
 * HELD inside the plot box rather than dropped for leaving it. The window's ends are wall-clock instants
 * — a bout begins when the user says so — while [scrubCursorOf] snaps to the grid, so at either travel
 * limit the cursor lands up to half a slot outside the window. Tested for membership instead, the marker
 * vanished at one end of the slider for roughly half of all bouts while the read-out below went on
 * quoting a time and a BG for that same instant: the picture and the table disagreeing.
 */
internal fun scrubCursorPx(
    cursorMs: Long,
    viewStartMs: Double,
    ppm: Double,
    plotLeft: Float,
    plotRight: Float,
): Float = (plotLeft + (cursorMs - viewStartMs) * ppm).toFloat()
    .coerceIn(plotLeft, plotRight.coerceAtLeast(plotLeft))

/**
 * The instant a slider sitting at [fraction] of `[windowStartMs, windowStartMs + windowSpanMs]` points
 * at, snapped to the nearest multiple of [gridMs].
 *
 * [gridMs] is a parameter rather than a constant here because the grid is `:data`'s fact and this
 * module may not restate it — the caller passes `T1dmRepository.GRID_MS`. The snap is the same
 * round-to-nearest the repository's own writers apply, so a cursor and a stored row on the same slot
 * agree about which slot that is.
 *
 * Quantising is what makes the scrub usable as well as correct: the detent under the thumb is fed this
 * value's grid index, and a raw `Float` there saturates the LRA into a flat buzz instead of a texture.
 */
fun scrubCursorOf(windowStartMs: Long, windowSpanMs: Long, fraction: Float, gridMs: Long): Long {
    if (gridMs <= 0L) return windowStartMs
    val f = fraction.coerceIn(0f, 1f).toDouble()
    val raw = windowStartMs + (f * windowSpanMs.coerceAtLeast(0L)).toLong()
    return Math.floorDiv(raw + gridMs / 2, gridMs) * gridMs
}

/** How far past the cursor the read-out quotes the swept forecast. Two horizons rather than the whole
 *  fan: the chart already draws every step, and this is the pair a bout is actually judged on. */
private val SCRUB_LOOKAHEAD = listOf("+30 min" to 1_800_000L, "+60 min" to 3_600_000L)

/**
 * The session read-out as (label, value) pairs: when the cursor is, what was measured there, and what
 * the forecast issued there said about the half hour and the hour after it.
 *
 * ALWAYS the same four rows, with a null value where there is nothing to say — a table that grew and
 * shrank under a travelling thumb would resize the box it sits in on every crossing.
 *
 * The BG is BOUNDED to half a grid slot. [GraphFrame.nearestIndex] clamps to the ends of the series,
 * so past the last reading it answers with that reading, and printed unqualified that is a measurement
 * asserted at an instant it was not taken at.
 *
 * The forecast rows are [HindsightFrame.medianAt]'s, so a cycle the app classified as degenerate or
 * stale yields no number at all: the chart can dash such a median, a table cannot, and an unqualified
 * figure here would assert what the picture beside it disowns. [HindsightFrame.degenerateAt] and
 * [HindsightFrame.staleAt] are what a caller says the reason with.
 */
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
        // No guard on `cycle` here: `medianAt` refuses an index it does not hold, an instant outside
        // the horizon, and a cycle that was never eligible — one refusal, in the one place that can
        // see all three.
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
