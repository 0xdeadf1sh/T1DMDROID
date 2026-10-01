package com.t1dm.ui.graph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmHaptics
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.T1dmTheme
import com.t1dm.core.design.logMarkerIcon
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.MaskGeometry
import com.t1dm.core.model.ReconstructedBg
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Time-anchored, so the cursor can land past the last reading. Values are in the active [unit]. */
data class GraphScrub(
    val tsMs: Long,
    val tzOffsetMin: Int,
    /** Past reading, prediction-zone median, or display-only roll past validity; else null. */
    val bgValue: Float?,
    val inPredZone: Boolean,
    /** bgValue from the roll past its validated prefix; unrendered, kept out of every rail. */
    val bgExtrapolated: Boolean,
    /** Grams per 5-min, or null with no overlay. */
    val carbRate: Float?,
    /** Units per 5-min, or null with no overlay. */
    val insulinRate: Float?,
    /** Grams of carbohydrate EQUIVALENT disposed per 5-min, or null with no overlay. */
    val exerciseRate: Float?,
    /** Null only with no pedometer feed; an unmeasured bucket reads 0, keeping rows fixed. */
    val steps: Int?,
    /** Predicted hour in `[0,24)`, or null with no probe. */
    val modelHour: Double?,
    val unit: UnitSpace,
)

/** predictedHour is hour at anchorTsMs; at later t: predictedHour + (t-anchor) hours, mod 24. */
data class PredictedClock(val predictedHour: Double, val anchorTsMs: Long, val resultantR: Double)

/** FIRST time a forecast median crosses a threshold; a stale or degenerate one yields none. */
data class ExcursionMarker(
    val tsMs: Long,
    val hyper: Boolean,
    val thresholdMgdl: Int,
    val etaMin: Long,
    /** The forecast median (mg/dL) at the crossing; the marker sits on it, not at the threshold. */
    val levelMgdl: Int,
)

/** Null paintControls means paint mode is off; tool is a PaintTool key, not the enum. */
data class PaintControls(
    val tool: String,
    val colorArgb: Int,
    val widthDp: Float,
    val eraser: Boolean = false,
)

private enum class PaintGesture { DRAW, ERASE, TRANSFORM }

/** Constant label set and value template: depends on theme/density, nothing the cursor does. */
private class ScrubMetrics(val labelColW: Float, val valueColW: Float, val lineH: Float)

/** Every paint coord anchors here, never the composable, whose top moves with the clock axis. */
private class PlotBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Double get() = (right - left).toDouble().coerceAtLeast(1.0)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

/** Draws a pre-built GraphFrame only, never List<CgmReading>: transform happens upstream. */
@Composable
fun GlucoseGraph(
    frame: GraphFrame,
    modifier: Modifier = Modifier,
    thresholds: AlertThresholds? = null,
    initialWindowMin: Float = 180f,
    predictions: List<PredSeries> = emptyList(),
    curveOverlay: CurveOverlayFrame? = null,
    curveToggles: CurveOverlayToggles = CurveOverlayToggles(),
    // Measured, apart from [curveOverlay]'s carb, insulin and exercise curves.
    stepsFrame: StepsFrame? = null,
    // One icon per logged event, one lane per log kind, low in the plot. No amount, no row id.
    logMarkers: List<LogMarker> = emptyList(),
    /** Positions in logMarkers of every log behind the mark, all lanes; indices, not markers. */
    onMarkerTap: ((List<Int>) -> Unit)? = null,
    /** DISPLAY ONLY: nothing here is stored; a fill is written by the edit bar's Fill. */
    reconstructed: List<ReconstructedBg> = emptyList(),
    /** mg/dL -> Kovatchev risk from Rust core; null on risk axis draws no reconstruction. */
    kovatchevF: ((Double) -> Double)? = null,
    /** Non-null puts the panel in EDIT mode: one finger drags a stretch, two+ pan/zoom the view. */
    maskControls: MaskControls? = null,
    editSelection: MaskSelection? = null,
    /** A drag finished: the stretch it selected, snapped/clamped, or null when it cleared it. */
    onEditSelection: ((MaskSelection?) -> Unit)? = null,
    rangeMinMgdl: Int? = null,
    rangeMaxMgdl: Int? = null,
    predictedClock: PredictedClock? = null,
    smoothed: SmoothedTrace? = null,
    showSmoothed: Boolean = false,
    /** A smoothed trace can be swapped in; [smoothed] is left unbuilt while hidden. */
    smoothable: Boolean = false,
    // Display-only; never drives an alert or a dose.
    rolled: RolledSeries? = null,
    // Extends the pannable right edge past now, without auto-following into that empty region.
    futureExtentMs: Long = 0L,
    // Layout only: holds auto-follow edge when a forecast exists undrawn; null = content decides.
    reservedEndMs: Long? = null,
    // Stored forecasts read back and swept by the scrub. Display-only. Null ⇒ nothing is drawn.
    hindsight: HindsightFrame? = null,
    // Freehand annotation layer, built off-thread; drawn under traces, clipped near the BG line.
    paint: PaintFrame? = null,
    // Non-null: one finger draws/erases, two+ pan/zoom. DECORATIVE: no rail ever reads a stroke.
    paintControls: PaintControls? = null,
    /** Fired once on lift-off; `id = 0`, the store mints the row id. */
    onPaintStroke: ((PaintStroke) -> Unit)? = null,
    /** Whole strokes only, so undo stays a stack. */
    onErasePaintStroke: ((Long) -> Unit)? = null,
    onScrub: ((GraphScrub?) -> Unit)? = null,
    /** Start instant and span, in ms; hoisted for drive mode, which adopts this viewport. */
    onViewportChange: ((startMs: Double, spanMs: Double) -> Unit)? = null,
    /** Where the RECORD begins, older than frame's first if the load was windowed; null = first. */
    domainFloorMs: Long? = null,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // One pass measures ~25 distinct strings, ~35 while scrubbing; the default cache holds eight.
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val haptics = LocalT1dmHaptics.current

    val leftPx = with(density) { GraphInsets.Left.toPx() }
    val rightPx = with(density) { GraphInsets.Right.toPx() }
    val topPx = with(density) { GraphInsets.top(predictedClock != null).toPx() }
    val bottomPx = with(density) { GraphInsets.Bottom.toPx() }

    // Held unconditionally: the empty-frame return below must not skip a `remember`.
    val corridor = remember { PaintCorridor() }
    val paintPath = remember { Path() }
    val dpPx = density.density
    val corridorPx = corridorWidthPx(dpPx)
    val chalkPens = remember(dpPx) { ChalkPens(dpPx) }

    // Hoisted so the draw phase, which re-runs at the display refresh rate, allocates none of this.
    val tracePath = remember { Path() }
    val overlayPaths = remember { CurveChannelPaths() }
    // One path for every visible bar: one `drawPath` a frame, not one `drawRect` a bucket.
    val stepBars = remember { StepBarPath() }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }
    /** Distinct from [dash]: a reconstruction and an interpolation are different claims. */
    val reconDash = remember { PathEffect.dashPathEffect(floatArrayOf(2f, 5f)) }
    val interpRingStroke = remember { Stroke(width = 1.4f) }
    val smoothedStroke = remember { Stroke(width = 2.2f, cap = StrokeCap.Round) }
    val scrubDotStroke = remember { Stroke(width = 2f) }
    // Reused by the fan's three bands and the rolled band; `reset()` retains capacity.
    val fanPath = remember { Path() }
    val rolledPath = remember { Path() }

    /** Its OWN, not `fanPath`'s: both are drawn in the same pass and would reset each other. */
    val reconPath = remember { Path() }
    val labelCache = remember { GraphLabelCache() }
    // One style: exactly one legend is ever drawn, since "smoothed" is a swap, not an overlay.
    val traceLegendStyle = remember(cs.primary) { TextStyle(color = cs.primary, fontSize = 9.sp) }
    // Where the fan ends, the roll hatch begins, one uncertainty; only from a painted fan series.
    val rolledSeam = remember(predictions) {
        predictions.lastOrNull { it.selected && !it.degenerate && !it.isEmpty }?.let { p ->
            val last = p.size - 1
            RolledSeam(p.tsMs[last], p.lo[0][last], p.hi[0][last])
        }
    }
    val scrubLabelStyle = remember(cs.onPrimary) {
        TextStyle(color = cs.onPrimary.copy(alpha = 0.72f), fontSize = 11.sp)
    }
    val scrubValueStyle = remember(cs.onPrimary) {
        TextStyle(color = cs.onPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    // From constant templates, so a scrub does not re-measure seven strings per pointer sample.
    val scrubMetrics = remember(measurer, scrubLabelStyle, scrubValueStyle) {
        val template = measurer.measure(SCRUB_VALUE_TEMPLATE, scrubValueStyle)
        ScrubMetrics(
            labelColW = SCRUB_LABELS.maxOf { measurer.measure(it, scrubLabelStyle).size.width }.toFloat(),
            valueColW = template.size.width.toFloat(),
            lineH = template.size.height.toFloat(),
        )
    }

    // Held unconditionally, like the paint scratch above; the feed is split/sorted ONCE here.
    val semantics = LocalT1dmSemantics.current
    val carbMarkPainter = rememberVectorPainter(logMarkerIcon(CurveKind.CARB))
    val insulinMarkPainter = rememberVectorPainter(logMarkerIcon(CurveKind.INSULIN))
    val exerciseMarkPainter = rememberVectorPainter(logMarkerIcon(CurveKind.EXERCISE))
    // The curve overlay's own inks: a mark is the colour of the curve it stands for.
    val carbInk = semantics.secondary
    val insulinInk = semantics.inRange
    // The one remaining chrome role. Not a glucose band, for the reason [stepsInk] gives below.
    val exerciseInk = semantics.primary
    // Deliberately not a semantic role: every role is spoken for; borrowing misreads as glucose.
    val stepsInk = remember(cs.onSurfaceVariant) { cs.onSurfaceVariant.copy(alpha = 0.38f) }
    val carbTint = remember(carbInk) { ColorFilter.tint(carbInk) }
    val insulinTint = remember(insulinInk) { ColorFilter.tint(insulinInk) }
    val exerciseTint = remember(exerciseInk) { ColorFilter.tint(exerciseInk) }
    val carbLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.CARB) }
    val insulinLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.INSULIN) }
    val exerciseLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.EXERCISE) }
    val markSepPx = logMarkerSeparationPx(dpPx)
    val markSizePx = LOG_MARKER_DP * dpPx

    // The buffer is plain memory; liveCount is snapshot state, invalidating only the DRAW phase.
    val live = remember { StrokeCapture() }
    var liveCount by remember { mutableIntStateOf(0) }
    var liveHeld by remember { mutableStateOf(false) }

    // Viewport in ABSOLUTE epoch-ms (stable across frame rebuilds whose t0 may shift).
    var viewStartMs by remember { mutableStateOf(Double.NaN) }
    var viewSpanMs by remember { mutableStateOf(initialWindowMin.toDouble() * 60_000.0) }
    val reportViewport by rememberUpdatedState(onViewportChange)
    LaunchedEffect(viewStartMs, viewSpanMs) {
        if (!viewStartMs.isNaN()) reportViewport?.invoke(viewStartMs, viewSpanMs)
    }

    var followLatest by remember { mutableStateOf(true) }
    // Absolute epoch-ms, NaN = inactive, so the cursor can land in the forecast zone.
    var scrubMs by remember { mutableStateOf(Double.NaN) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun plotW(): Double = (canvasSize.width - leftPx - rightPx).toDouble().coerceAtLeast(1.0)

    // Where AUTO-FOLLOW settles: last reading or furthest forecast step, never the empty future.
    fun followEndMs(): Double {
        val fe = if (frame.isEmpty) 0.0 else frame.absMs(frame.size - 1)
        val pe = predictions.maxTsMs()?.toDouble() ?: fe
        val re = rolled?.maxTsMs?.toDouble() ?: fe
        val se = reservedEndMs?.toDouble() ?: fe
        return maxOf(fe, pe, re, se)
    }

    // Furthest the viewport may be panned, so dose curves in the empty future are reachable.
    fun panEndMs(): Double =
        maxOf(followEndMs(), (System.currentTimeMillis() + futureExtentMs).toDouble())

    /** Oldest instant the viewport may reach; falls back to the first reading when unwindowed. */
    fun domainStartMs(): Double {
        val firstHeld = frame.absMs(0)
        val floor = domainFloorMs?.toDouble() ?: return firstHeld
        return minOf(floor, firstHeld)
    }

    fun spanBounds(): Pair<Double, Double> {
        val range = if (frame.isEmpty) 0.0 else panEndMs() - domainStartMs()
        val minSpan = 15.0 * 60_000.0
        val maxSpan = maxOf(range, initialWindowMin.toDouble() * 60_000.0) * 1.2
        return minSpan to maxSpan.coerceAtLeast(minSpan)
    }

    fun clamp() {
        if (frame.isEmpty) return
        val (minSpan, maxSpan) = spanBounds()
        val (start, span) = clampViewport(
            startMs = viewStartMs,
            spanMs = viewSpanMs,
            domainStartMs = domainStartMs(),
            panEndMs = panEndMs(),
            minSpanMs = minSpan,
            maxSpanMs = maxSpan,
        )
        viewStartMs = start
        viewSpanMs = span
    }

    // Keyed on paint mode ALONE: a re-key cancels an in-flight gesture mid freehand line.
    val paintOn = paintControls != null
    val maskOn = maskControls != null && !paintOn
    // Paint wins: it is the mode the user turned on most recently.
    val gestureMode = when {
        paintOn -> GraphGesture.PAINT
        maskOn -> GraphGesture.EDIT
        else -> GraphGesture.NAVIGATE
    }
    val maskCtl by rememberUpdatedState(maskControls)
    val selNow by rememberUpdatedState(editSelection)
    val emitSel by rememberUpdatedState(onEditSelection)

    // A selection snaps to whole patches, a tau sweep steps a ladder; both interpolate by jumping.
    var selTween by remember { mutableStateOf(SelectionTween(null, null)) }
    val selProgress = remember { Animatable(1f) }
    LaunchedEffect(editSelection) {
        val target = editSelection
        val drawnNow = lerpSelection(selTween.from, selTween.to, selProgress.value)
        if (target == null || drawnNow == null) {
            selTween = SelectionTween(target, target)
            selProgress.snapTo(1f)
            return@LaunchedEffect
        }
        selTween = SelectionTween(drawnNow, target)
        selProgress.snapTo(0f)
        selProgress.animateTo(1f, tween(SELECTION_TWEEN_MS, easing = FastOutSlowInEasing))
    }
    // Both interpolations resolve in DRAW: Animatable.value is snapshot state, read there only.

    var reconTween by remember { mutableStateOf(ReconTween(emptyList(), emptyList())) }
    val reconProgress = remember { Animatable(1f) }
    LaunchedEffect(reconstructed) {
        val target = reconstructed
        val drawnNow = lerpReconstruction(reconTween.from, reconTween.to, reconProgress.value)
        // Only a move is interpolated: crossing slot sets would draw through unspoken slots.
        if (!sameSlots(drawnNow, target)) {
            reconTween = ReconTween(target, target)
            reconProgress.snapTo(1f)
            return@LaunchedEffect
        }
        reconTween = ReconTween(drawnNow, target)
        reconProgress.snapTo(0f)
        reconProgress.animateTo(1f, tween(RECON_TWEEN_MS, easing = FastOutSlowInEasing))
    }

    val controls by rememberUpdatedState(paintControls)
    val paintNow by rememberUpdatedState(paint)
    val emitStroke by rememberUpdatedState(onPaintStroke)
    val eraseStroke by rememberUpdatedState(onErasePaintStroke)
    val plotBox by rememberUpdatedState(
        PlotBox(leftPx, topPx, canvasSize.width - rightPx, canvasSize.height - bottomPx),
    )

    // Rebuilt per PAN, not per frame; same reduction the tap hit-test uses, matching the screen.
    val plotRightPx = canvasSize.width - rightPx
    val insulinClusters = remember(insulinLane, viewStartMs, viewSpanMs, leftPx, plotRightPx, markSepPx) {
        if (viewStartMs.isNaN()) emptyList()
        else clusterLogMarkers(insulinLane.marks, viewStartMs, viewSpanMs, leftPx, plotRightPx, markSepPx)
    }
    val carbClusters = remember(carbLane, viewStartMs, viewSpanMs, leftPx, plotRightPx, markSepPx) {
        if (viewStartMs.isNaN()) emptyList()
        else clusterLogMarkers(carbLane.marks, viewStartMs, viewSpanMs, leftPx, plotRightPx, markSepPx)
    }
    val exerciseClusters = remember(exerciseLane, viewStartMs, viewSpanMs, leftPx, plotRightPx, markSepPx) {
        if (viewStartMs.isNaN()) emptyList()
        else clusterLogMarkers(exerciseLane.marks, viewStartMs, viewSpanMs, leftPx, plotRightPx, markSepPx)
    }

    // Read through rememberUpdatedState, so the handler tests against the current viewport.
    val markerTap by rememberUpdatedState(onMarkerTap)
    val hitMarkers by rememberUpdatedState<(Offset) -> List<Int>>({ pos ->
        hitTestLogMarkers(
            pos.x, pos.y, plotBox.left, plotBox.right, plotBox.bottom, dpPx,
            insulinLane, insulinClusters, carbLane, carbClusters, exerciseLane, exerciseClusters,
        )
    })

    // Edge-triggered so a held pan buzzes once; plain holder, not snapshot state (felt, not drawn).
    val atEdge = remember { booleanArrayOf(false) }

    /** The one implementation of the viewport math, shared by every mode. */
    val applyTransform by rememberUpdatedState<(Float, Float, Float) -> Unit>({ centroidX, panX, zoom ->
        val ppm = plotW() / viewSpanMs // px per ms
        if (zoom != 1f) {
            val focusMs = viewStartMs + (centroidX - leftPx) / ppm
            val (minSpan, maxSpan) = spanBounds()
            val newSpan = (viewSpanMs / zoom).coerceIn(minSpan, maxSpan)
            val frac = ((centroidX - leftPx) / plotW()).coerceIn(0.0, 1.0)
            viewSpanMs = newSpan
            viewStartMs = focusMs - frac * newSpan
        }
        viewStartMs -= panX / ppm
        val de = followEndMs()
        // What the gesture asked for: clamp rewrites both, leaving nothing to test a wall against.
        val wantedStart = viewStartMs
        val wantedSpan = viewSpanMs
        clamp()
        // isFinite guards before viewStartMs is seeded: NaN != NaN reads as a wall never met.
        val pinned = wantedStart.isFinite() &&
            (viewStartMs != wantedStart || viewSpanMs != wantedSpan)
        if (pinned && !atEdge[0]) haptics.perform(HapticEvent.EdgeStop)
        atEdge[0] = pinned
        followLatest = (viewStartMs + viewSpanMs) >= de - viewSpanMs * 0.02
    })

    // Grain is the SAMPLE not the pixel: raw position saturates the LRA; index ticks per reading.
    val scrubDetent = rememberHapticDetent(HapticEvent.ScrubTick)

    val scrubAt by rememberUpdatedState<(Float) -> Unit>({ x ->
        val ppm = plotW() / viewSpanMs
        val lastMs = if (frame.isEmpty) Double.NaN else frame.absMs(frame.size - 1)
        val ms = (viewStartMs + (x - leftPx) / ppm).coerceIn(frame.absMs(0), panEndMs())
        scrubDetent.at(
            if (!lastMs.isNaN() && ms > lastMs) "f" + ((ms - lastMs) / 300_000.0).toLong()
            else frame.nearestIndex(ms),
        )
        scrubMs = ms
        onScrub?.invoke(buildScrub(frame, predictions, curveOverlay, stepsFrame, predictedClock, rolled, ms))
    })
    val scrubEnd by rememberUpdatedState<() -> Unit>({
        haptics.perform(HapticEvent.DragEnd)
        scrubMs = Double.NaN
        onScrub?.invoke(null)
    })

    // Released when the layer containing it arrives; with no listener there is no twin to wait for.
    LaunchedEffect(paint) {
        if (liveHeld) {
            liveHeld = false
            liveCount = 0
        }
    }

    // Leaving paint mode drops an in-flight stroke; clear pixels too, or it lingers as a ghost.
    LaunchedEffect(paintOn) {
        if (!paintOn) {
            live.abandon()
            liveCount = 0
            liveHeld = false
        }
    }

    // Initialise on the first frame; keep tracking the latest point until the user scrolls back.
    LaunchedEffect(frame, predictions) {
        if (frame.isEmpty) return@LaunchedEffect
        val de = followEndMs()
        if (viewStartMs.isNaN() || followLatest) viewStartMs = de - viewSpanMs
        clamp()
    }

    // When a roll lands, pan right so its far edge shows, with ~1h of context before its anchor.
    LaunchedEffect(rolled) {
        if (rolled == null || rolled.isEmpty || frame.isEmpty) return@LaunchedEffect
        val end = rolled.maxTsMs!!.toDouble()
        val start = rolled.tsMs.first().toDouble() - 60.0 * 60_000.0
        viewSpanMs = (end - start).coerceAtLeast(initialWindowMin.toDouble() * 60_000.0)
        viewStartMs = end - viewSpanMs
        followLatest = false
        clamp()
    }

    // The window buttons drive [initialWindowMin]; a change resets the span and re-follows.
    LaunchedEffect(initialWindowMin) {
        viewSpanMs = initialWindowMin.toDouble() * 60_000.0
        followLatest = true
        if (!frame.isEmpty) { viewStartMs = followEndMs() - viewSpanMs; clamp() }
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
            // Registration order load-bearing: REVERSE dispatch (1.7.6) drops the first gesture.
            .pointerInput(gestureMode) {
                // One finger drags a span, two+ pan/zoom; horizontal only, a stretch of TIME.
                if (gestureMode == GraphGesture.EDIT) {
                    // Hand-written: drag detector consumes; transform aborts, edit stays pannable.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val c = maskCtl ?: return@awaitEachGesture
                        val sink = emitSel ?: return@awaitEachGesture
                        val box = plotBox
                        if (box.width <= 0f) return@awaitEachGesture
                        down.consume()

                        val slopPx = EDIT_DRAG_SLOP_DP * dpPx
                        val grabPx = EDIT_HANDLE_GRAB_DP * dpPx
                        val startX = down.position.x

                        fun tsAt(x: Float): Long =
                            (viewStartMs + (x - box.left) / (box.width / viewSpanMs)).toLong()

                        // An instant, not a pixel: viewport moves, misplacing a stale pixel read.
                        val fromTs = tsAt(startX)

                        // Opposite end anchors INCLUSIVELY: selectionOf grows the left resize.
                        var anchorTs = Long.MIN_VALUE
                        selNow?.let { sel ->
                            val ppmNow = box.width / viewSpanMs
                            val x0 = ((sel.startMs - viewStartMs) * ppmNow + box.left).toFloat()
                            val x1 = ((sel.endMs - viewStartMs) * ppmNow + box.left).toFloat()
                            val dLeft = kotlin.math.abs(startX - x0)
                            val dRight = kotlin.math.abs(startX - x1)
                            // Nearest handle wins: left-first wins under the grab radius.
                            if (dLeft <= grabPx || dRight <= grabPx) {
                                anchorTs = if (dLeft <= dRight) sel.endMs - c.patchMs else sel.startMs
                                haptics.perform(HapticEvent.Tap)
                            }
                        }

                        var mode = EditGesture.SELECT
                        var moved = anchorTs != Long.MIN_VALUE
                        var emitted: MaskSelection? = null
                        // Latched before the loop: selNow already holds what this gesture emitted.
                        val selAtStart = selNow

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed >= 2 && mode != EditGesture.TRANSFORM) {
                                // Put back what was selected; a pan leaves no stray stretch.
                                if (emitted != null) sink(selAtStart)
                                mode = EditGesture.TRANSFORM
                            }
                            when (mode) {
                                EditGesture.TRANSFORM -> {
                                    // No slop gate: a pan fights the finger on the selection.
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    if ((zoom != 1f || pan != Offset.Zero) && centroid.isSpecified) {
                                        applyTransform(centroid.x, pan.x, zoom)
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                EditGesture.SELECT -> {
                                    val ch = event.changes.firstOrNull { it.pressed } ?: break
                                    // Shorter than slop is a touch: selects nothing, grab exempt.
                                    if (!moved && kotlin.math.abs(ch.position.x - startX) >= slopPx) {
                                        moved = true
                                    }
                                    if (moved) {
                                        val anchor = if (anchorTs != Long.MIN_VALUE) anchorTs else fromTs
                                        val next = selectionOf(anchor, tsAt(ch.position.x), c)
                                        // Every move, not the lift: touch-up drags a handle blind.
                                        if (next != null && next != emitted) {
                                            emitted = next
                                            sink(next)
                                        }
                                    }
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                }
                            }
                        }
                        if (mode == EditGesture.SELECT && emitted != null) {
                            haptics.perform(HapticEvent.Commit)
                        }
                    }
                    return@pointerInput
                }
                if (!paintOn) {
                    coroutineScope {
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            // FIRST registered, LAST dispatched; empty feed misses at hit-test.
                            this@pointerInput.detectLogMarkerTaps { pos ->
                                val sink = markerTap ?: return@detectLogMarkerTaps
                                val hit = hitMarkers(pos)
                                if (hit.isEmpty()) return@detectLogMarkerTaps
                                haptics.perform(HapticEvent.Tap)
                                sink(hit)
                            }
                        }
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            this@pointerInput.detectTransformGestures { centroid, pan, zoom, _ ->
                                applyTransform(centroid.x, pan.x, zoom)
                            }
                        }
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            // Detent resets first so the landing sample seeds silently.
                            this@pointerInput.detectDragGesturesAfterLongPress(
                                onDragStart = { pos ->
                                    haptics.perform(HapticEvent.LongPress)
                                    scrubDetent.reset()
                                    scrubAt(pos.x)
                                },
                                onDrag = { change, _ -> scrubAt(change.position.x) },
                                onDragEnd = { scrubEnd() },
                                onDragCancel = { scrubEnd() },
                            )
                        }
                    }
                    return@pointerInput
                }

                // A constant reach hoists; the decimation gate scales with the pen and cannot.
                val erasePx = PAINT_ERASE_RADIUS_DP * dpPx

                // Per sample, not pen-down: viewport/inset move mid-stroke, shearing a capture.
                fun sampleTs(x: Float): Long {
                    val box = plotBox
                    return (viewStartMs + (x - box.left) / (box.width / viewSpanMs)).toLong()
                }
                fun sampleY(y: Float): Float {
                    val box = plotBox
                    return (y - box.top) / box.height
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // After down, never before: re-entry latches the last lift-off's palette.
                    val ctl = controls ?: return@awaitEachGesture
                    down.consume()

                    // Same reason: hoisted, this would be the last stroke's pen width.
                    val minStepPx = paintMinStepPx(ctl.widthDp, dpPx)

                    var mode = if (ctl.eraser) PaintGesture.ERASE else PaintGesture.DRAW
                    val erased = HashSet<Long>() // one callback per stroke, however long it lingers
                    var lastPos = down.position

                    fun eraseAt(pos: Offset) {
                        val pf = paintNow ?: return
                        if (pf.isEmpty) return
                        val box = plotBox
                        val id = hitTestPaint(
                            pf, pos.x, pos.y, erasePx,
                            viewStartMs, box.width / viewSpanMs, box.left, box.top, box.height, dpPx,
                        )
                        if (id != NO_STROKE && erased.add(id)) {
                            haptics.perform(HapticEvent.Reject)
                            eraseStroke?.invoke(id)
                        }
                    }

                    if (mode == PaintGesture.DRAW) {
                        live.begin(System.currentTimeMillis())
                        liveHeld = false
                        live.add(down.position.x, down.position.y, sampleTs(down.position.x), sampleY(down.position.y), 0f)
                        liveCount = live.size
                        haptics.perform(HapticEvent.StrokeStart)
                    } else {
                        eraseAt(down.position)
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2 && mode != PaintGesture.TRANSFORM) {
                            if (mode == PaintGesture.DRAW) {
                                live.abandon()
                                liveCount = 0
                            }
                            mode = PaintGesture.TRANSFORM
                        }
                        when (mode) {
                            PaintGesture.TRANSFORM -> {
                                // No slop gate: second finger declared intent; pan fights drawing.
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if ((zoom != 1f || pan != Offset.Zero) && centroid.isSpecified) {
                                    applyTransform(centroid.x, pan.x, zoom)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            PaintGesture.DRAW -> {
                                val ch = event.changes.firstOrNull { it.pressed } ?: break
                                lastPos = ch.position
                                if (live.add(ch.position.x, ch.position.y, sampleTs(ch.position.x), sampleY(ch.position.y), minStepPx)) {
                                    liveCount = live.size
                                }
                                event.changes.forEach { if (it.pressed) it.consume() }
                            }
                            PaintGesture.ERASE -> {
                                val ch = event.changes.firstOrNull { it.pressed } ?: break
                                eraseAt(ch.position)
                                event.changes.forEach { if (it.pressed) it.consume() }
                            }
                        }
                    }

                    if (mode == PaintGesture.DRAW) {
                        // Stroke ends where finger left glass, not the last min-distance sample.
                        if (live.addFinal(lastPos.x, lastPos.y, sampleTs(lastPos.x), sampleY(lastPos.y))) {
                            liveCount = live.size
                        }
                        val stroke = live.toStroke(ctl.tool, ctl.colorArgb, ctl.widthDp)
                        val sink = emitStroke
                        if (stroke != null && sink != null) {
                            haptics.perform(HapticEvent.StrokeEnd)
                            liveHeld = true // keep it on screen until its persisted twin lands
                            sink(stroke)
                        } else {
                            liveCount = 0
                        }
                    }
                }
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
            // Fold visible forecast points into the auto-fit so the overlay never clips.
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
            // Fold the visible roll in too, so its extrapolated tail never clips.
            rolled?.let { rs ->
                val vLo = viewStartMs; val vHi = viewStartMs + viewSpanMs
                for (i in 0 until rs.size) {
                    val t = rs.tsMs[i].toDouble()
                    if (t < vLo || t > vHi) continue
                    // The OUTERMOST pair alone bounds the fan; the inner ones sit inside it.
                    if (rs.lo[0][i] < yMin) yMin = rs.lo[0][i]
                    if (rs.hi[0][i] > yMax) yMax = rs.hi[0][i]
                }
            }
            // HINDSIGHT fan NOT folded in: a bad forecast would rescale the axis to a flat truth.
            if (!yMin.isFinite() || !yMax.isFinite()) { yMin = 0f; yMax = 1f }
            // Always covers the configured range and grows past it, on the risk axis through f.
            val fixed = if (rangeMinMgdl != null && rangeMaxMgdl != null) {
                val (railLo, railHi) = axisRailsMgdl(frame.unit, rangeMinMgdl, rangeMaxMgdl, thresholds)
                fixedYRange(yMin, yMax, frame.unit, railLo, railHi, kovatchevF)
            } else {
                null
            }
            if (fixed != null) {
                yMin = fixed.first; yMax = fixed.second
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

            val lineColor = cs.primary
            val interpColor = cs.primary.copy(alpha = 0.45f)
            // Not a glucose colour: a reconstruction is a model's statement about a slot.
            val reconColor = cs.onSurfaceVariant.copy(alpha = 0.55f)
            val warmupColor = cs.secondary
            // A SWAP, not an overlay: exactly one trace is ever on screen.
            val swapToSmoothed = showSmoothed && smoothed != null && !smoothed.isEmpty

            // Extracted so drive mode can render the same furniture around its own viewport.
            drawGraphFurniture(
                unit = frame.unit,
                kovatchevF = kovatchevF,
                tzOffsetMin = frame.tzOffsetMin,
                plotLeft = plotLeft, plotTop = plotTop, plotRight = plotRight, plotBottom = plotBottom,
                viewStartMs = viewStartMs, viewSpanMs = viewSpanMs,
                yMin = yMin, yMax = yMax,
                thresholds = thresholds,
                predictedClock = predictedClock,
                measurer = measurer,
                cs = cs,
                labels = labelCache,
            )

            // Data is clipped to the plot; grid, axes and margin captions stay outside it.
            clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
                // First inside the clip: paint masks out a corridor around whichever trace draws.
                val livePoints = liveCount // DRAW snapshot: a sample redraws, never recomposes
                val committed = paint != null && !paint.isEmpty
                if (committed || livePoints > 0) {
                    fun strokes() {
                        if (committed) {
                            drawPaintFrame(
                                paint!!, viewStartMs, viewSpanMs, ppm, plotLeft, plotTop, plotHeight, dpPx,
                                paintPath, chalkPens,
                            )
                        }
                        val ctl = paintControls
                        if (livePoints > 0 && ctl != null) {
                            drawLiveStroke(
                                live, livePoints, PaintFrame.toolIdOf(ctl.tool), ctl.colorArgb, ctl.widthDp,
                                viewStartMs, ppm, plotLeft, plotTop, plotHeight, dpPx, paintPath, chalkPens,
                            )
                        }
                    }
                    val traceId = System.identityHashCode(if (swapToSmoothed) smoothed else frame)
                    if (
                        corridor.stale(
                            traceId, swapToSmoothed, viewStartMs, viewSpanMs, yMin, yMax,
                            plotLeft, plotTop, plotRight, plotBottom, corridorPx,
                        )
                    ) {
                        corridor.begin()
                        if (swapToSmoothed) {
                            val sm = smoothed!!
                            // Trace loop culls to one span; corridor masks only what is on screen.
                            var sLo = lowerBoundLong(sm.tsMs, viewStartMs.toLong()) - 1
                            var sHi = lowerBoundLong(sm.tsMs, (viewStartMs + viewSpanMs).toLong())
                            if (sLo < 0) sLo = 0
                            if (sHi > sm.size - 1) sHi = sm.size - 1
                            corridor.append(
                                sLo, sHi,
                                { sm.breakAfter[it] },
                                { (plotLeft + (sm.tsMs[it].toDouble() - viewStartMs) * ppm).toFloat() },
                                { yToPx(sm.ys[it]) },
                            )
                        } else {
                            corridor.append(
                                iLo, iHi, { frame.breakAfter[it] }, { xToPx(frame.xs[it]) }, { yToPx(frame.ys[it]) },
                            )
                        }
                        corridor.commit(corridorPx)
                    }
                    val mask = corridor.mask
                    if (mask == null) strokes() else clipPath(mask, ClipOp.Difference) { strokes() }
                }

                // Steps first of three layers: bars are context, under the curves.
                if (stepsFrame != null && curveToggles.exercise && !stepsFrame.isEmpty) {
                    fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                    drawStepsBars(
                        stepsFrame, AbsToPx(::absToPx),
                        bandTop = plotBottom - plotHeight * 0.30f, plotBottom = plotBottom,
                        color = stepsInk,
                        viewStartMs = viewStartMs, viewSpanMs = viewSpanMs,
                        dpPx = dpPx, bars = stepBars,
                    )
                }

                // Carb Ra + insulin action in the bottom band, UNDER the BG line.
                if (curveOverlay != null && curveToggles.any) {
                    fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                    val bandTop = plotBottom - plotHeight * 0.30f
                    drawCurveOverlay(
                        curveOverlay, curveToggles, AbsToPx(::absToPx), bandTop, plotBottom,
                        carbColor = carbInk, insulinColor = insulinInk, exerciseColor = exerciseInk,
                        // Viewport bounds the draw itself: the channels span ~14 days of buckets.
                        viewStartMs = viewStartMs, viewSpanMs = viewSpanMs,
                        paths = overlayPaths,
                    )
                }

                // Inside the clip so lanes pan with data, BEFORE the BG trace.
                drawLogMarkers(
                    insulinClusters,
                    painter = insulinMarkPainter,
                    tint = insulinTint,
                    sizePx = markSizePx,
                    laneTopY = logMarkerLaneTop(CurveKind.INSULIN, plotBottom, dpPx),
                )
                drawLogMarkers(
                    carbClusters,
                    painter = carbMarkPainter,
                    tint = carbTint,
                    sizePx = markSizePx,
                    laneTopY = logMarkerLaneTop(CurveKind.CARB, plotBottom, dpPx),
                )
                drawLogMarkers(
                    exerciseClusters,
                    painter = exerciseMarkPainter,
                    tint = exerciseTint,
                    sizePx = markSizePx,
                    laneTopY = logMarkerLaneTop(CurveKind.EXERCISE, plotBottom, dpPx),
                )

                // DISPLAY ONLY — nothing drawn here is stored.
                lerpSelection(selTween.from, selTween.to, selProgress.value)?.let { s ->
                    val x0 = ((s.startMs - viewStartMs) * ppm + plotLeft).toFloat()
                    val x1 = ((s.endMs - viewStartMs) * ppm + plotLeft).toFloat()
                    if (x1 >= plotLeft && x0 <= plotRight) {
                        drawRect(
                            cs.primary.copy(alpha = 0.14f),
                            topLeft = Offset(maxOf(x0, plotLeft), plotTop),
                            size = Size(minOf(x1, plotRight) - maxOf(x0, plotLeft), plotHeight),
                        )
                        // Full-height, so the grab target is the whole edge rather than a dot.
                        val w = EDIT_HANDLE_W_DP * dpPx
                        for (x in listOf(x0, x1)) {
                            if (x < plotLeft - w || x > plotRight + w) continue
                            drawRect(
                                cs.primary.copy(alpha = 0.85f),
                                topLeft = Offset(x - w / 2f, plotTop),
                                size = Size(w, plotHeight),
                            )
                        }
                    }
                }
                // Kovatchev is a RISK axis: without the transform, a reconstruction lands off-plot.
                val recon = lerpReconstruction(reconTween.from, reconTween.to, reconProgress.value)
                val reconToAxis = mgdlToAxis(frame.unit, kovatchevF)
                if (recon.isNotEmpty() && reconToAxis != null) {
                    drawReconstruction(
                        rows = recon,
                        xOf = { ts -> ((ts - viewStartMs) * ppm + plotLeft).toFloat() },
                        // The frame's own unit, through the same transforms the trace used.
                        yOf = { mgdl -> yToPx(reconToAxis(mgdl.toFloat())) },
                        // The forecast's own ink: a fill and a forecast are one artifact.
                        ink = cs.tertiary,
                        fanColor = cs.tertiary,
                        plotLeft = plotLeft,
                        plotRight = plotRight,
                        // Bracketing slot in px; null where nothing draws (a fill's far end).
                        anchorPxAt = { ts ->
                            val i = frame.nearestIndex(ts.toDouble())
                            when {
                                i < 0 || kotlin.math.abs(frame.absMs(i) - ts) > GRID_HALF_MS -> null
                                // Never onto another recon: pinching implies a known meeting point.
                                frame.flags[i] == GraphFrame.FLAG_RECONSTRUCTED -> null
                                else -> yToPx(frame.ys[i])
                            }
                        },
                        scratch = reconPath,
                    )
                }

                // BG polyline, styled by provenance; gaps broken.
                if (!swapToSmoothed) for (i in iLo until iHi) {
                    if (frame.breakAfter[i]) continue
                    val fa = frame.flags[i]
                    val fb = frame.flags[i + 1]
                    // Two selections, not a Pair: destructuring boxed Color per segment each frame.
                    val warm = fa == GraphFrame.FLAG_WARMUP || fb == GraphFrame.FLAG_WARMUP
                    val interp = fa == GraphFrame.FLAG_INTERPOLATED || fb == GraphFrame.FLAG_INTERPOLATED
                    // Markers suppress at 6h+; unflagged recon draws as an unbroken measured trace.
                    val recon = fa == GraphFrame.FLAG_RECONSTRUCTED || fb == GraphFrame.FLAG_RECONSTRUCTED
                    val col = when {
                        warm -> warmupColor
                        interp -> interpColor
                        recon -> reconColor
                        else -> lineColor
                    }
                    val effect = if (warm || interp) dash else if (recon) reconDash else null
                    drawLine(
                        col,
                        Offset(xToPx(frame.xs[i]), yToPx(frame.ys[i])),
                        Offset(xToPx(frame.xs[i + 1]), yToPx(frame.ys[i + 1])),
                        strokeWidth = 2.2f, cap = StrokeCap.Round, pathEffect = effect,
                    )
                }

                // Point markers only when uncluttered, so the distinctions stay legible.
                if (!swapToSmoothed && iHi - iLo <= 240) {
                    val r = 2.6f
                    for (i in iLo..iHi) {
                        val c = Offset(xToPx(frame.xs[i]), yToPx(frame.ys[i]))
                        when (frame.flags[i]) {
                            GraphFrame.FLAG_WARMUP -> drawCircle(warmupColor, r, c)
                            GraphFrame.FLAG_INTERPOLATED ->
                                drawCircle(interpColor, r, c, style = interpRingStroke)
                            GraphFrame.FLAG_RECONSTRUCTED ->
                                drawCircle(reconColor, r, c, style = interpRingStroke)
                            else -> drawCircle(lineColor, r, c)
                        }
                    }
                }

                // Causal Savitzky-Golay, mg/dL pre-risk; replaces raw trace, breaks honoured.
                if (swapToSmoothed) {
                    val sm = smoothed!!
                    val smColor = lineColor // it stands in for the raw trace
                    val path = tracePath
                    path.reset()
                    var open = false
                    fun flush() { if (open) { drawPath(path, smColor, style = smoothedStroke); path.reset(); open = false } }
                    for (i in sm.visibleRange(viewStartMs, viewSpanMs)) {
                        val x = (plotLeft + (sm.tsMs[i].toDouble() - viewStartMs) * ppm).toFloat()
                        val y = yToPx(sm.ys[i])
                        if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
                        if (i < sm.size - 1 && sm.breakAfter[i]) flush()
                    }
                    flush()
                    val leg = measurer.measure("sensor — smoothed", traceLegendStyle)
                    drawText(leg, topLeft = Offset((plotRight - leg.size.width - 4f).coerceAtLeast(plotLeft), plotTop + 2f))
                } else if (smoothable || (smoothed != null && !smoothed.isEmpty)) {
                    // Label the raw trace, so the toggle's state is legible.
                    val leg = measurer.measure("sensor — raw", traceLegendStyle)
                    drawText(leg, topLeft = Offset((plotRight - leg.size.width - 4f).coerceAtLeast(plotLeft), plotTop + 2f))
                }

                // Drawn BEFORE live overlay: in-force forecast on top, second accent, distinct hue.
                hindsight?.let { hf ->
                    if (!scrubMs.isNaN() && !hf.isEmpty) {
                        val c = hf.cycleAt(scrubMs)
                        if (c >= 0) {
                            fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                            drawHindsightFan(
                                hf, c, AbsToPx(::absToPx), ValToPx(::yToPx),
                                cs.secondary, cs.secondary, fanPath,
                            )
                        }
                    }
                }

                if (predictions.isNotEmpty()) {
                    fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                    val predLine = cs.tertiary
                    val fan = cs.tertiary
                    val flag = cs.error
                    for (s in predictions) if (!s.selected) {
                        drawPredSeries(s, AbsToPx(::absToPx), ValToPx(::yToPx), plotTop, plotBottom, predLine, fan, flag, fanPath)
                    }
                    for (s in predictions) if (s.selected) {
                        drawPredSeries(s, AbsToPx(::absToPx), ValToPx(::yToPx), plotTop, plotBottom, predLine, fan, flag, fanPath)
                    }
                }

                // Display-only by TYPE: calc rejects RolledForecast; the cycle's forecast re-fed.
                rolled?.let { rs ->
                    fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                    drawRolledSeries(
                        rs, AbsToPx(::absToPx), ValToPx(::yToPx),
                        cs.tertiary, cs.tertiary, rolledSeam, rolledPath,
                    )
                }

                // Read-out box PINNED at right-middle, not floating by the thumb; never occluded.
                if (!scrubMs.isNaN()) {
                    val cx = (plotLeft + (scrubMs - viewStartMs) * ppm).toFloat()
                    if (cx in plotLeft..plotRight) {
                        val sc = buildScrub(frame, predictions, curveOverlay, stepsFrame, predictedClock, rolled, scrubMs)
                        drawLine(cs.onSurface.copy(alpha = 0.5f), Offset(cx, plotTop), Offset(cx, plotBottom), 1f)
                        sc.bgValue?.let { drawCircle(cs.onSurface, 4f, Offset(cx, yToPx(it)), style = scrubDotStroke) }
                        // Columns size from fixed templates; box size holds as the thumb moves.
                        val rows = scrubRows(sc)
                        val padH = 9f; val padV = 8f; val colGap = 14f; val rowGap = 5f
                        val labelColW = scrubMetrics.labelColW
                        val valueColW = scrubMetrics.valueColW
                        val lineH = scrubMetrics.lineH
                        val boxW = padH + labelColW + colGap + valueColW + padH
                        val boxH = padV * 2f + lineH * rows.size + rowGap * (rows.size - 1)
                        val bx = (plotRight - boxW - 6f).coerceAtLeast(plotLeft)
                        // coerceIn THROWS if max<min; a too-tall box pushes bottom below plotTop.
                        val by = ((plotTop + plotBottom) / 2f - boxH / 2f)
                            .coerceIn(plotTop, (plotBottom - boxH).coerceAtLeast(plotTop))
                        drawRoundRect(
                            cs.primary,
                            topLeft = Offset(bx, by),
                            size = androidx.compose.ui.geometry.Size(boxW, boxH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f),
                        )
                        val valueRight = bx + boxW - padH // the value column's shared right edge
                        rows.forEachIndexed { i, (label, value) ->
                            val rowTop = by + padV + i * (lineH + rowGap)
                            val lbl = measurer.measure(label, scrubLabelStyle)
                            val vm = measurer.measure(value, scrubValueStyle)
                            drawText(lbl, topLeft = Offset(bx + padH, rowTop))
                            drawText(vm, topLeft = Offset(valueRight - vm.size.width, rowTop))
                        }
                    }
                }
            }
        }
    }
}

/** BG: past readings, model median in the prediction zone, or the roll past validated forecast. */
internal fun buildScrub(
    frame: GraphFrame,
    predictions: List<PredSeries>,
    overlay: CurveOverlayFrame?,
    stepsFrame: StepsFrame?,
    clock: PredictedClock?,
    rolled: RolledSeries?,
    ms: Double,
): GraphScrub {
    val lastFrameMs = if (frame.isEmpty) Long.MIN_VALUE else frame.absMs(frame.size - 1).toLong()
    val inPred = ms > lastFrameMs
    // One cascade so provenance travels with the number; validated-roll steps are unmarked, plain.
    var bg: Float? = null
    var extrapolated = false
    if (!inPred && !frame.isEmpty) {
        val i = frame.drawnIndexAt(ms)
        if (i >= 0) bg = frame.ys[i]
    } else {
        bg = selectedMedianAt(predictions, ms)
        if (bg == null && rolled != null) {
            val i = rolled.nearestIndex(ms)
            if (i >= 0) {
                bg = rolled.median[i]
                extrapolated = rolled.extrapolatedAt(i)
            }
        }
    }
    val carb = overlay?.carbAt(ms.toLong())?.takeIf { overlay.carbMax > 0f }
    val insulin = overlay?.insulinAt(ms.toLong())?.takeIf { overlay.insulinMax > 0f }
    val exercise = overlay?.exerciseAt(ms.toLong())?.takeIf { overlay.exerciseMax > 0f }
    val modelHour = clock?.let { predictedHourAt(ms.toLong(), it) }
    return GraphScrub(
        tsMs = ms.toLong(),
        tzOffsetMin = frame.tzOffsetMin,
        bgValue = bg,
        inPredZone = inPred,
        bgExtrapolated = extrapolated,
        carbRate = carb,
        insulinRate = insulin,
        exerciseRate = exercise,
        // Read regardless of DRAWN state; unmeasured bucket reads 0, keeping the box a fixed shape.
        steps = stepsFrame?.let { it.stepsAt(ms.toLong()) ?: 0 },
        modelHour = modelHour,
        unit = frame.unit,
    )
}

/** Null past validated horizon (bounded by nearestWithinHalfStep, like the rolled lookup). */
private fun selectedMedianAt(predictions: List<PredSeries>, ms: Double): Float? {
    val s = predictions.firstOrNull { it.selected && !it.degenerate && !it.stale } ?: return null
    val i = nearestWithinHalfStep(s.tsMs, ms)
    return if (i < 0) null else s.median[i]
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

/** Label column sizes from the widest of these, so the box never resizes under the cursor. */
private val SCRUB_LABELS = listOf("BG", "Carb", "Ins", "Exr", "Steps", "Local", "Model")

/** Widest value the right column can hold: a carb/insulin rate beats any BG or clock value. */
private const val SCRUB_VALUE_TEMPLATE = "199.9 g"

/** (label, value) pairs; only the rows that exist are emitted. Values carry their unit. */
internal fun scrubRows(sc: GraphScrub): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>(5)
    // "*" marks prediction zone; a rolled step isn't distinguished, calc can't accept its type.
    val mark = if (sc.inPredZone) "*" else ""
    val bgStr = sc.bgValue?.let { formatValue(it, sc.unit) + mark } ?: "--"
    out.add("BG" to bgStr)
    sc.carbRate?.let { out.add("Carb" to "%.1f g".format(it)) }
    sc.insulinRate?.let { out.add("Ins" to "%.2f U".format(it)) }
    sc.exerciseRate?.let { out.add("Exr" to "%.1f g".format(it)) }
    sc.steps?.let { out.add("Steps" to it.toString()) }
    out.add("Local" to formatClock(sc.tsMs, sc.tzOffsetMin))
    sc.modelHour?.let {
        val hh = floor(it).toInt(); val mm = ((it - hh) * 60.0).roundToInt().coerceIn(0, 59)
        out.add("Model" to "%02d:%02d".format(hh % 24, mm))
    }
    return out
}

/** A mg/dL chrome value onto the drawn axis; null when the risk axis has no `f` to place it. */
internal fun mgdlToAxis(unit: UnitSpace, kovatchevF: ((Double) -> Double)?): ((Float) -> Float)? {
    val f = kovatchevF
    return when (unit) {
        UnitSpace.MgDl -> ({ v: Float -> v })
        UnitSpace.MmolL -> ({ v: Float -> (v / 18.0182).toFloat() })
        UnitSpace.Kovatchev -> if (f == null) null else ({ v: Float -> f(v.toDouble()).toFloat() })
    }
}

/** Axis rails in mg/dL. f is steep past the urgent bands, so they bound the risk axis instead. */
internal fun axisRailsMgdl(
    unit: UnitSpace,
    rangeMinMgdl: Int,
    rangeMaxMgdl: Int,
    thresholds: AlertThresholds?,
): Pair<Int, Int> {
    if (unit != UnitSpace.Kovatchev || thresholds == null) return rangeMinMgdl to rangeMaxMgdl
    val lo = maxOf(rangeMinMgdl, thresholds.urgentLowMgdl)
    return lo to maxOf(lo + 1, minOf(rangeMaxMgdl, thresholds.urgentHighMgdl))
}

/** Covers rangeMinMgdl..rangeMaxMgdl in unit, grows for data beyond, rounds to a tick. */
internal fun fixedYRange(
    dataYMin: Float,
    dataYMax: Float,
    unit: UnitSpace,
    rangeMinMgdl: Int,
    rangeMaxMgdl: Int,
    kovatchevF: ((Double) -> Double)? = null,
): Pair<Float, Float>? {
    val toAxis = mgdlToAxis(unit, kovatchevF) ?: return null
    val rLo = toAxis(rangeMinMgdl.toFloat())
    val rHi = toAxis(rangeMaxMgdl.toFloat())
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

/** Smallest visible span, so a near-flat trace still fills the plot. */
internal fun minValueSpan(unit: UnitSpace): Float = when (unit) {
    UnitSpace.MgDl -> 40f
    UnitSpace.MmolL -> 2.2f
    UnitSpace.Kovatchev -> 0.6f
}

private fun DrawScope.drawBands(
    t: AlertThresholds, unit: UnitSpace, kovatchevF: ((Double) -> Double)?,
    left: Float, right: Float,
    yToPx: (Float) -> Float, yMin: Float, yMax: Float, urgent: Color, warn: Color,
) {
    // Fails closed: no `f` on the risk axis draws no band, never a band at a mg/dL number.
    val conv = mgdlToAxis(unit, kovatchevF) ?: return
    fun band(loV: Float, hiV: Float, color: Color) {
        val a = yToPx(hiV.coerceIn(yMin, yMax))
        val b = yToPx(loV.coerceIn(yMin, yMax))
        if (b - a > 0.5f) drawRect(color, topLeft = Offset(left, a), size = androidx.compose.ui.geometry.Size(right - left, b - a))
    }
    band(yMin, conv(t.urgentLowMgdl.toFloat()), urgent.copy(alpha = 0.10f))
    band(conv(t.urgentLowMgdl.toFloat()), conv(t.lowMgdl.toFloat()), warn.copy(alpha = 0.08f))
    band(conv(t.highMgdl.toFloat()), conv(t.urgentHighMgdl.toFloat()), warn.copy(alpha = 0.08f))
    band(conv(t.urgentHighMgdl.toFloat()), yMax, urgent.copy(alpha = 0.10f))
}

/** First index >= [target]; [xs] must be ascending. */
internal fun lowerBoundLong(xs: LongArray, target: Long): Int {
    var lo = 0
    var hi = xs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (xs[mid] < target) lo = mid + 1 else hi = mid
    }
    return lo
}

/** Nearest entry to ms, or -1 past half a step out; THE nearest-step rule for scrub overlays. */
internal fun nearestWithinHalfStep(tsMs: LongArray, ms: Double): Int {
    val n = tsMs.size
    if (n == 0) return -1
    val half = if (n >= 2) kotlin.math.abs(tsMs[1] - tsMs[0]) / 2.0 else 0.0
    if (ms < tsMs[0] - half || ms > tsMs[n - 1] + half) return -1
    // The two straddling candidates (ceil keeps the upper one at/above ms), then the nearer.
    val hi = lowerBoundLong(tsMs, kotlin.math.ceil(ms).toLong()).coerceIn(0, n - 1)
    val lo = (hi - 1).coerceAtLeast(0)
    return if (kotlin.math.abs(tsMs[lo] - ms) <= kotlin.math.abs(tsMs[hi] - ms)) lo else hi
}

/** First index >= [target]; [xs] must be ascending. */
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

internal fun formatValue(v: Float, unit: UnitSpace): String = when (unit) {
    UnitSpace.MgDl -> v.roundToInt().toString()
    UnitSpace.MmolL -> "%.1f".format(v)
    UnitSpace.Kovatchev -> "%.2f".format(v)
}

private fun zoneOf(tzOffsetMin: Int) = ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)
private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)
private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)

private fun formatTime(ms: Long, tzOffsetMin: Int, stepMs: Long): String {
    val odt = Instant.ofEpochMilli(ms).atOffset(zoneOf(tzOffsetMin))
    return if (stepMs >= 720L * 60_000L) odt.format(MMDD) else odt.format(HHMM)
}

/** English rather than the device locale: the ordinal suffix has no analogue outside it. */
internal fun formatAxisDate(ms: Long, tzOffsetMin: Int): String {
    val d = Instant.ofEpochMilli(ms).atOffset(zoneOf(tzOffsetMin))
    return "${d.format(MONTH)} ${d.dayOfMonth}${ordinalSuffix(d.dayOfMonth)}, ${d.format(WEEKDAY)}"
}

/** The teens take `th` whatever their last digit claims. */
private fun ordinalSuffix(day: Int): String = when {
    day / 10 == 1 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}

internal fun formatClock(ms: Long, tzOffsetMin: Int): String =
    Instant.ofEpochMilli(ms).atOffset(zoneOf(tzOffsetMin)).format(HHMM)

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
            logMarkers = syntheticMarkers(),
        )
    }
}

private fun syntheticMarkers(): List<LogMarker> {
    val t0 = 1_720_000_000_000L
    val step = 300_000L
    return listOf(
        LogMarker(t0 + 20 * step, CurveKind.CARB),
        LogMarker(t0 + 62 * step, CurveKind.CARB),
        LogMarker(t0 + 62 * step, CurveKind.INSULIN),
        LogMarker(t0 + 100 * step, CurveKind.INSULIN),
        LogMarker(t0 + 101 * step, CurveKind.INSULIN),
    )
}

/** Deterministic, with a warm-up head and one interpolated run. */
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

/** Everything that frames the plot, not data; hill-climb mode reuses this same frame. */
fun DrawScope.drawGraphFurniture(
    unit: UnitSpace,
    /** mg/dL -> Kovatchev risk; null on the risk axis draws no threshold band. */
    kovatchevF: ((Double) -> Double)? = null,
    tzOffsetMin: Int,
    plotLeft: Float,
    plotTop: Float,
    plotRight: Float,
    plotBottom: Float,
    viewStartMs: Double,
    viewSpanMs: Double,
    yMin: Float,
    yMax: Float,
    thresholds: AlertThresholds?,
    predictedClock: PredictedClock?,
    measurer: TextMeasurer,
    cs: ColorScheme,
    labels: GraphLabelCache,
) {
    val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
    val ppm = (plotRight - plotLeft).toDouble().coerceAtLeast(1.0) / viewSpanMs
    val ppv = plotHeight / (yMax - yMin)
    fun yToPx(v: Float): Float = plotBottom - (v - yMin) * ppv

    val gridColor = cs.onSurface.copy(alpha = 0.10f)
    val axisColor = cs.onSurface.copy(alpha = 0.30f)
    val labelColor = cs.onSurface.copy(alpha = 0.65f)
    val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)

        thresholds?.let {
            drawBands(it, unit, kovatchevF, plotLeft, plotRight, ::yToPx, yMin, yMax, cs.error, cs.secondary)
        }

        val vStep = niceStep((yMax - yMin).toDouble() / 5.0)
        var vy = floor(yMin / vStep) * vStep
        while (vy <= yMax + 1e-6) {
            val py = yToPx(vy.toFloat())
            if (vy >= yMin && py in plotTop..plotBottom) {
                drawLine(gridColor, Offset(plotLeft, py), Offset(plotRight, py), 1f)
                val vf = vy.toFloat()
                val label = measurer.measure(labels.value(vf, unit.ordinal) { formatValue(vf, unit) }, labelStyle)
                drawText(label, topLeft = Offset(plotLeft - 6f - label.size.width, py - label.size.height / 2f))
            }
            vy += vStep
        }

        // TOP axis carries the predicted clock if present, else "model time n/a", never fabricated.
        val tStepMs = niceTimeStepMs(viewSpanMs)
        val tzMs = tzOffsetMin * 60_000L
        var tick = floor((viewStartMs + tzMs) / tStepMs) * tStepMs - tzMs
        if (tick < viewStartMs) tick += tStepMs
        val endMs = viewStartMs + viewSpanMs
        val modelLabelColor = cs.tertiary.copy(alpha = 0.8f)
        val modelStyle = TextStyle(color = modelLabelColor, fontSize = 10.sp)
        // Labels are sp-scaled, reservation isn't; past ~1.15 scale a label outgrows its strip.
        val modelTopPx = plotTop - GraphInsets.ModelAxis.toPx()
        while (tick <= endMs) {
            val px = (plotLeft + (tick - viewStartMs) * ppm).toFloat()
            drawLine(gridColor, Offset(px, plotTop), Offset(px, plotBottom), 1f)
            val tms = tick.toLong()
            val label = measurer.measure(labels.time(tms, tzOffsetMin, tStepMs) { formatTime(tms, tzOffsetMin, tStepMs) }, labelStyle)
            var lx = px - label.size.width / 2f
            lx = lx.coerceIn(plotLeft, plotRight - label.size.width)
            drawText(label, topLeft = Offset(lx, plotBottom + 3f))
            if (predictedClock != null) {
                val cms = tick.toLong()
                val mlbl = measurer.measure(labels.clock(cms, predictedClock) { predictedClockLabel(cms, predictedClock) }, modelStyle)
                var mlx = px - mlbl.size.width / 2f
                mlx = mlx.coerceIn(plotLeft, plotRight - mlbl.size.width)
                drawText(mlbl, topLeft = Offset(mlx, (plotTop - mlbl.size.height - 2f).coerceAtLeast(modelTopPx)))
            }
            tick += tStepMs
        }
        // One label per day, drawn only where its span fits it; suppressed once ticks are dates.
        if (tStepMs < 720L * 60_000L) {
            val dateStyle = TextStyle(color = labelColor, fontSize = 9.sp)
            val dateTop = plotBottom + 3f + measurer.measure("00:00", labelStyle).size.height + 1f
            // Strip is dp, labels are sp; past ~1.4 font scale pin the row to the strip's floor.
            val stripFloor = plotBottom + GraphInsets.Bottom.toPx()
            val dayMs = 86_400_000L
            var dayStart = floor((viewStartMs + tzMs) / dayMs) * dayMs - tzMs
            while (dayStart < endMs) {
                val visFrom = dayStart.coerceAtLeast(viewStartMs)
                val visTo = (dayStart + dayMs).coerceAtMost(endMs)
                // Any instant inside the day names it; local noon is the one no offset pushes out.
                val dms = (dayStart + dayMs / 2).toLong()
                val lbl = measurer.measure(labels.date(dms, tzOffsetMin) { formatAxisDate(dms, tzOffsetMin) }, dateStyle)
                val spanPx = ((visTo - visFrom) * ppm).toFloat()
                if (spanPx >= lbl.size.width) {
                    val cx = (plotLeft + ((visFrom + visTo) / 2.0 - viewStartMs) * ppm).toFloat()
                    val y = dateTop.coerceAtMost(stripFloor - lbl.size.height)
                    drawText(lbl, topLeft = Offset(cx - lbl.size.width / 2f, y))
                }
                dayStart += dayMs
            }
        }

        val tzCap = measurer.measure(tzLabel(tzOffsetMin), TextStyle(color = axisColor, fontSize = 8.sp))
        drawText(tzCap, topLeft = Offset(2f, plotBottom + 3f))
        if (predictedClock != null) {
            val tag = measurer.measure("model", TextStyle(color = modelLabelColor, fontSize = 8.sp))
            drawText(tag, topLeft = Offset(2f, (plotTop - tag.size.height - 2f).coerceAtLeast(0f)))
        } else {
            val na = measurer.measure("model time n/a", TextStyle(color = axisColor, fontSize = 9.sp))
            drawText(na, topLeft = Offset(plotLeft + 4f, plotTop + 2f))
        }

        drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), 1.5f)
        drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), 1.5f)

}

// A drag shorter than this selects nothing; a stray contact used to select and reconstruct a patch.
private const val EDIT_DRAG_SLOP_DP = 16f

/** How far either side of a selection edge counts as grabbing that handle. */
private const val EDIT_HANDLE_GRAB_DP = 20f

/** Narrower than its grab target: the bar marks the edge, the target is what the thumb hits. */
private const val EDIT_HANDLE_W_DP = 3f

/** Short enough that a drag reads as the edge pushed, long enough to smooth a patch jump. */
private const val SELECTION_TWEEN_MS = 130

/** The τ ladder has 17 stops, so a sweep across it is a run of these rather than one. */
private const val RECON_TWEEN_MS = 110

/** Half a grid step. Further than this from a slot is a point at some other slot. */
private const val GRID_HALF_MS = 150_000.0
