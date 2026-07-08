package com.t1dm.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t1dm.core.design.T1dmTheme
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** What the scrub cursor currently points at; emitted so a host (dashboard read-out) can react. */
data class GraphScrub(val tsMs: Long, val value: Float, val flag: Int, val unit: UnitSpace)

/**
 * The Phase-1 live BG graph (PLAN.private.md Phase 1 / ux-decisions "Graph = the centrepiece"):
 * a background grid, time (x) and glucose (y) axes, the BG polyline with INTERPOLATED and WARMUP
 * points rendered visually distinct, pan / pinch-zoom / long-press-scrub gestures, and an auto-fit
 * Y computed over the visible window. It draws a pre-built immutable [GraphFrame] only — never a
 * `List<CgmReading>` — so all tessellation/decimation/unit-transform happen off-thread upstream.
 *
 * Reusable by construction: the dashboard hosts it full-size, widgets embed the same composable at a
 * smaller size later. Colours are drawn entirely from the active [MaterialTheme] so it tracks the
 * `:core:design` theme.
 *
 * @param onScrub invoked with the pointed-at sample while scrubbing, and `null` on release.
 */
@Composable
fun GlucoseGraph(
    frame: GraphFrame,
    modifier: Modifier = Modifier,
    thresholds: AlertThresholds? = null,
    initialWindowMin: Float = 180f,
    onScrub: ((GraphScrub?) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    val leftPx = with(density) { 46.dp.toPx() }
    val rightPx = with(density) { 12.dp.toPx() }
    val topPx = with(density) { 10.dp.toPx() }
    val bottomPx = with(density) { 20.dp.toPx() }

    // Viewport in ABSOLUTE epoch-ms (stable across frame rebuilds whose t0 may shift).
    var viewStartMs by remember { mutableStateOf(Double.NaN) }
    var viewSpanMs by remember { mutableStateOf(initialWindowMin.toDouble() * 60_000.0) }
    var followLatest by remember { mutableStateOf(true) }
    var scrubIdx by remember { mutableIntStateOf(-1) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun plotW(): Double = (canvasSize.width - leftPx - rightPx).toDouble().coerceAtLeast(1.0)

    fun spanBounds(): Pair<Double, Double> {
        val range = if (frame.isEmpty) 0.0 else frame.absMs(frame.size - 1) - frame.absMs(0)
        val minSpan = 15.0 * 60_000.0
        val maxSpan = maxOf(range, initialWindowMin.toDouble() * 60_000.0) * 1.2
        return minSpan to maxSpan.coerceAtLeast(minSpan)
    }

    fun clamp() {
        if (frame.isEmpty) return
        val (minSpan, maxSpan) = spanBounds()
        viewSpanMs = viewSpanMs.coerceIn(minSpan, maxSpan)
        val ds = frame.absMs(0)
        val de = frame.absMs(frame.size - 1)
        val range = de - ds
        viewStartMs = if (viewSpanMs >= range) ds - (viewSpanMs - range) / 2.0
        else viewStartMs.coerceIn(ds, de - viewSpanMs)
    }

    // Initialise on the first frame; keep tracking the latest point until the user scrolls back.
    LaunchedEffect(frame) {
        if (frame.isEmpty) return@LaunchedEffect
        val de = frame.absMs(frame.size - 1)
        if (viewStartMs.isNaN() || followLatest) viewStartMs = de - viewSpanMs
        clamp()
    }

    if (frame.isEmpty) {
        Box(modifier.height(220.dp), contentAlignment = Alignment.Center) {
            Text("No glucose data yet", color = cs.onSurface.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        return
    }

    Box(
        modifier
            .height(220.dp)
            .onSizeChanged { canvasSize = it }
            // Pan + pinch-zoom.
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val ppm = plotW() / viewSpanMs // px per ms
                    if (zoom != 1f) {
                        val focusMs = viewStartMs + (centroid.x - leftPx) / ppm
                        val (minSpan, maxSpan) = spanBounds()
                        val newSpan = (viewSpanMs / zoom).coerceIn(minSpan, maxSpan)
                        val frac = ((centroid.x - leftPx) / plotW()).coerceIn(0.0, 1.0)
                        viewSpanMs = newSpan
                        viewStartMs = focusMs - frac * newSpan
                    }
                    viewStartMs -= pan.x / ppm
                    val de = frame.absMs(frame.size - 1)
                    clamp()
                    followLatest = (viewStartMs + viewSpanMs) >= de - viewSpanMs * 0.02
                }
            }
            // Long-press then drag = scrub.
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val ppm = plotW() / viewSpanMs
                        scrubIdx = frame.nearestIndex(viewStartMs + (pos.x - leftPx) / ppm)
                        onScrub?.invoke(scrubTarget(frame, scrubIdx))
                    },
                    onDrag = { change, _ ->
                        val ppm = plotW() / viewSpanMs
                        scrubIdx = frame.nearestIndex(viewStartMs + (change.position.x - leftPx) / ppm)
                        onScrub?.invoke(scrubTarget(frame, scrubIdx))
                    },
                    onDragEnd = { scrubIdx = -1; onScrub?.invoke(null) },
                    onDragCancel = { scrubIdx = -1; onScrub?.invoke(null) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (viewStartMs.isNaN()) return@Canvas
            val plotLeft = leftPx
            val plotTop = topPx
            val plotRight = size.width - rightPx
            val plotBottom = size.height - bottomPx
            val plotWidth = (plotRight - plotLeft).toDouble().coerceAtLeast(1.0)
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
            val ppm = plotWidth / viewSpanMs

            // Visible index window (pad one on each side so entering/leaving segments still draw).
            val startMin = ((viewStartMs - frame.t0Ms) / 60_000.0).toFloat()
            val endMin = ((viewStartMs + viewSpanMs - frame.t0Ms) / 60_000.0).toFloat()
            var iLo = lowerBound(frame.xs, startMin) - 1
            var iHi = lowerBound(frame.xs, endMin)
            if (iLo < 0) iLo = 0
            if (iHi > frame.size - 1) iHi = frame.size - 1

            // Auto-fit Y over the visible window, padded, with a per-unit minimum span.
            var yMin = Float.POSITIVE_INFINITY
            var yMax = Float.NEGATIVE_INFINITY
            for (i in iLo..iHi) {
                if (frame.ys[i] < yMin) yMin = frame.ys[i]
                if (frame.ys[i] > yMax) yMax = frame.ys[i]
            }
            if (!yMin.isFinite() || !yMax.isFinite()) { yMin = 0f; yMax = 1f }
            val minSpanY = minValueSpan(frame.unit)
            if (yMax - yMin < minSpanY) {
                val mid = (yMax + yMin) / 2f
                yMin = mid - minSpanY / 2f; yMax = mid + minSpanY / 2f
            }
            val padY = (yMax - yMin) * 0.08f
            yMin -= padY; yMax += padY
            val ppv = plotHeight / (yMax - yMin)

            fun xToPx(min: Float): Float =
                (plotLeft + (frame.t0Ms + min.toDouble() * 60_000.0 - viewStartMs) * ppm).toFloat()
            fun yToPx(v: Float): Float = plotBottom - (v - yMin) * ppv

            // Theme-derived palette.
            val gridColor = cs.onSurface.copy(alpha = 0.10f)
            val axisColor = cs.onSurface.copy(alpha = 0.30f)
            val labelColor = cs.onSurface.copy(alpha = 0.65f)
            val lineColor = cs.primary
            val interpColor = cs.primary.copy(alpha = 0.45f)
            val warmupColor = cs.secondary
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)

            // (1) Threshold band tints, if supplied.
            thresholds?.let { drawBands(it, frame.unit, plotLeft, plotRight, ::yToPx, yMin, yMax, cs.error, cs.secondary) }

            // (2) Horizontal value grid + left-axis labels.
            val vStep = niceStep((yMax - yMin).toDouble() / 5.0)
            var vy = floor(yMin / vStep) * vStep
            while (vy <= yMax + 1e-6) {
                val py = yToPx(vy.toFloat())
                if (vy >= yMin && py in plotTop..plotBottom) {
                    drawLine(gridColor, Offset(plotLeft, py), Offset(plotRight, py), 1f)
                    val label = measurer.measure(formatValue(vy.toFloat(), frame.unit), labelStyle)
                    drawText(label, topLeft = Offset(plotLeft - 6f - label.size.width, py - label.size.height / 2f))
                }
                vy += vStep
            }

            // (3) Vertical time grid + bottom-axis labels.
            val tStepMs = niceTimeStepMs(viewSpanMs)
            val tzMs = frame.tzOffsetMin * 60_000L
            var tick = floor((viewStartMs + tzMs) / tStepMs) * tStepMs - tzMs
            if (tick < viewStartMs) tick += tStepMs
            val endMs = viewStartMs + viewSpanMs
            while (tick <= endMs) {
                val px = (plotLeft + (tick - viewStartMs) * ppm).toFloat()
                drawLine(gridColor, Offset(px, plotTop), Offset(px, plotBottom), 1f)
                val label = measurer.measure(formatTime(tick.toLong(), frame.tzOffsetMin, tStepMs), labelStyle)
                var lx = px - label.size.width / 2f
                lx = lx.coerceIn(plotLeft, plotRight - label.size.width)
                drawText(label, topLeft = Offset(lx, plotBottom + 3f))
                tick += tStepMs
            }

            // (4) Axes.
            drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), 1.5f)
            drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), 1.5f)

            // (5) BG polyline, segment-styled by provenance; gaps broken.
            for (i in iLo until iHi) {
                if (frame.breakAfter[i]) continue
                val fa = frame.flags[i]
                val fb = frame.flags[i + 1]
                val (col, effect) = when {
                    fa == GraphFrame.FLAG_WARMUP || fb == GraphFrame.FLAG_WARMUP -> warmupColor to dash
                    fa == GraphFrame.FLAG_INTERPOLATED || fb == GraphFrame.FLAG_INTERPOLATED -> interpColor to dash
                    else -> lineColor to null
                }
                drawLine(
                    col,
                    Offset(xToPx(frame.xs[i]), yToPx(frame.ys[i])),
                    Offset(xToPx(frame.xs[i + 1]), yToPx(frame.ys[i + 1])),
                    strokeWidth = 2.2f, cap = StrokeCap.Round, pathEffect = effect,
                )
            }

            // (6) Point markers — only when uncluttered, so distinctions stay legible.
            if (iHi - iLo <= 240) {
                val r = 2.6f
                for (i in iLo..iHi) {
                    val c = Offset(xToPx(frame.xs[i]), yToPx(frame.ys[i]))
                    when (frame.flags[i]) {
                        GraphFrame.FLAG_WARMUP -> drawCircle(warmupColor, r, c)
                        GraphFrame.FLAG_INTERPOLATED ->
                            drawCircle(interpColor, r, c, style = Stroke(width = 1.4f))
                        else -> drawCircle(lineColor, r, c)
                    }
                }
            }

            // (7) Scrub cursor.
            if (scrubIdx in iLo..iHi) {
                val cx = xToPx(frame.xs[scrubIdx])
                val cy = yToPx(frame.ys[scrubIdx])
                drawLine(cs.onSurface.copy(alpha = 0.5f), Offset(cx, plotTop), Offset(cx, plotBottom), 1f)
                drawCircle(cs.onSurface, 4f, Offset(cx, cy), style = Stroke(width = 2f))
                val txt = "${formatValue(frame.ys[scrubIdx], frame.unit)}  ${formatClock(frame.absMs(scrubIdx).toLong(), frame.tzOffsetMin)}"
                val lbl = measurer.measure(txt, TextStyle(color = cs.onPrimary, fontSize = 10.sp))
                val bx = (cx + 6f).coerceAtMost(plotRight - lbl.size.width - 8f)
                drawRoundRect(
                    cs.primary,
                    topLeft = Offset(bx - 4f, plotTop),
                    size = androidx.compose.ui.geometry.Size(lbl.size.width + 8f, lbl.size.height + 6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                )
                drawText(lbl, topLeft = Offset(bx, plotTop + 3f))
            }
        }
    }
}

private fun scrubTarget(frame: GraphFrame, idx: Int): GraphScrub? =
    if (idx < 0) null
    else GraphScrub(frame.absMs(idx).toLong(), frame.ys[idx], frame.flags[idx], frame.unit)

/** Smallest visible-value span so a near-flat trace still fills the plot instead of a single pixel. */
private fun minValueSpan(unit: UnitSpace): Float = when (unit) {
    UnitSpace.MgDl -> 40f
    UnitSpace.MmolL -> 2.2f
    UnitSpace.Kovatchev -> 0.6f
}

private fun DrawScope.drawBands(
    t: AlertThresholds, unit: UnitSpace, left: Float, right: Float,
    yToPx: (Float) -> Float, yMin: Float, yMax: Float, urgent: Color, warn: Color,
) {
    fun conv(mgdl: Int) = when (unit) {
        UnitSpace.MgDl -> mgdl.toFloat()
        UnitSpace.MmolL -> (mgdl / 18.0182).toFloat()
        UnitSpace.Kovatchev -> mgdl.toFloat()
    }
    fun band(loV: Float, hiV: Float, color: Color) {
        val a = yToPx(hiV.coerceIn(yMin, yMax))
        val b = yToPx(loV.coerceIn(yMin, yMax))
        if (b - a > 0.5f) drawRect(color, topLeft = Offset(left, a), size = androidx.compose.ui.geometry.Size(right - left, b - a))
    }
    if (unit == UnitSpace.Kovatchev) return // thresholds are mg/dL-defined; skip in raw space
    band(yMin, conv(t.urgentLowMgdl), urgent.copy(alpha = 0.10f))
    band(conv(t.urgentLowMgdl), conv(t.lowMgdl), warn.copy(alpha = 0.08f))
    band(conv(t.highMgdl), conv(t.urgentHighMgdl), warn.copy(alpha = 0.08f))
    band(conv(t.urgentHighMgdl), yMax, urgent.copy(alpha = 0.10f))
}

/** First index whose value is >= [target] (binary search on the ascending [xs]). */
private fun lowerBound(xs: FloatArray, target: Float): Int {
    var lo = 0
    var hi = xs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (xs[mid] < target) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun niceStep(rough: Double): Double {
    if (rough <= 0.0 || !rough.isFinite()) return 1.0
    val exp = floor(log10(rough))
    val base = 10.0.pow(exp)
    val f = rough / base
    val nf = when {
        f < 1.5 -> 1.0
        f < 3.0 -> 2.0
        f < 7.0 -> 5.0
        else -> 10.0
    }
    return nf * base
}

private val TIME_STEPS_MIN = longArrayOf(5, 10, 15, 30, 60, 120, 180, 360, 720, 1440)

private fun niceTimeStepMs(spanMs: Double): Long {
    val spanMin = spanMs / 60_000.0
    for (s in TIME_STEPS_MIN) if (spanMin / s <= 7.0) return s * 60_000L
    return TIME_STEPS_MIN.last() * 60_000L
}

private fun formatValue(v: Float, unit: UnitSpace): String = when (unit) {
    UnitSpace.MgDl -> v.roundToInt().toString()
    UnitSpace.MmolL -> "%.1f".format(v)
    UnitSpace.Kovatchev -> "%.2f".format(v)
}

private fun zoneOf(tzOffsetMin: Int) = ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)
private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

private fun formatTime(ms: Long, tzOffsetMin: Int, stepMs: Long): String {
    val odt = Instant.ofEpochMilli(ms).atOffset(zoneOf(tzOffsetMin))
    return if (stepMs >= 720L * 60_000L) odt.format(MMDD) else odt.format(HHMM)
}

private fun formatClock(ms: Long, tzOffsetMin: Int): String =
    Instant.ofEpochMilli(ms).atOffset(zoneOf(tzOffsetMin)).format(HHMM)

// ---------------------------------------------------------------------------------------------

@Preview(widthDp = 380, heightDp = 240, showBackground = true)
@Composable
private fun GlucoseGraphPreview() {
    val frame = buildGraphFrame(syntheticReadings(), UnitSpace.MgDl)
    T1dmTheme {
        GlucoseGraph(
            frame = frame,
            modifier = Modifier.fillMaxSize().padding(4.dp),
            thresholds = AlertThresholds(urgentLowMgdl = 55, lowMgdl = 80, highMgdl = 180, urgentHighMgdl = 250),
            initialWindowMin = 240f,
        )
    }
}

/** Deterministic synthetic day-ish trace with a warm-up head and one interpolated gap-fill run. */
private fun syntheticReadings(): List<CgmReading> {
    val src = com.t1dm.core.model.CgmSourceId("preview")
    val step = 300_000L
    val t0 = 1_720_000_000_000L
    val out = ArrayList<CgmReading>(120)
    var bg = 140.0
    for (i in 0 until 120) {
        val ts = t0 + i * step
        bg += Math.sin(i / 7.0) * 9.0 + (if (i % 11 == 0) -14.0 else 4.0)
        bg = bg.coerceIn(48.0, 320.0)
        val warmup = i < 8
        val interp = i in 60..66 // a fabricated gap-fill run
        out.add(
            CgmReading(
                sourceId = src, tsMs = ts,
                bgMgdl = bg.roundToInt(),
                trendTenthsPerMin = 0, minFromStart = (i + 1) * 5, quality = 100,
                provenance = if (interp) ReadingProvenance.INTERPOLATED else ReadingProvenance.MEASURED,
                flag = if (warmup) ReadingFlag.WARMUP else ReadingFlag.NORMAL,
                tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
            ),
        )
    }
    return out
}
