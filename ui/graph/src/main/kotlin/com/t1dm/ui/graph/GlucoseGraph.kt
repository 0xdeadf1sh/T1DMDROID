package com.t1dm.ui.graph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.LogState
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
     *  model's median in the prediction zone, the DISPLAY-ONLY rolled forecast where no validated one
     *  reaches, or null when none of the three is available. */
    val bgValue: Float?,
    val inPredZone: Boolean,
    /** True when [bgValue] was taken from the rolled forecast's EXTRAPOLATED tail rather than a reading
     *  or a validated forecast. The read-out must mark it: every other surface of that region is
     *  hatched, dashed, bounded and captioned, and this row would otherwise be the one place a
     *  compounding self-fed number is presented exactly like a validated one. */
    val bgExtrapolated: Boolean,
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
 * What the panel is holding while PAINT MODE is on: which implement, in what colour and width, or
 * whether the finger is an [eraser] instead. A null `paintControls` on [GlucoseGraph] means paint mode
 * is OFF, and every gesture then behaves exactly as it did before the layer existed.
 *
 * [tool] is a [com.t1dm.core.model.PaintTool] key rather than the enum so `:ui:graph` keeps resolving
 * the open text vocabulary in exactly one place ([PaintFrame.toolIdOf]) — for a live stroke and for a
 * decoded one alike.
 */
data class PaintControls(
    val tool: String,
    val colorArgb: Int,
    val widthDp: Float,
    val eraser: Boolean = false,
)

/** Which of the three things a pointer is doing while paint mode is on. */
private enum class PaintGesture { DRAW, ERASE, TRANSFORM }

/** The scrub read-out's fixed column geometry (I4). Derived from the constant label set and the constant
 *  value template, so it depends on the theme and the density and on nothing the cursor does. */
private class ScrubMetrics(val labelColW: Float, val valueColW: Float, val lineH: Float)

/** The plot rectangle in canvas pixels — the box EVERY paint coordinate is anchored to (never the
 *  composable, whose top moves by 14 dp when the model's predicted-clock axis appears). */
private class PlotBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Double get() = (right - left).toDouble().coerceAtLeast(1.0)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

/**
 * The Phase-1 live BG graph (Phase 1 — "Graph = the centrepiece"):
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
    // One icon per logged carb/insulin event, in two fixed lanes low in the plot (LogMarkerLayer.kt).
    // Carries when / which channel / whether the server has taken it yet, and nothing else — no amount,
    // no row id — so the panel can neither render a figure it has no business rendering nor reach back
    // at the row.
    logMarkers: List<LogMarker> = emptyList(),
    /** Fired when a tap lands on a mark, with the positions IN [logMarkers] of every log standing
     *  behind it — a whole cluster, and both lanes when the column carries carbs and insulin at once.
     *  Indices rather than markers because a marker is not an identity: two logs can share a 5-min
     *  slot. The caller resolves them against the richer feed it reduced [logMarkers] from, so the
     *  amounts never enter this panel. */
    onMarkerTap: ((List<Int>) -> Unit)? = null,
    rangeMinMgdl: Int? = null,
    rangeMaxMgdl: Int? = null,
    predictedClock: PredictedClock? = null,
    smoothed: SmoothedTrace? = null,
    showSmoothed: Boolean = false,
    // I2 — the ephemeral, display-only rolled forecast (never drives an alert/dose).
    rolled: RolledSeries? = null,
    // I3 — extend the pannable right edge this far past now so the committed dose curves in the empty
    // future are reachable (up to 24 h), WITHOUT auto-following into that empty region.
    futureExtentMs: Long = 0L,
    // The freehand ANNOTATION layer, already built off-thread ([paintFrameOf]). Drawn under every
    // trace and overlay, and clipped out of a corridor around the live BG line so the glucose trace
    // stays legible under any amount of drawing. In-app BG panel only.
    paint: PaintFrame? = null,
    // PAINT MODE. Non-null ⇒ one finger draws (or erases) and two or more pan/zoom; null ⇒ the panel's
    // gestures are exactly what they were before the layer existed. Painting is DECORATIVE: a stroke is
    // never read by a calculator, a model channel, an alarm, or any §3.6 rail — the only thing it can
    // affect is this Canvas, and even here the corridor keeps it off the glucose trace.
    paintControls: PaintControls? = null,
    /** Fired ONCE on lift-off with the finished stroke (`id = 0`; the store mints the row id). */
    onPaintStroke: ((PaintStroke) -> Unit)? = null,
    /** Fired with the row id of a stroke the eraser hit. Whole strokes only, so undo stays a stack. */
    onErasePaintStroke: ((Long) -> Unit)? = null,
    onScrub: ((GraphScrub?) -> Unit)? = null,
    /** Reports the visible window — start instant and span, both in ms — whenever the user pans or
     *  pinches. Hoisted for DRIVE MODE, which adopts the chart's own viewport rather than a zoom of
     *  its own, and which maps a tap on the panel back to an instant through it. Both otherwise live
     *  and die inside this composable. */
    onViewportChange: ((startMs: Double, spanMs: Double) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // The default cache holds EIGHT entries and one pass over this panel measures ~25 distinct strings
    // (a value label per gridline, a local-time label per tick, a model-clock label beside each, the tz
    // caption, the axis tag, a trace legend) and ~35 while scrubbing — so at the default every frame
    // thrashed the LRU and every measure was a fresh layout. That multiplies against the draw phase,
    // which the committed-marker pulse re-enters at the display's refresh rate on an unchanged viewport,
    // where every one of those strings is byte-identical to the frame before. Sized past the worst case
    // so a static viewport measures nothing twice; the entries are short single-line layouts.
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val haptics = LocalT1dmHaptics.current

    val leftPx = with(density) { GraphInsets.Left.toPx() }
    val rightPx = with(density) { GraphInsets.Right.toPx() }
    // Widens to reserve the model's predicted-clock axis (item 21) when the clock is available.
    val topPx = with(density) { GraphInsets.top(predictedClock != null).toPx() }
    val bottomPx = with(density) { GraphInsets.Bottom.toPx() }

    // The annotation layer's draw-phase scratch: a memoised corridor mask and one reusable path, so
    // painting hundreds of strokes allocates nothing per frame. Held here (never conditionally) so the
    // early empty-frame return below cannot skip a `remember`.
    val corridor = remember { PaintCorridor() }
    val paintPath = remember { Path() }
    val dpPx = density.density
    val corridorPx = corridorWidthPx(dpPx)
    val chalkPens = remember(dpPx) { ChalkPens(dpPx) }

    // ── The draw phase's other scratch: everything immutable it would otherwise re-allocate per frame ──
    // The same discipline as the annotation layer above, applied where the file had stopped applying it.
    // None of these depends on anything the draw phase computes — a dash pattern is a constant, and each
    // style is a pure function of the theme, which is read in composition already — so re-deriving them
    // inside the Canvas bought a fresh object on every invalidation, and the committed-marker pulse
    // invalidates at the display's refresh rate.
    val tracePath = remember { Path() }
    val overlayPaths = remember { CurveChannelPaths() }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }
    // Both trace legends are the primary ink at 9 sp — the same style, since exactly one of them is ever
    // drawn (the toggle makes "smoothed" a SWAP for the raw trace, never an overlay of it).
    val traceLegendStyle = remember(cs.primary) { TextStyle(color = cs.primary, fontSize = 9.sp) }
    val rolledLegendStyle = remember(cs.onSurface) {
        TextStyle(color = cs.onSurface.copy(alpha = 0.7f), fontSize = 9.sp)
    }
    // Where the selected model's fan ends is where the roll's hatched band begins, and the two must
    // state one uncertainty there — see [RolledSeam]. Resolved here rather than inside the draw: it
    // changes only when the forecast set does, and the draw phase re-runs at the display's refresh
    // rate. Taken only from a series whose fan is actually painted — a degenerate forecast paints
    // none, so there is nothing on screen for the hatch to meet.
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
    // The read-out box's FIXED geometry (I4): both column widths and the line height come from constant
    // template strings, so they are decided once per theme rather than re-measured — seven measures a
    // frame — for every pointer sample of a scrub.
    val scrubMetrics = remember(measurer, scrubLabelStyle, scrubValueStyle) {
        val template = measurer.measure(SCRUB_VALUE_TEMPLATE, scrubValueStyle)
        ScrubMetrics(
            labelColW = SCRUB_LABELS.maxOf { measurer.measure(it, scrubLabelStyle).size.width }.toFloat(),
            valueColW = template.size.width.toFloat(),
            lineH = template.size.height.toFloat(),
        )
    }

    // ── The log-marker layer's composition-time state ──────────────────────────────────────────────
    // Held here, unconditionally, for the same reason the paint scratch above is: the empty-frame return
    // below must not be able to skip a `remember`.
    //
    // ONE burger and ONE syringe, shape-fixed across every theme and rasterised once; the theme reaches
    // them only as the tint applied below. Splitting the feed by channel happens ONCE here, and so does
    // the sort — `clusterLogMarkers` takes a single lane and is a linear pass that reads the projection
    // as monotone, so re-splitting or re-sorting per frame would put an O(n log n) allocation in the
    // draw phase of every pan.
    val semantics = LocalT1dmSemantics.current
    val carbMarkPainter = rememberVectorPainter(logMarkerIcon(CurveKind.CARB))
    val insulinMarkPainter = rememberVectorPainter(logMarkerIcon(CurveKind.INSULIN))
    // The two model channels' inks, decided ONCE: the curve overlay paints its carb and insulin areas
    // in them, and the log markers tint their glyphs with them, so a mark is always the colour of the
    // curve it stands for and the two cannot drift apart. Read from the SEMANTIC roles rather than the
    // Material projection of them, because the marker glyphs are shape-fixed — the tint is the only
    // thing the theme still says about a mark. The two tints are built here rather than per draw: the
    // committed pulse re-enters the draw lambda at the display's refresh rate, and a ColorFilter is a
    // real allocation.
    val carbInk = semantics.secondary
    val insulinInk = semantics.inRange
    val carbTint = remember(carbInk) { ColorFilter.tint(carbInk) }
    val insulinTint = remember(insulinInk) { ColorFilter.tint(insulinInk) }
    val carbLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.CARB) }
    val insulinLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.INSULIN) }
    val anyCommitted = remember(logMarkers) { logMarkers.any { it.state == LogState.COMMITTED } }
    val markSepPx = logMarkerSeparationPx(dpPx)
    val markSizePx = LOG_MARKER_DP * dpPx
    // The committed pulse. ONE animation drives every committed mark — they say the same thing, so they
    // should say it in unison, and a per-mark animation would be a hundred animations on a busy day.
    val motionOn = LocalAnimationsEnabled.current
    val markerPulse = remember { Animatable(LOG_MARKER_STATIC_ALPHA) }

    // The stroke under the finger. The buffer is PLAIN memory (mutated from the pointer handler); the
    // Canvas is driven by [liveCount], which is snapshot state, so a new sample invalidates the DRAW
    // phase only — never a recomposition. [liveHeld] keeps a just-finished stroke on screen until its
    // persisted twin arrives through [paint], so lift-off does not blink.
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
    // TIME-anchored scrub cursor (absolute epoch-ms; NaN = inactive) so it can land in the forecast
    // zone past the last reading (item 3), not only on a BG sample.
    var scrubMs by remember { mutableStateOf(Double.NaN) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun plotW(): Double = (canvasSize.width - leftPx - rightPx).toDouble().coerceAtLeast(1.0)

    // Where the AUTO-FOLLOW settles: the last reading OR the furthest forecast/rolled step, so the
    // forecast horizon (in the future, past the last reading) stays on-screen — but NOT the empty
    // future-view region, which the user reaches only by panning.
    fun followEndMs(): Double {
        val fe = if (frame.isEmpty) 0.0 else frame.absMs(frame.size - 1)
        val pe = predictions.maxTsMs()?.toDouble() ?: fe
        val re = rolled?.maxTsMs?.toDouble() ?: fe
        return maxOf(fe, pe, re)
    }

    // The furthest the viewport may be PANNED to (I3): the data/forecast end, extended into the empty
    // future by [futureExtentMs] so the committed dose curves out there are reachable.
    fun panEndMs(): Double =
        maxOf(followEndMs(), (System.currentTimeMillis() + futureExtentMs).toDouble())

    fun spanBounds(): Pair<Double, Double> {
        val range = if (frame.isEmpty) 0.0 else panEndMs() - frame.absMs(0)
        val minSpan = 15.0 * 60_000.0
        val maxSpan = maxOf(range, initialWindowMin.toDouble() * 60_000.0) * 1.2
        return minSpan to maxSpan.coerceAtLeast(minSpan)
    }

    fun clamp() {
        if (frame.isEmpty) return
        val (minSpan, maxSpan) = spanBounds()
        viewSpanMs = viewSpanMs.coerceIn(minSpan, maxSpan)
        val ds = frame.absMs(0)
        val de = panEndMs()
        val range = de - ds
        viewStartMs = if (viewSpanMs >= range) ds - (viewSpanMs - range) / 2.0
        else viewStartMs.coerceIn(ds, de - viewSpanMs)
    }

    // ── What the ONE pointer handler reads ─────────────────────────────────────────────────────
    // The handler is keyed on paint mode ALONE, so a landing reading or a publishing forecast can no
    // longer cancel a gesture in flight (the scrub handler used to be keyed on five changing values —
    // tolerable for a sub-second scrub, fatal for a long freehand line, which would be truncated
    // mid-draw). Everything it needs that DOES change is therefore read through `rememberUpdatedState`,
    // so the coroutine always calls the CURRENT composition's closure over the current frame,
    // predictions and plot insets rather than the ones it captured when it was launched.
    val paintOn = paintControls != null
    val controls by rememberUpdatedState(paintControls)
    val paintNow by rememberUpdatedState(paint)
    val emitStroke by rememberUpdatedState(onPaintStroke)
    val eraseStroke by rememberUpdatedState(onErasePaintStroke)
    val plotBox by rememberUpdatedState(
        PlotBox(leftPx, topPx, canvasSize.width - rightPx, canvasSize.height - bottomPx),
    )

    // ── The marker lanes' clusters, hoisted OUT of the draw lambda ──────────────────────────────
    // Collision is a pixel fact, so the clusters depend on the viewport — but the viewport changes
    // once per pointer sample and the draw lambda re-runs at the display's refresh rate for as long as
    // one committed mark is breathing. Memoised here they are rebuilt per PAN, not per FRAME, and the
    // pulse costs nothing but the painting it exists for. No new invalidation is bought with it:
    // `viewStartMs` and `canvasSize` are already read in composition (the LaunchedEffect below keys on
    // the first, `plotBox` on the second), so this scope was recomposing on a pan regardless.
    //
    // They are also exactly what the tap hit-tests against, which is the point: the marks a tap
    // resolves cannot be a different reduction from the marks the user is looking at.
    val plotRightPx = canvasSize.width - rightPx
    val insulinClusters = remember(insulinLane, viewStartMs, viewSpanMs, leftPx, plotRightPx, markSepPx) {
        if (viewStartMs.isNaN()) emptyList()
        else clusterLogMarkers(insulinLane.marks, viewStartMs, viewSpanMs, leftPx, plotRightPx, markSepPx)
    }
    val carbClusters = remember(carbLane, viewStartMs, viewSpanMs, leftPx, plotRightPx, markSepPx) {
        if (viewStartMs.isNaN()) emptyList()
        else clusterLogMarkers(carbLane.marks, viewStartMs, viewSpanMs, leftPx, plotRightPx, markSepPx)
    }

    // What a tap on the marker band resolves to: positions in the caller's OWN feed, for it to name.
    // Rebuilt every composition and read through `rememberUpdatedState` like every other closure the
    // pointer handler holds, so the handler — keyed on paint mode alone — always tests against the
    // current viewport rather than the one it was launched under.
    val markerTap by rememberUpdatedState(onMarkerTap)
    val hitMarkers by rememberUpdatedState<(Offset) -> List<Int>>({ pos ->
        hitTestLogMarkers(
            pos.x, pos.y, plotBox.left, plotBox.right, plotBox.bottom, dpPx,
            insulinLane, insulinClusters, carbLane, carbClusters,
        )
    })

    // Edge-triggered so a pan HELD against the clamp buzzes once on arrival rather than droning at
    // every pointer sample; it re-arms only once the viewport has moved off the wall. A plain holder
    // rather than snapshot state: this is felt, never drawn, so it must not invalidate the Canvas.
    val atEdge = remember { booleanArrayOf(false) }

    /** Pan + pinch-zoom: the ONE implementation of the viewport math, shared by both modes. */
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
        // What the gesture ASKED for, before clamp() pins it to the data bounds. Comparing the two is
        // the only honest way to know a wall was met: `clamp` silently rewrites both fields, so after
        // it runs there is nothing left to test against.
        val wantedStart = viewStartMs
        val wantedSpan = viewSpanMs
        clamp()
        // `isFinite` guards the one frame between the first non-empty frame arriving and the
        // LaunchedEffect that seeds `viewStartMs`: NaN compares unequal to itself, which would read as
        // a wall that was never met.
        val pinned = wantedStart.isFinite() &&
            (viewStartMs != wantedStart || viewSpanMs != wantedSpan)
        if (pinned && !atEdge[0]) haptics.perform(HapticEvent.EdgeStop)
        atEdge[0] = pinned
        followLatest = (viewStartMs + viewSpanMs) >= de - viewSpanMs * 0.02
    })

    // The scrub's detent. The graph's grain is the SAMPLE, not the pixel: `scrubAt` runs on every
    // pointer-move, so keying the tick on the raw position would saturate the LRA into a flat buzz.
    // Fed the nearest sample index (or, out past the last reading, the 5-min forecast bucket the
    // cursor sits in) it instead ticks once per reading crossed — a texture that tracks the data, so
    // a slow drag over a sparse stretch is quiet and a fast one over dense history is dense.
    val scrubDetent = rememberHapticDetent(HapticEvent.ScrubTick)

    /** Move the TIME-anchored scrub cursor to a canvas x and publish the read-out. */
    val scrubAt by rememberUpdatedState<(Float) -> Unit>({ x ->
        val ppm = plotW() / viewSpanMs
        val lastMs = if (frame.isEmpty) Double.NaN else frame.absMs(frame.size - 1)
        val ms = (viewStartMs + (x - leftPx) / ppm).coerceIn(frame.absMs(0), panEndMs())
        scrubDetent.at(
            if (!lastMs.isNaN() && ms > lastMs) "f" + ((ms - lastMs) / 300_000.0).toLong()
            else frame.nearestIndex(ms),
        )
        scrubMs = ms
        onScrub?.invoke(buildScrub(frame, predictions, curveOverlay, predictedClock, rolled, ms))
    })
    val scrubEnd by rememberUpdatedState<() -> Unit>({
        haptics.perform(HapticEvent.DragEnd)
        scrubMs = Double.NaN
        onScrub?.invoke(null)
    })

    // The held stroke is released the moment the layer that now contains it arrives. If nothing is
    // listening for strokes at all there is no twin to wait for, so nothing is ever held.
    LaunchedEffect(paint) {
        if (liveHeld) {
            liveHeld = false
            liveCount = 0
        }
    }

    // Leaving paint mode cancels the pointer handler wherever it happens to be, so a stroke in flight
    // is dropped without ever being emitted; clear its pixels too, or the abandoned line would linger
    // as a ghost that no persisted stroke will ever replace.
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

    // I2 — when an on-demand rolled forecast newly lands, AUTO-PAN right so its far edge is visible,
    // widening the span to include ~1 h of context before the roll's anchor.
    LaunchedEffect(rolled) {
        if (rolled == null || rolled.isEmpty || frame.isEmpty) return@LaunchedEffect
        val end = rolled.maxTsMs!!.toDouble()
        val start = rolled.tsMs.first().toDouble() - 60.0 * 60_000.0
        viewSpanMs = (end - start).coerceAtLeast(initialWindowMin.toDouble() * 60_000.0)
        viewStartMs = end - viewSpanMs
        followLatest = false
        clamp()
    }

    // The 6h / 12h / 24h window buttons (item 5) drive [initialWindowMin]; a change resets the visible
    // span and re-follows the latest reading so the button feels immediate.
    LaunchedEffect(initialWindowMin) {
        viewSpanMs = initialWindowMin.toDouble() * 60_000.0
        followLatest = true
        if (!frame.isEmpty) { viewStartMs = followEndMs() - viewSpanMs; clamp() }
    }

    // The committed marks' endless fade. Two things it deliberately is not:
    //
    //  - It is not built from `motionSpec`. That returns `snap()` with motion off, and a snapped fade to
    //    invisible would silently stop distinguishing committed from delivered exactly when the user has
    //    asked for a static UI. The disabled branch is therefore a HELD alpha, not an animation at all —
    //    the same choice, for the same reason, as `pulseHighlight`.
    //  - It is not started when nothing is waiting on the server. An always-running animation would keep
    //    the panel invalidating its draw phase forever for a mark that has nothing left to say.
    LaunchedEffect(motionOn, anyCommitted) {
        if (!motionOn || !anyCommitted) {
            markerPulse.snapTo(LOG_MARKER_STATIC_ALPHA)
            return@LaunchedEffect
        }
        while (true) {
            markerPulse.animateTo(LOG_MARKER_PULSE_MIN_ALPHA, tween(LOG_MARKER_PULSE_MS, easing = LinearEasing))
            markerPulse.animateTo(LOG_MARKER_PULSE_MAX_ALPHA, tween(LOG_MARKER_PULSE_MS, easing = LinearEasing))
        }
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
            // ── ONE pointer handler for the whole panel ────────────────────────────────────────
            //
            // PAINT OFF: the two stock detectors, with the same lambdas they have always had, now
            // co-resident in this single node — and, registered ahead of both so that it is dispatched
            // behind both, the marker tap. That one consumes nothing whatever the outcome, so the two
            // below cannot tell it is there and the question of what it might take from them does not
            // arise; [detectLogMarkerTaps] carries the argument in full.
            //
            // Their relative priority — which is load-bearing, since
            // `detectTransformGestures` aborts the moment it sees a consumed change, and the scrub
            // consumes — survives the move because a node dispatches the MAIN pass to its handlers in
            // REVERSE registration order (verified against compose-ui 1.7.6's
            // `SuspendingPointerInputModifierNodeImpl.forEachCurrentPointerHandler`, where Initial and
            // Final walk forward and Main walks back). Registration order is the launch order, and
            // `awaitEachGesture` only ever re-registers after ALL pointers are up — on the FINAL pass,
            // which is dispatched forward — so the two re-arm in the same order they first armed. That
            // is exactly the ordering the old stacked modifiers got structurally.
            //
            // UNDISPATCHED is not a nicety. The node starts `pointerInputJob` lazily from the FIRST
            // `onPointerEvent`, itself undispatched, and dispatches that very DOWN the instant the body
            // suspends; a plain `launch` would only append to `AndroidUiDispatcher`'s trampoline, so the
            // DOWN would meet an EMPTY handler vector and both detectors — which open on
            // `awaitFirstDown`, matched by `!previousPressed && pressed` — would sit out the whole first
            // gesture rather than merely truncating it.
            //
            // PAINT ON: one finger draws (or erases) and TWO OR MORE pan/zoom, so reaching fresh
            // canvas costs nothing but a second finger; long-press-scrub is suspended entirely, because
            // a long press is how one starts a deliberate stroke. A second finger landing mid-stroke
            // abandons the in-flight stroke CLEANLY — nothing is emitted and nothing is persisted —
            // rather than smearing it across the pan.
            .pointerInput(paintOn) {
                if (!paintOn) {
                    coroutineScope {
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            // A tap on a log mark opens what it stands for. Registered FIRST, so on the
                            // main pass — walked in reverse — it is dispatched LAST: whatever the pan or
                            // the scrub means to claim, it has already claimed by the time this sees the
                            // event, and the detector aborts on that consumption. It consumes NOTHING
                            // itself, at any point, so the two below cannot tell it is here; all it does
                            // is read a stationary short press that both of them were already discarding.
                            // Not keyed on the marker feed: the handler is keyed on paint mode alone and
                            // a re-key cancels a gesture in flight, so an empty feed is answered by the
                            // hit test missing rather than by the detector not existing.
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
                            // Long-press then drag = scrub. Time-anchored, so it works in the forecast zone.
                            // The entry pop is the single most valuable haptic in the app: the scrub is
                            // otherwise invisible until the finger has already moved, so without it the
                            // long press is a blind wait. The detent is reset first so the sample the
                            // cursor LANDS on seeds silently — the ticks then count crossings, not the
                            // arrival, which the LongPress has already announced far more loudly.
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

                // The eraser's reach is a constant fingertip and can be hoisted; the decimation gate is
                // not — it scales with the pen and so belongs with the palette read below.
                val erasePx = PAINT_ERASE_RADIUS_DP * dpPx

                // The projection is recomputed per sample rather than captured at pen-down: while a
                // stroke is being drawn the viewport can still slide under it (auto-follow shifts on a
                // landing reading) and the plot's top inset can move (the predicted-clock axis
                // appearing), and a captured transform would silently shear the stroke.
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
                    // Read the palette AFTER the down, never before it. `awaitEachGesture` re-invokes
                    // its block on the FINAL pass of the previous lift-off and then parks in
                    // `awaitFirstDown`, so a value latched at the top of the block is the palette as it
                    // stood at the END of the last stroke — and the handler is deliberately keyed on
                    // `paintOn` alone, so a chip tap never restarts it to refresh that value. The live
                    // overlay reads `paintControls` straight from the composition at draw time, so a
                    // stale latch would commit a row that disagrees with the line the user just watched
                    // themselves draw (the mismatch `drawLiveStroke` promises cannot happen), and would
                    // put the eraser latch a whole gesture behind the chip the user is looking at.
                    val ctl = controls ?: return@awaitEachGesture
                    down.consume()

                    // …and derived from it here for the same reason. A gate hoisted out of the gesture
                    // would be the width of whatever pen the LAST stroke used, which under the flood pen
                    // is the difference between a few dozen samples and a few thousand.
                    val minStepPx = paintMinStepPx(ctl.widthDp, dpPx)

                    var mode = if (ctl.eraser) PaintGesture.ERASE else PaintGesture.DRAW
                    val erased = HashSet<Long>() // one callback per stroke, however long the finger lingers
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
                                // No slop gate: the user has already declared intent by putting a second
                                // finger down, and a pan that has to be earned would fight the drawing.
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
                        // The stroke ends where the finger left the glass, not at the last sample that
                        // happened to clear the min-distance gate.
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
            // Fold the visible rolled forecast (I2) into the auto-fit too, so the extrapolated tail
            // never clips off the plot.
            rolled?.let { rs ->
                val vLo = viewStartMs; val vHi = viewStartMs + viewSpanMs
                for (i in 0 until rs.size) {
                    val t = rs.tsMs[i].toDouble()
                    if (t < vLo || t > vHi) continue
                    if (rs.lo[i] < yMin) yMin = rs.lo[i]
                    if (rs.hi[i] > yMax) yMax = rs.hi[i]
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

            // Theme-derived palette. The grid/axis/label inks and their 10 sp style live where they are
            // used — inside [drawGraphFurniture], which owns every mark that draws in them; the copies
            // that lingered here after that extraction were read by nothing.
            val lineColor = cs.primary
            val interpColor = cs.primary.copy(alpha = 0.45f)
            val warmupColor = cs.secondary
            // I5 — "Smoothed" is a SWAP, not an overlay: when on (and a smooth exists) the raw sensor
            // polyline is REPLACED by the model-input smoothed trace, so exactly one trace is on screen.
            val swapToSmoothed = showSmoothed && smoothed != null && !smoothed.isEmpty

            // (1)–(4) Grid, axes and their labels. Extracted so GAME MODE can render the very same
            // furniture around its own viewport — the panel keeps its background, its left value axis
            // and both time axes while the car drives, instead of becoming a bare canvas.
            drawGraphFurniture(
                unit = frame.unit,
                tzOffsetMin = frame.tzOffsetMin,
                plotLeft = plotLeft, plotTop = plotTop, plotRight = plotRight, plotBottom = plotBottom,
                viewStartMs = viewStartMs, viewSpanMs = viewSpanMs,
                yMin = yMin, yMax = yMax,
                thresholds = thresholds,
                predictedClock = predictedClock,
                measurer = measurer,
                cs = cs,
            )

            // Clip data-drawing sections to the plot rectangle so no trace spills over the y-axis
            // labels or off the plot edges; grid, axes, and margin captions above stay OUTSIDE it.
            clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
                // (4.4) The freehand ANNOTATION layer — first inside the plot clip, so it sits under the
                //       curve overlay, both BG traces, the markers, the forecast fans and the scrub
                //       read-out — so no measurement, forecast or read-out is ever hidden behind the
                //       user's marginalia.
                //
                //       THE CORRIDOR: the paint is then clipped OUT of a [PAINT_CORRIDOR_DP] band centred
                //       on the BG line, so a scribble laid straight across the trace leaves a clean halo
                //       instead of a smear. The band follows whichever trace is ACTUALLY on screen — I5
                //       makes "Smoothed" a SWAP rather than an overlay, so exactly one exists at a time,
                //       and masking around a trace that is not being drawn would carve an unexplained
                //       blank channel through the art. The mask is derived from the same runs the polyline
                //       is drawn from ([forEachTraceRun]), so it breaks at genuine dropouts too.
                //
                //       The stroke UNDER THE FINGER is drawn here too, through the same projection, the
                //       same per-tool renderer and the same mask — so the line being drawn looks exactly
                //       like the line that will have been drawn, and the panel never appears to edit the
                //       user's work on lift-off.
                val livePoints = liveCount // a DRAW-phase snapshot read: a new sample redraws, never recomposes
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
                            // The smoothed trace's own draw loop culls to ± one full span (three viewports'
                            // worth); the corridor only ever masks what is on screen, so narrow it.
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

                // (4.5) Curve overlay (carb Ra + insulin action) in the bottom band, UNDER the BG line
                //       so it never occludes the glucose trace (Phase 4 — toggleable).
                if (curveOverlay != null && curveToggles.any) {
                    fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                    val bandTop = plotBottom - plotHeight * 0.30f
                    drawCurveOverlay(
                        curveOverlay, curveToggles, ::absToPx, bandTop, plotBottom,
                        carbColor = carbInk, insulinColor = insulinInk,
                        // The viewport, so the draw can bound itself to it: the channels span up to ~14
                        // days of buckets and a default window shows ~1.8% of them.
                        viewStartMs = viewStartMs, viewSpanMs = viewSpanMs,
                        paths = overlayPaths,
                    )
                }

                // (4.6) LOG MARKERS — one icon per logged carb/insulin event, in two FIXED lanes in the
                //       plot's lower region: insulin above, carbs below, whatever is in view.
                //
                //       Inside this clip on purpose: they belong to the data, so they pan and zoom with
                //       it and can never spill over the local-time or model-time axes. Anchored to
                //       `plotBottom`, which the furniture pass already computed — never to the
                //       composable's own height, whose top moves by the model-axis strip. The lanes
                //       OVERLAY that region: `plotBottom`, the y scale and the trace geometry above are
                //       untouched, so turning logging on cannot move the glucose line.
                //
                //       Drawn after the curve overlay whose band shares this strip, so a mark is never
                //       buried under it — and BEFORE the BG trace, so an icon can never sit on top of a
                //       hypoglycaemic excursion, which drops into exactly these lanes.
                //
                //       The alpha is read HERE, in the draw lambda, so a running fade invalidates the
                //       draw phase alone — a marker breathing must not recompose the panel. Everything
                //       else the lanes need is decided in composition: at refresh rate, the whole of
                //       this section is two translate-and-blit loops over lists already built.
                drawLogMarkers(
                    insulinClusters,
                    painter = insulinMarkPainter,
                    tint = insulinTint,
                    sizePx = markSizePx,
                    laneTopY = logMarkerLaneTop(CurveKind.INSULIN, plotBottom, dpPx),
                    committedAlpha = markerPulse.value,
                )
                drawLogMarkers(
                    carbClusters,
                    painter = carbMarkPainter,
                    tint = carbTint,
                    sizePx = markSizePx,
                    laneTopY = logMarkerLaneTop(CurveKind.CARB, plotBottom, dpPx),
                    committedAlpha = markerPulse.value,
                )

                // (5) BG polyline, segment-styled by provenance; gaps broken. Suppressed when the smoothed
                //     model-input trace has replaced it (I5).
                if (!swapToSmoothed) for (i in iLo until iHi) {
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

                // (6) Point markers — only when uncluttered, so distinctions stay legible. Suppressed while
                //     the smoothed trace is shown in place of the raw one (I5).
                if (!swapToSmoothed && iHi - iLo <= 240) {
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

                // (6.4) Smoothed model-input trace (item 13; I5 — now a SWAP): the causal Savitzky-Golay
                //       series the model actually consumes (mg/dL, before any risk transform). When the
                //       "Smoothed" toggle is on it REPLACES the raw trace (drawn above only when off), so a
                //       single trace is ever on screen; the legend states which one. Breaks are honoured so a
                //       dropout is not bridged with a fictitious line.
                if (swapToSmoothed) {
                    val sm = smoothed!!
                    val smColor = lineColor // drawn AS the primary trace, since it stands in for the raw one
                    // The cull is the same ± one span it always was, but REACHED rather than walked: the
                    // old loop visited every point of a never-pruned history — years of it — to test a
                    // predicate that admits one contiguous range ([visibleRange] carries the argument,
                    // and the same binary search already narrows the corridor mask a hundred lines
                    // above). The loop body is unchanged, so the polyline is identical, break for break.
                    val path = tracePath
                    path.reset()
                    var open = false
                    fun flush() { if (open) { drawPath(path, smColor, style = Stroke(width = 2.2f, cap = StrokeCap.Round)); path.reset(); open = false } }
                    for (i in sm.visibleRange(viewStartMs, viewSpanMs)) {
                        val x = (plotLeft + (sm.tsMs[i].toDouble() - viewStartMs) * ppm).toFloat()
                        val y = yToPx(sm.ys[i])
                        if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
                        if (i < sm.size - 1 && sm.breakAfter[i]) flush()
                    }
                    flush()
                    val leg = measurer.measure("model input — smoothed", traceLegendStyle)
                    drawText(leg, topLeft = Offset((plotRight - leg.size.width - 4f).coerceAtLeast(plotLeft), plotTop + 2f))
                } else if (smoothed != null && !smoothed.isEmpty) {
                    // The raw sensor trace is showing (section 5); label it so the toggle's state is legible.
                    val leg = measurer.measure("sensor — raw", traceLegendStyle)
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

                // (6.6) The ephemeral, DISPLAY-ONLY rolled forecast (I2): the extrapolated tail beyond the
                //       validated 2 h is drawn hatched/dimmed with a boundary + legend, so it can never be
                //       mistaken for a validated forecast (and it never drives an alert or a dose).
                rolled?.let { rs ->
                    fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                    drawRolledSeries(rs, ::absToPx, ::yToPx, plotTop, plotBottom, cs.tertiary, cs.onSurface, rolledSeam)
                    val legendText = if (rs.degenerate) "extrapolated · degenerated · display-only"
                    else "extrapolated · unvalidated · display-only"
                    val leg = measurer.measure(legendText, rolledLegendStyle)
                    // Lifted clear of the marker band whenever the lanes claim it. Both this caption and
                    // the lanes are measured up from `plotBottom` and the caption is drawn LAST, so left
                    // where it was it prints straight over the carb lane — and it is the one thing keeping
                    // the extrapolated tail from being read as a validated forecast, so neither layer may
                    // be allowed to bury the other. Gated on exactly what the lanes are gated on — the
                    // FEED, not the clusters — so the caption holds one height for as long as marks exist
                    // rather than hopping as they pan in and out of view.
                    //
                    // The band is a third of a short plot's height, so the lift is clamped to the plot
                    // top: overlapping a lane is bad, but being clipped away by the plot rectangle would
                    // silence the caption altogether, which is the one outcome not tolerable here.
                    val laneless = carbLane.marks.isEmpty() && insulinLane.marks.isEmpty()
                    val legFloor = plotBottom - if (laneless) 0f else LOG_MARKER_BAND_DP * dpPx
                    val legTop = (legFloor - leg.size.height - 2f).coerceAtLeast(plotTop)
                    drawText(leg, topLeft = Offset((plotRight - leg.size.width - 4f).coerceAtLeast(plotLeft), legTop))
                }

                // (7) Scrub cursor — time-anchored, so it reads in the forecast zone too (item 3). U8 — the
                //     read-out box is now PINNED at the right-hand middle of the plot (not floating by the
                //     thumb) and updates continuously as the thumb moves, so a finger never occludes it.
                if (!scrubMs.isNaN()) {
                    val cx = (plotLeft + (scrubMs - viewStartMs) * ppm).toFloat()
                    if (cx in plotLeft..plotRight) {
                        val sc = buildScrub(frame, predictions, curveOverlay, predictedClock, rolled, scrubMs)
                        drawLine(cs.onSurface.copy(alpha = 0.5f), Offset(cx, plotTop), Offset(cx, plotBottom), 1f)
                        sc.bgValue?.let { drawCircle(cs.onSurface, 4f, Offset(cx, yToPx(it)), style = Stroke(width = 2f)) }
                        // I4 — a STABLE, TABULATED read-out: a two-column table (short label ⟶ right-aligned
                        //      value) inside the box. Labels are normal-weight; VALUES use tabular monospace
                        //      figures so digits align. Both columns are sized from FIXED widest templates
                        //      (the label set + the widest value), never the live content, so the box holds
                        //      its size and its value column's right edge as the thumb moves.
                        //      Both styles and all three template measurements are decided once per theme
                        //      ([scrubMetrics]) rather than per pointer sample: they are functions of the
                        //      FIXED templates alone, so re-measuring them as the thumb moved was seven
                        //      text layouts a frame for three numbers that cannot change.
                        val rows = scrubRows(sc)
                        val padH = 9f; val padV = 8f; val colGap = 14f; val rowGap = 5f
                        val labelColW = scrubMetrics.labelColW
                        val valueColW = scrubMetrics.valueColW
                        val lineH = scrubMetrics.lineH
                        val boxW = padH + labelColW + colGap + valueColW + padH
                        val boxH = padV * 2f + lineH * rows.size + rowGap * (rows.size - 1)
                        // Fixed at the right-hand middle of the plot.
                        val bx = (plotRight - boxW - 6f).coerceAtLeast(plotLeft)
                        val by = ((plotTop + plotBottom) / 2f - boxH / 2f).coerceIn(plotTop, plotBottom - boxH)
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

/** Sample the graph at absolute [ms] for the scrub read-out (item 3). BG comes from the reading
 *  series in the past, the selected model's median in the prediction zone, and the rolled forecast
 *  wherever no validated one reaches; carb/insulin rates from the overlay; the model clock from the
 *  circadian probe. */
internal fun buildScrub(
    frame: GraphFrame,
    predictions: List<PredSeries>,
    overlay: CurveOverlayFrame?,
    clock: PredictedClock?,
    rolled: RolledSeries?,
    ms: Double,
): GraphScrub {
    val lastFrameMs = if (frame.isEmpty) Long.MIN_VALUE else frame.absMs(frame.size - 1).toLong()
    val inPred = ms > lastFrameMs
    // The BG, and where it came from, as one cascade so the provenance travels out with the number: a
    // measured reading in the past; else the selected model's validated median; else — past that 2 h
    // horizon, or during warmup, where no validated forecast reaches — the DISPLAY-ONLY rolled
    // forecast, whose extrapolated tail is marked rather than printed like a validated value. A step
    // inside the roll's validated prefix is NOT marked: it coincides with the 2 h forecast, and the
    // overlay draws it as the plain line it is.
    var bg: Float? = null
    var extrapolated = false
    if (!inPred && !frame.isEmpty) {
        val i = frame.nearestIndex(ms)
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
    val modelHour = clock?.let { predictedHourAt(ms.toLong(), it) }
    return GraphScrub(
        tsMs = ms.toLong(),
        tzOffsetMin = frame.tzOffsetMin,
        bgValue = bg,
        inPredZone = inPred,
        bgExtrapolated = extrapolated,
        carbRate = carb,
        insulinRate = insulin,
        modelHour = modelHour,
        unit = frame.unit,
    )
}

/** The selected model's median (in the frame's unit) at the step nearest absolute [ms], or null when
 *  no eligible forecast reaches [ms] — BOUNDED by [nearestWithinHalfStep], exactly as the rolled
 *  series' lookup is, so past the validated horizon this yields and the fallback below it is reached. */
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

/** Every label the read-out's left column can show; the label column is sized from the widest of these
 *  fixed strings (I4) so the box never resizes as rows appear or vanish under the cursor. */
private val SCRUB_LABELS = listOf("BG", "Carb", "Ins", "Local", "Model")

/** The widest VALUE the right column can ever hold (a carb rate / insulin rate reads "199.9 g" / "99.99 U",
 *  both wider than any BG or clock value — including the widest BG there is, a Kovatchev "-1.23" carrying
 *  its one-glyph prediction/extrapolation marker); the value column — and thus the box — is sized from this
 *  fixed template so its right edge holds still as the tabular figures under the cursor change. */
private const val SCRUB_VALUE_TEMPLATE = "199.9 g"

/** The scrub read-out as (label, value) pairs for the two-column table: BG, carb + insulin rates, and the
 *  clock (local always; model in the prediction zone). Only the rows that exist are emitted — carb, insulin
 *  and model may be absent. Values carry their unit so the right column reads on its own. */
internal fun scrubRows(sc: GraphScrub): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>(5)
    // "*" marks the prediction zone; "~" SUPERSEDES it past the rolled forecast's validated prefix,
    // where the number is extrapolated and unvalidated (an extrapolated value is necessarily in the
    // prediction zone, so the two never both apply). One glyph rather than a word: the value column is
    // sized from a fixed template, and the panel already carries the hatch, the boundary rule and the
    // "extrapolated · unvalidated · display-only" caption that say it at length.
    val mark = when {
        sc.bgExtrapolated -> "~"
        sc.inPredZone -> "*"
        else -> ""
    }
    val bgStr = sc.bgValue?.let { formatValue(it, sc.unit) + mark } ?: "--"
    out.add("BG" to bgStr)
    sc.carbRate?.let { out.add("Carb" to "%.1f g".format(it)) }
    sc.insulinRate?.let { out.add("Ins" to "%.2f U".format(it)) }
    out.add("Local" to formatClock(sc.tsMs, sc.tzOffsetMin))
    sc.modelHour?.let {
        val hh = floor(it).toInt(); val mm = ((it - hh) * 60.0).roundToInt().coerceIn(0, 59)
        out.add("Model" to "%02d:%02d".format(hh % 24, mm))
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
internal fun lowerBoundLong(xs: LongArray, target: Long): Int {
    var lo = 0
    var hi = xs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (xs[mid] < target) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * Index of the entry of the ascending [tsMs] nearest absolute [ms], or -1 when the cursor lies more
 * than half a step outside the series. THE one nearest-step rule, shared by every timestamped overlay
 * the scrub samples.
 *
 * It is shared deliberately. Half a step past either end is the tolerance a nearest-neighbour lookup
 * already grants between two samples, so the bound merely carries that rule over the ends instead of
 * inventing a second one for them — and an unbounded scan is not a laxer version of this, it is a
 * different answer: it returns the last step of the series for every time after it, forever. Written
 * twice, the two copies disagreed exactly there, and the read-out froze at the forecast horizon while
 * the rolled median walked away underneath it.
 */
internal fun nearestWithinHalfStep(tsMs: LongArray, ms: Double): Int {
    val n = tsMs.size
    if (n == 0) return -1
    val half = if (n >= 2) kotlin.math.abs(tsMs[1] - tsMs[0]) / 2.0 else 0.0
    if (ms < tsMs[0] - half || ms > tsMs[n - 1] + half) return -1
    // The two candidates straddling `ms` (`ceil` so the upper one is never below it), then the nearer.
    val hi = lowerBoundLong(tsMs, kotlin.math.ceil(ms).toLong()).coerceIn(0, n - 1)
    val lo = (hi - 1).coerceAtLeast(0)
    return if (kotlin.math.abs(tsMs[lo] - ms) <= kotlin.math.abs(tsMs[hi] - ms)) lo else hi
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
            logMarkers = syntheticMarkers(),
        )
    }
}

/** A lone meal, a meal with its bolus above it in the insulin lane, and a pair of doses close enough to
 *  combine — enough to see both silhouettes, both lanes and both states in one preview. */
private fun syntheticMarkers(): List<LogMarker> {
    val t0 = 1_720_000_000_000L
    val step = 300_000L
    return listOf(
        LogMarker(t0 + 20 * step, CurveKind.CARB, LogState.DELIVERED),
        LogMarker(t0 + 62 * step, CurveKind.CARB, LogState.DELIVERED),
        LogMarker(t0 + 62 * step, CurveKind.INSULIN, LogState.DELIVERED),
        LogMarker(t0 + 100 * step, CurveKind.INSULIN, LogState.COMMITTED),
        LogMarker(t0 + 101 * step, CurveKind.INSULIN, LogState.DELIVERED),
    )
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

/**
 * The panel's furniture: threshold bands, the value grid + left axis, the time grid + the local-time
 * and model-time axes, and the two axis lines. Everything that frames the plot without being data.
 *
 * Extracted from [GlucoseGraph] so the hill-climb mode can draw the SAME frame around its own
 * viewport. That is the whole point of it being a mode rather than a separate screen: while the car
 * drives, the background, the BG scale on the left and both time axes stay exactly where they were, so
 * the player can still read what they are driving over. The game passes the viewport its camera is
 * looking at; every tick and label then follows the car by construction.
 */
fun DrawScope.drawGraphFurniture(
    unit: UnitSpace,
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
) {
    val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
    val ppm = (plotRight - plotLeft).toDouble().coerceAtLeast(1.0) / viewSpanMs
    val ppv = plotHeight / (yMax - yMin)
    fun yToPx(v: Float): Float = plotBottom - (v - yMin) * ppv

    val gridColor = cs.onSurface.copy(alpha = 0.10f)
    val axisColor = cs.onSurface.copy(alpha = 0.30f)
    val labelColor = cs.onSurface.copy(alpha = 0.65f)
    val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)

        // (1) Threshold band tints, if supplied.
        thresholds?.let { drawBands(it, unit, plotLeft, plotRight, ::yToPx, yMin, yMax, cs.error, cs.secondary) }

        // (2) Horizontal value grid + left-axis labels.
        val vStep = niceStep((yMax - yMin).toDouble() / 5.0)
        var vy = floor(yMin / vStep) * vStep
        while (vy <= yMax + 1e-6) {
            val py = yToPx(vy.toFloat())
            if (vy >= yMin && py in plotTop..plotBottom) {
                drawLine(gridColor, Offset(plotLeft, py), Offset(plotRight, py), 1f)
                val label = measurer.measure(formatValue(vy.toFloat(), unit), labelStyle)
                drawText(label, topLeft = Offset(plotLeft - 6f - label.size.width, py - label.size.height / 2f))
            }
            vy += vStep
        }

        // (3) Vertical time grid + bottom-axis labels (ACTUAL local time, item 21). The TOP axis
        //     carries the model's PREDICTED clock when the circadian probe is present, else a quiet
        //     "model time n/a" — never a fabricated axis.
        val tStepMs = niceTimeStepMs(viewSpanMs)
        val tzMs = tzOffsetMin * 60_000L
        var tick = floor((viewStartMs + tzMs) / tStepMs) * tStepMs - tzMs
        if (tick < viewStartMs) tick += tStepMs
        val endMs = viewStartMs + viewSpanMs
        val modelLabelColor = cs.tertiary.copy(alpha = 0.8f)
        val modelStyle = TextStyle(color = modelLabelColor, fontSize = 10.sp)
        // The ceiling of the strip reserved for this axis. The labels are sp-scaled and the reservation
        // is not, so past roughly a 1.15 font scale a label is taller than its own strip; without this it
        // climbs out of the top of the panel, and in drive mode straight through the progress bar.
        val modelTopPx = plotTop - GraphInsets.ModelAxis.toPx()
        while (tick <= endMs) {
            val px = (plotLeft + (tick - viewStartMs) * ppm).toFloat()
            drawLine(gridColor, Offset(px, plotTop), Offset(px, plotBottom), 1f)
            val label = measurer.measure(formatTime(tick.toLong(), tzOffsetMin, tStepMs), labelStyle)
            var lx = px - label.size.width / 2f
            lx = lx.coerceIn(plotLeft, plotRight - label.size.width)
            drawText(label, topLeft = Offset(lx, plotBottom + 3f))
            if (predictedClock != null) {
                val mlbl = measurer.measure(predictedClockLabel(tick.toLong(), predictedClock), modelStyle)
                var mlx = px - mlbl.size.width / 2f
                mlx = mlx.coerceIn(plotLeft, plotRight - mlbl.size.width)
                drawText(mlbl, topLeft = Offset(mlx, (plotTop - mlbl.size.height - 2f).coerceAtLeast(modelTopPx)))
            }
            tick += tStepMs
        }
        // Timezone caption on the local axis, and the model-axis tag / n/a note.
        val tzCap = measurer.measure(tzLabel(tzOffsetMin), TextStyle(color = axisColor, fontSize = 8.sp))
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

}
