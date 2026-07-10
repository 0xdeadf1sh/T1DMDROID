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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

/**
 * What the scrub cursor currently points at, emitted so the dashboard read-out can render it
 * (Phase 7A item 3). The cursor is TIME-anchored — it can land in the PREDICTION
 * zone past the last reading, where [bgValue] comes from the selected model's median and [modelHour]
 * is the model's predicted clock at that step. All values are already in the active [unit].
 */
data class GraphScrub(
    val tsMs: Long,
    val tzOffsetMin: Int,
    /** BG in the active unit at the cursor: a measured/interpolated reading in the past, the selected
     *  model's median in the prediction zone, or null when neither is available. */
    val bgValue: Float?,
    val inPredZone: Boolean,
    /** Raw carb appearance (grams per 5-min) at the cursor, or null when no overlay is present. */
    val carbRate: Float?,
    /** Raw insulin action (units per 5-min) at the cursor, or null when no overlay is present. */
    val insulinRate: Float?,
    /** The model's predicted clock hour in `[0,24)` at the cursor, or null when the probe is absent. */
    val modelHour: Double?,
    val unit: UnitSpace,
)

/**
 * The selected model's circadian-phase belief, threaded to the graph so the TOP axis can render the
 * model's PREDICTED clock (item 21) and the scrub read-out can report predicted time in the forecast
 * zone. [predictedHour] is the model's estimate of the current hour-of-day at [anchorTsMs]; the
 * predicted clock at any later time t is `predictedHour + (t − anchor)` hours, mod 24.
 */
data class PredictedClock(val predictedHour: Double, val anchorTsMs: Long, val resultantR: Double)

/**
 * An approaching threshold crossing the selected, §3.6-eligible forecast predicts (item 16): the
 * FIRST time its median crosses below the low or above the high threshold. Drawn as a marker at the
 * crossing with an ETA; produced only for an eligible forecast, so a degenerate/stale one shows none.
 */
data class ExcursionMarker(
    val tsMs: Long,
    val hyper: Boolean,
    val thresholdMgdl: Int,
    val etaMin: Long,
    /** The forecast MEDIAN (mg/dL) at the crossing step — the marker sits ON the median here (item N12),
     *  at the predicted time and level, not at the bare threshold and never in an unrelated corner. */
    val levelMgdl: Int,
)

/**
 * The Phase-1 live BG graph (Phase 1 / ux-decisions "Graph = the centrepiece"):
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
    predictions: List<PredSeries> = emptyList(),
    curveOverlay: CurveOverlayFrame? = null,
    curveToggles: CurveOverlayToggles = CurveOverlayToggles(),
    rangeMinMgdl: Int? = null,
    rangeMaxMgdl: Int? = null,
    predictedClock: PredictedClock? = null,
    excursions: List<ExcursionMarker> = emptyList(),
    smoothed: SmoothedTrace? = null,
    showSmoothed: Boolean = false,
    onScrub: ((GraphScrub?) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    val leftPx = with(density) { 46.dp.toPx() }
    val rightPx = with(density) { 12.dp.toPx() }
    // Reserve a top strip for the model's predicted-clock axis (item 21) when it is available.
    val topPx = with(density) { (if (predictedClock != null) 24.dp else 10.dp).toPx() }
    val bottomPx = with(density) { 20.dp.toPx() }

    // Viewport in ABSOLUTE epoch-ms (stable across frame rebuilds whose t0 may shift).
    var viewStartMs by remember { mutableStateOf(Double.NaN) }
    var viewSpanMs by remember { mutableStateOf(initialWindowMin.toDouble() * 60_000.0) }
    var followLatest by remember { mutableStateOf(true) }
    // TIME-anchored scrub cursor (absolute epoch-ms; NaN = inactive) so it can land in the forecast
    // zone past the last reading (item 3), not only on a BG sample.
    var scrubMs by remember { mutableStateOf(Double.NaN) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun plotW(): Double = (canvasSize.width - leftPx - rightPx).toDouble().coerceAtLeast(1.0)

    // The effective right edge of the data: the last reading OR the furthest forecast step, so the
    // 2 h forecast horizon (which lies in the future, past the last reading) stays on-screen when a
    // prediction overlay is present.
    fun dataEndMs(): Double {
        val fe = if (frame.isEmpty) 0.0 else frame.absMs(frame.size - 1)
        val pe = predictions.maxTsMs()?.toDouble() ?: return fe
        return maxOf(fe, pe)
    }

    fun spanBounds(): Pair<Double, Double> {
        val range = if (frame.isEmpty) 0.0 else dataEndMs() - frame.absMs(0)
        val minSpan = 15.0 * 60_000.0
        val maxSpan = maxOf(range, initialWindowMin.toDouble() * 60_000.0) * 1.2
        return minSpan to maxSpan.coerceAtLeast(minSpan)
    }

    fun clamp() {
        if (frame.isEmpty) return
        val (minSpan, maxSpan) = spanBounds()
        viewSpanMs = viewSpanMs.coerceIn(minSpan, maxSpan)
        val ds = frame.absMs(0)
        val de = dataEndMs()
        val range = de - ds
        viewStartMs = if (viewSpanMs >= range) ds - (viewSpanMs - range) / 2.0
        else viewStartMs.coerceIn(ds, de - viewSpanMs)
    }

    // Initialise on the first frame; keep tracking the latest point until the user scrolls back.
    LaunchedEffect(frame, predictions) {
        if (frame.isEmpty) return@LaunchedEffect
        val de = dataEndMs()
        if (viewStartMs.isNaN() || followLatest) viewStartMs = de - viewSpanMs
        clamp()
    }

    // The 6h / 12h / 24h window buttons (item 5) drive [initialWindowMin]; a change resets the visible
    // span and re-follows the latest reading so the button feels immediate.
    LaunchedEffect(initialWindowMin) {
        viewSpanMs = initialWindowMin.toDouble() * 60_000.0
        followLatest = true
        if (!frame.isEmpty) { viewStartMs = dataEndMs() - viewSpanMs; clamp() }
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
                    val de = dataEndMs()
                    clamp()
                    followLatest = (viewStartMs + viewSpanMs) >= de - viewSpanMs * 0.02
                }
            }
            // Long-press then drag = scrub. Time-anchored so the cursor works in the forecast zone.
            .pointerInput(frame, predictions, curveOverlay, predictedClock) {
                fun at(x: Float) {
                    val ppm = plotW() / viewSpanMs
                    val ms = (viewStartMs + (x - leftPx) / ppm).coerceIn(frame.absMs(0), dataEndMs())
                    scrubMs = ms
                    onScrub?.invoke(buildScrub(frame, predictions, curveOverlay, predictedClock, ms))
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos -> at(pos.x) },
                    onDrag = { change, _ -> at(change.position.x) },
                    onDragEnd = { scrubMs = Double.NaN; onScrub?.invoke(null) },
                    onDragCancel = { scrubMs = Double.NaN; onScrub?.invoke(null) },
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
            // Fold visible forecast points (median + outer band) into the auto-fit so the overlay
            // never clips off the top/bottom of the plot.
            if (predictions.isNotEmpty()) {
                val vLo = viewStartMs
                val vHi = viewStartMs + viewSpanMs
                for (s in predictions) {
                    for (i in 0 until s.size) {
                        val t = s.tsMs[i].toDouble()
                        if (t < vLo || t > vHi) continue
                        if (s.median[i] < yMin) yMin = s.median[i]
                        if (s.median[i] > yMax) yMax = s.median[i]
                        if (!s.degenerate) {
                            if (s.lo[0][i] < yMin) yMin = s.lo[0][i]
                            if (s.hi[0][i] > yMax) yMax = s.hi[0][i]
                        }
                    }
                }
            }
            if (!yMin.isFinite() || !yMax.isFinite()) { yMin = 0f; yMax = 1f }
            // Fixed axis span (item 1): always cover the configured [MIN, MAX] and GROW above MAX to
            // never clip a high reading (and below MIN to never clip a low). The range is mg/dL-defined,
            // so it applies to mg/dL + mmol/L; Kovatchev risk space keeps the data-driven auto-fit.
            val fixedApplies = rangeMinMgdl != null && rangeMaxMgdl != null && frame.unit != UnitSpace.Kovatchev
            if (fixedApplies) {
                val (a, b) = fixedYRange(yMin, yMax, frame.unit, rangeMinMgdl, rangeMaxMgdl)
                yMin = a; yMax = b
            } else {
                val minSpanY = minValueSpan(frame.unit)
                if (yMax - yMin < minSpanY) {
                    val mid = (yMax + yMin) / 2f
                    yMin = mid - minSpanY / 2f; yMax = mid + minSpanY / 2f
                }
                val padY = (yMax - yMin) * 0.08f
                yMin -= padY; yMax += padY
            }
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

            // (3) Vertical time grid + bottom-axis labels (ACTUAL local time, item 21). The TOP axis
            //     carries the model's PREDICTED clock when the circadian probe is present, else a quiet
            //     "model time n/a" — never a fabricated axis.
            val tStepMs = niceTimeStepMs(viewSpanMs)
            val tzMs = frame.tzOffsetMin * 60_000L
            var tick = floor((viewStartMs + tzMs) / tStepMs) * tStepMs - tzMs
            if (tick < viewStartMs) tick += tStepMs
            val endMs = viewStartMs + viewSpanMs
            val modelLabelColor = cs.tertiary.copy(alpha = 0.8f)
            val modelStyle = TextStyle(color = modelLabelColor, fontSize = 10.sp)
            while (tick <= endMs) {
                val px = (plotLeft + (tick - viewStartMs) * ppm).toFloat()
                drawLine(gridColor, Offset(px, plotTop), Offset(px, plotBottom), 1f)
                val label = measurer.measure(formatTime(tick.toLong(), frame.tzOffsetMin, tStepMs), labelStyle)
                var lx = px - label.size.width / 2f
                lx = lx.coerceIn(plotLeft, plotRight - label.size.width)
                drawText(label, topLeft = Offset(lx, plotBottom + 3f))
                if (predictedClock != null) {
                    val mlbl = measurer.measure(predictedClockLabel(tick.toLong(), predictedClock), modelStyle)
                    var mlx = px - mlbl.size.width / 2f
                    mlx = mlx.coerceIn(plotLeft, plotRight - mlbl.size.width)
                    drawText(mlbl, topLeft = Offset(mlx, plotTop - mlbl.size.height - 2f))
                }
                tick += tStepMs
            }
            // Timezone caption on the local axis, and the model-axis tag / n/a note.
            val tzCap = measurer.measure(tzLabel(frame.tzOffsetMin), TextStyle(color = axisColor, fontSize = 8.sp))
            drawText(tzCap, topLeft = Offset(2f, plotBottom + 3f))
            if (predictedClock != null) {
                val tag = measurer.measure("model", TextStyle(color = modelLabelColor, fontSize = 8.sp))
                drawText(tag, topLeft = Offset(2f, (plotTop - tag.size.height - 2f).coerceAtLeast(0f)))
            } else {
                val na = measurer.measure("model time n/a", TextStyle(color = axisColor, fontSize = 9.sp))
                drawText(na, topLeft = Offset(plotLeft + 4f, plotTop + 2f))
            }

            // (4) Axes.
            drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), 1.5f)
            drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), 1.5f)

            // (4.5) Curve overlay (carb Ra + insulin action) in the bottom band, UNDER the BG line
            //       so it never occludes the glucose trace (Phase 4 — toggleable).
            if (curveOverlay != null && curveToggles.any) {
                fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                val bandTop = plotBottom - plotHeight * 0.30f
                drawCurveOverlay(
                    curveOverlay, curveToggles, ::absToPx, bandTop, plotBottom,
                    carbColor = cs.secondary, insulinColor = cs.tertiary,
                )
            }

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

            // (6.4) Smoothed model-input overlay (item 13): the causal Savitzky-Golay trace the model
            //       actually consumes (mg/dL, before any risk transform), drawn distinctly — a thin
            //       dash-dot line in the tertiary hue — so it is unmistakable against the solid primary
            //       raw trace. Breaks are honoured so a dropout is not bridged with a fictitious line.
            if (showSmoothed && smoothed != null && !smoothed.isEmpty) {
                val vLo = viewStartMs
                val vHi = viewStartMs + viewSpanMs
                val smColor = cs.tertiary
                val smEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f, 1f, 3f))
                val path = Path()
                var open = false
                for (i in 0 until smoothed.size) {
                    val t = smoothed.tsMs[i].toDouble()
                    // Cull to the visible window (± one span) so a long history is cheap to paint.
                    if (t < vLo - viewSpanMs || t > vHi + viewSpanMs) {
                        if (open) { drawPath(path, smColor.copy(alpha = 0.9f), style = Stroke(width = 1.8f, pathEffect = smEffect)); path.reset(); open = false }
                        continue
                    }
                    val x = (plotLeft + (t - viewStartMs) * ppm).toFloat()
                    val y = yToPx(smoothed.ys[i])
                    if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
                    if (i < smoothed.size - 1 && smoothed.breakAfter[i]) {
                        drawPath(path, smColor.copy(alpha = 0.9f), style = Stroke(width = 1.8f, pathEffect = smEffect))
                        path.reset(); open = false
                    }
                }
                if (open) drawPath(path, smColor.copy(alpha = 0.9f), style = Stroke(width = 1.8f, pathEffect = smEffect))
                // Plain-language legend so the overlay is never mistaken for the sensor trace.
                val leg = measurer.measure("model input — smoothed", TextStyle(color = smColor, fontSize = 9.sp))
                drawText(leg, topLeft = Offset((plotRight - leg.size.width - 4f).coerceAtLeast(plotLeft), plotTop + 2f))
            }

            // (6.5) Prediction overlay: quantile fan + median for each running model. Non-selected
            //       models are drawn first (faint), the selected model last (on top, full fan).
            if (predictions.isNotEmpty()) {
                fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                val predLine = cs.tertiary
                val fan = cs.tertiary
                val flag = cs.error
                for (s in predictions) if (!s.selected) {
                    drawPredSeries(s, ::absToPx, ::yToPx, plotTop, plotBottom, predLine, fan, flag)
                }
                for (s in predictions) if (s.selected) {
                    drawPredSeries(s, ::absToPx, ::yToPx, plotTop, plotBottom, predLine, fan, flag)
                }
            }

            // (6.7) Predicted-excursion markers (item 16): the approaching hypo/hyper crossing of the
            //       §3.6-eligible forecast median, with an ETA. Suppressed in Kovatchev space (mg/dL
            //       thresholds) and off-plot; the caller already withholds these for a degenerate/stale
            //       forecast, so their mere presence is meaningful.
            if (frame.unit != UnitSpace.Kovatchev) {
                for (ex in excursions) {
                    val x = (plotLeft + (ex.tsMs - viewStartMs) * ppm).toFloat()
                    if (x < plotLeft || x > plotRight) continue
                    // N12 — anchor the marker ON the forecast median at the crossing (its predicted
                    // level), not at the bare threshold, so it reads as a point on the predicted curve.
                    val y = yToPx(convertMgdlTo(ex.levelMgdl.toFloat(), frame.unit))
                    val col = if (ex.hyper) cs.secondary else cs.error
                    val r = 5f
                    val tri = Path().apply {
                        if (ex.hyper) { moveTo(x, y - r); lineTo(x - r, y + r); lineTo(x + r, y + r) }
                        else { moveTo(x, y + r); lineTo(x - r, y - r); lineTo(x + r, y - r) }
                        close()
                    }
                    drawPath(tri, col)
                    drawLine(col.copy(alpha = 0.5f), Offset(x, plotTop), Offset(x, plotBottom), 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)))
                    val kind = if (ex.hyper) "hyper" else "hypo"
                    // ETA + the predicted level beside the marker (item N12).
                    val lbl = measurer.measure(
                        "$kind ${formatValue(convertMgdlTo(ex.levelMgdl.toFloat(), frame.unit), frame.unit)} ~${ex.etaMin}m",
                        TextStyle(color = col, fontSize = 9.sp),
                    )
                    var lx = x - lbl.size.width / 2f
                    lx = lx.coerceIn(plotLeft, plotRight - lbl.size.width)
                    val ly = if (ex.hyper) (y - r - lbl.size.height - 2f).coerceAtLeast(plotTop) else (y + r + 2f)
                    drawText(lbl, topLeft = Offset(lx, ly))
                }
            }

            // (7) Scrub cursor — time-anchored, so it reads in the forecast zone too (item 3).
            if (!scrubMs.isNaN()) {
                val cx = (plotLeft + (scrubMs - viewStartMs) * ppm).toFloat()
                if (cx in plotLeft..plotRight) {
                    val sc = buildScrub(frame, predictions, curveOverlay, predictedClock, scrubMs)
                    drawLine(cs.onSurface.copy(alpha = 0.5f), Offset(cx, plotTop), Offset(cx, plotBottom), 1f)
                    sc.bgValue?.let { drawCircle(cs.onSurface, 4f, Offset(cx, yToPx(it)), style = Stroke(width = 2f)) }
                    val lines = scrubLines(sc)
                    val measured = lines.map { measurer.measure(it, TextStyle(color = cs.onPrimary, fontSize = 10.sp)) }
                    val boxW = (measured.maxOf { it.size.width }).toFloat() + 10f
                    val lineH = measured.first().size.height.toFloat()
                    val boxH = lineH * measured.size + 6f
                    val bx = (cx + 6f).coerceAtMost(plotRight - boxW - 2f).coerceAtLeast(plotLeft)
                    drawRoundRect(
                        cs.primary,
                        topLeft = Offset(bx, plotTop),
                        size = androidx.compose.ui.geometry.Size(boxW, boxH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                    )
                    measured.forEachIndexed { i, m ->
                        drawText(m, topLeft = Offset(bx + 5f, plotTop + 3f + i * lineH))
                    }
                }
            }
        }
    }
}

/** Sample the graph at absolute [ms] for the scrub read-out (item 3). BG comes from the reading
 *  series in the past and the selected model's median in the prediction zone; carb/insulin rates from
 *  the overlay; the model clock from the circadian probe. */
private fun buildScrub(
    frame: GraphFrame,
    predictions: List<PredSeries>,
    overlay: CurveOverlayFrame?,
    clock: PredictedClock?,
    ms: Double,
): GraphScrub {
    val lastFrameMs = if (frame.isEmpty) Long.MIN_VALUE else frame.absMs(frame.size - 1).toLong()
    val inPred = ms > lastFrameMs
    val bg: Float? = when {
        !inPred && !frame.isEmpty -> frame.nearestIndex(ms).let { if (it < 0) null else frame.ys[it] }
        else -> selectedMedianAt(predictions, ms)
    }
    val carb = overlay?.carbAt(ms.toLong())?.takeIf { overlay.carbMax > 0f }
    val insulin = overlay?.insulinAt(ms.toLong())?.takeIf { overlay.insulinMax > 0f }
    val modelHour = clock?.let { predictedHourAt(ms.toLong(), it) }
    return GraphScrub(
        tsMs = ms.toLong(),
        tzOffsetMin = frame.tzOffsetMin,
        bgValue = bg,
        inPredZone = inPred,
        carbRate = carb,
        insulinRate = insulin,
        modelHour = modelHour,
        unit = frame.unit,
    )
}

/** The selected model's median (in the frame's unit) at the step nearest absolute [ms], or null. */
private fun selectedMedianAt(predictions: List<PredSeries>, ms: Double): Float? {
    val s = predictions.firstOrNull { it.selected && !it.degenerate && !it.stale } ?: return null
    if (s.isEmpty) return null
    var best = 0
    var bestD = Double.MAX_VALUE
    for (i in 0 until s.size) {
        val d = kotlin.math.abs(s.tsMs[i].toDouble() - ms)
        if (d < bestD) { bestD = d; best = i }
    }
    return s.median[best]
}

private fun predictedHourAt(ms: Long, clock: PredictedClock): Double {
    val dh = (ms - clock.anchorTsMs).toDouble() / 3_600_000.0
    var h = (clock.predictedHour + dh) % 24.0
    if (h < 0) h += 24.0
    return h
}

private fun predictedClockLabel(ms: Long, clock: PredictedClock): String {
    val h = predictedHourAt(ms, clock)
    val hh = floor(h).toInt()
    val mm = ((h - hh) * 60.0).roundToInt().coerceIn(0, 59)
    return "%02d:%02d".format(hh % 24, mm)
}

/** The scrub read-out lines: BG, carb + insulin rates, and the clock (local always; model in the
 *  prediction zone). */
private fun scrubLines(sc: GraphScrub): List<String> {
    val out = ArrayList<String>(4)
    out.add("BG " + (sc.bgValue?.let { formatValue(it, sc.unit) + (if (sc.inPredZone) "*" else "") } ?: "--"))
    val rates = buildString {
        sc.carbRate?.let { append("C %.1fg".format(it)) }
        sc.insulinRate?.let { if (isNotEmpty()) append("  "); append("I %.2fU".format(it)) }
    }
    if (rates.isNotEmpty()) out.add(rates)
    out.add(formatClock(sc.tsMs, sc.tzOffsetMin) + " loc")
    if (sc.modelHour != null) {
        val hh = floor(sc.modelHour).toInt(); val mm = ((sc.modelHour - hh) * 60.0).roundToInt().coerceIn(0, 59)
        out.add("%02d:%02d mdl".format(hh % 24, mm))
    }
    return out
}

private fun convertMgdlTo(mgdl: Float, unit: UnitSpace): Float = when (unit) {
    UnitSpace.MgDl, UnitSpace.Kovatchev -> mgdl
    UnitSpace.MmolL -> (mgdl / 18.0182).toFloat()
}

/**
 * The fixed-span Y-axis rule (item 1): the axis always covers the configured [rangeMinMgdl]..
 * [rangeMaxMgdl] (converted into [unit]) and GROWS to include any data beyond — above the ceiling for
 * highs, below the floor for lows — then rounds out to a sensible tick. Never clips a reading.
 * Extracted from the Canvas so it can be unit-tested. [dataYMin]/[dataYMax] are the visible data (+
 * forecast) extremes already in [unit].
 */
internal fun fixedYRange(
    dataYMin: Float,
    dataYMax: Float,
    unit: UnitSpace,
    rangeMinMgdl: Int,
    rangeMaxMgdl: Int,
): Pair<Float, Float> {
    val rLo = convertMgdlTo(rangeMinMgdl.toFloat(), unit)
    val rHi = convertMgdlTo(rangeMaxMgdl.toFloat(), unit)
    var yMin = minOf(dataYMin, rLo)
    var yMax = maxOf(dataYMax, rHi)
    val step = niceStep((yMax - yMin).toDouble() / 5.0)
    yMax = (Math.ceil(yMax / step) * step).toFloat()
    yMin = (floor(yMin / step) * step).toFloat()
    return yMin to yMax
}

private fun tzLabel(tzOffsetMin: Int): String {
    val sign = if (tzOffsetMin < 0) "-" else "+"
    val a = kotlin.math.abs(tzOffsetMin)
    return "UTC$sign${a / 60}" + (if (a % 60 != 0) ":%02d".format(a % 60) else "")
}

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
