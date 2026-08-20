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
    /** True when [bgValue] was taken from the roll past its validated prefix rather than from a
     *  reading or the cycle forecast. Carried because it is a true fact about where the number came
     *  from, and because the seam tests are written against it; nothing renders it. The panel draws
     *  the roll as what it is — the cycle's own forecast re-fed to itself — and the roll is kept out
     *  of every rail by TYPE rather than by a glyph. */
    val bgExtrapolated: Boolean,
    /** Raw carb appearance (grams per 5-min) at the cursor, or null when no overlay is present. */
    val carbRate: Float?,
    /** Raw insulin action (units per 5-min) at the cursor, or null when no overlay is present. */
    val insulinRate: Float?,
    /**
     * Steps at the cursor, or null when this panel has no pedometer feed wired at all — the only case
     * that omits the row.
     *
     * A bucket the pedometer never measured reads `0` here rather than null. That is a DISPLAY choice
     * and not a claim about the patient: the read-out keeps a fixed set of rows, so a line cannot
     * appear and vanish as the thumb travels and a reader scanning the box never has to notice an
     * absent one. [StepsFrame.stepsAt] still tells unmeasured from measured-zero, and the bars still
     * draw nothing across an unmeasured stretch — the coercion happens at this read-out and nowhere
     * upstream of it.
     */
    val steps: Int?,
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
    // The pedometer's per-bucket counts, drawn as bars in the same bottom band as the curve overlay.
    // A separate frame rather than a third channel on [curveOverlay]: that frame carries the two
    // MODEL-INPUT curves, reconstructed from logged events by the same resolve, and steps are neither
    // a model input nor reconstructed from anything — they are measured, and they are read from a
    // different table by a different query.
    stepsFrame: StepsFrame? = null,
    showSteps: Boolean = false,
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
    /**
     * The reconstructions to draw over the trace — an unpromoted fill and a promoted one alike.
     * DISPLAY ONLY: nothing drawn on this panel is stored, and the act that writes a reconstruction
     * into the record lives in the Lab, where the model and its numbers are named.
     */
    reconstructed: List<ReconstructedBg> = emptyList(),
    /** mg/dL → Kovatchev risk, from the Rust core. Needed only to draw a reconstruction on the risk
     *  axis; null there means the reconstruction is not drawn at all. */
    kovatchevF: ((Double) -> Double)? = null,
    /**
     * Non-null puts the panel in EDIT mode: one finger drags out a stretch of time, two or more
     * still pan and zoom. Null leaves the gesture exactly as it was.
     *
     * A drag SELECTS and does nothing else. What happens to a selection is a separate, deliberate
     * press on the edit bar — which is the whole difference from the mode this replaced, where the
     * end of a drag immediately reconstructed and there was no way back.
     *
     * Everything in it comes from the selected model's own descriptor. The app holds no geometry.
     */
    maskControls: MaskControls? = null,
    /** The stretch currently selected, drawn as a shaded column with a handle at each end. */
    editSelection: MaskSelection? = null,
    /** A drag finished: the stretch it selected, snapped and clamped, or null when it cleared it. */
    onEditSelection: ((MaskSelection?) -> Unit)? = null,
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
    // Hold the auto-follow edge out to this instant even where nothing is DRAWN there. Layout only: it
    // reserves the room a forecast would occupy, so a panel whose fan is withheld anchors on the same
    // instant as one whose fan is drawn instead of sliding the trace right by the horizon. Null ⇒ the
    // edge is whatever the drawn content reaches.
    reservedEndMs: Long? = null,
    // HINDSIGHT — every forecast the selected model issued over the visible window, swept by the
    // scrub: the fan issued at the cursor's own cycle, drawn forward over the trace that actually
    // followed it. Null ⇒ the sweep is off and nothing is drawn. DISPLAY-ONLY, like the rolled
    // forecast: these are stored rows read back, and no alarm, rail or calculator sees them.
    hindsight: HindsightFrame? = null,
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
    /** Where the RECORD begins, which is older than the first reading in [frame] whenever the caller
     *  has windowed what it loaded. The pannable domain is floored here rather than at the oldest
     *  reading held, so a windowed load cannot wall the user off from their own history; null ⇒ the
     *  caller loads everything and the first reading is the floor. */
    domainFloorMs: Long? = null,
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
    // The steps band is one path holding every visible bar, so it costs one `drawPath` per frame and
    // not one `drawRect` per bucket; `reset()` retains its capacity, so after the first frame it grows
    // no further.
    val stepBars = remember { StepBarPath() }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }
    /** Distinct from [dash] on purpose: a reconstruction and an interpolation are different claims,
     *  and two identical dashes would say they are the same one. */
    val reconDash = remember { PathEffect.dashPathEffect(floatArrayOf(2f, 5f)) }
    // Draw styles are immutable value objects with no per-frame content, so they are hoisted beside the
    // paths rather than rebuilt inside the loops that use them: the interpolated-point ring allocated
    // one per POINT (up to 241 a frame), the smoothed trace one per flush, the scrub dot one per frame.
    val interpRingStroke = remember { Stroke(width = 1.4f) }
    val smoothedStroke = remember { Stroke(width = 2.2f, cap = StrokeCap.Round) }
    val scrubDotStroke = remember { Stroke(width = 2f) }
    // Reused by the fan (three bands) and the rolled band, which allocated a native Path each, per
    // frame. `reset()` retains capacity, so after the first frame these grow no further.
    val fanPath = remember { Path() }
    val rolledPath = remember { Path() }

    /** The reconstruction fan's own scratch. Its OWN, not `fanPath`'s: the forecast overlay and this
     *  one are drawn in the same pass, and sharing one would have each reset the other's vertices. */
    val reconPath = remember { Path() }
    val labelCache = remember { GraphLabelCache() }
    // Both trace legends are the primary ink at 9 sp — the same style, since exactly one of them is ever
    // drawn (the toggle makes "smoothed" a SWAP for the raw trace, never an overlay of it).
    val traceLegendStyle = remember(cs.primary) { TextStyle(color = cs.primary, fontSize = 9.sp) }
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
    // Steps are ACTIVITY, not a glucose band and not a model channel, so they are deliberately NOT
    // given a semantic role: every one of those is spoken for — the five band roles say where the
    // glucose is, and `secondary`/`inRange` are already carbs and insulin above. Borrowing one would
    // make a busy hour read as a glucose statement. A muted neutral says "context" instead, and is
    // resolved here rather than per draw for the reason the tints above are.
    val stepsInk = remember(cs.onSurfaceVariant) { cs.onSurfaceVariant.copy(alpha = 0.38f) }
    val carbTint = remember(carbInk) { ColorFilter.tint(carbInk) }
    val insulinTint = remember(insulinInk) { ColorFilter.tint(insulinInk) }
    val carbLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.CARB) }
    val insulinLane = remember(logMarkers) { markerLane(logMarkers, CurveKind.INSULIN) }
    val markSepPx = logMarkerSeparationPx(dpPx)
    val markSizePx = LOG_MARKER_DP * dpPx

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
    //
    // [reservedEndMs] holds that edge where a forecast EXISTS but is not being drawn. Without it the
    // edge fell back to the last reading the moment the fan was withheld, and stepping between sensors
    // slid the whole trace sideways by the horizon.
    fun followEndMs(): Double {
        val fe = if (frame.isEmpty) 0.0 else frame.absMs(frame.size - 1)
        val pe = predictions.maxTsMs()?.toDouble() ?: fe
        val re = rolled?.maxTsMs?.toDouble() ?: fe
        val se = reservedEndMs?.toDouble() ?: fe
        return maxOf(fe, pe, re, se)
    }

    // The furthest the viewport may be PANNED to (I3): the data/forecast end, extended into the empty
    // future by [futureExtentMs] so the committed dose curves out there are reachable.
    fun panEndMs(): Double =
        maxOf(followEndMs(), (System.currentTimeMillis() + futureExtentMs).toDouble())

    /**
     * The oldest instant the viewport may reach. [domainFloorMs] is where the RECORD begins, which is
     * older than the first reading held whenever the panel has windowed its load — take it, so the
     * user can still pan back to a meal logged before the sensor was replaced and let the loaded
     * window follow. Falls back to the first reading on screen when no floor is supplied.
     */
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
        viewSpanMs = viewSpanMs.coerceIn(minSpan, maxSpan)
        val ds = domainStartMs()
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
    val maskOn = maskControls != null && !paintOn
    // ONE key for the pointer handler, because a re-key cancels a gesture in flight. The three
    // modes are mutually exclusive by construction — paint wins, because it is the one the user
    // turned on most recently — so an enum says exactly what a pair of booleans would and cannot
    // hold a state that does not exist.
    val gestureMode = when {
        paintOn -> GraphGesture.PAINT
        maskOn -> GraphGesture.EDIT
        else -> GraphGesture.NAVIGATE
    }
    val maskCtl by rememberUpdatedState(maskControls)
    val selNow by rememberUpdatedState(editSelection)
    val emitSel by rememberUpdatedState(onEditSelection)

    // ── The two things on this panel that MOVE between discrete states ───────────────────────────
    //
    // A selection snaps to whole patches and a τ sweep steps the fan's ladder, so both changed by
    // jumping. Each is interpolated from wherever its previous interpolation had reached, which
    // turns a run of jumps into one continuous travel; neither animation delays the state itself,
    // only how it is painted, so the finger is never waiting on the picture.
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
    // NOTE both interpolations are resolved in the DRAW phase, never here. `Animatable.value` is
    // snapshot state, so reading it in the composable body subscribes the whole ~1100-line body to
    // every animation frame; read inside the Canvas lambda it invalidates the draw phase alone,
    // which is the discipline the live paint stroke above already follows.

    var reconTween by remember { mutableStateOf(ReconTween(emptyList(), emptyList())) }
    val reconProgress = remember { Animatable(1f) }
    LaunchedEffect(reconstructed) {
        val target = reconstructed
        val drawnNow = lerpReconstruction(reconTween.from, reconTween.to, reconProgress.value)
        // Only a MOVE is interpolated: the same slots at different levels, which is what a τ sweep
        // is. A fill landing or a span leaving changes which slots exist, and sliding one row set
        // into another would draw a curve through slots the model never spoke about.
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
        onScrub?.invoke(buildScrub(frame, predictions, curveOverlay, stepsFrame, predictedClock, rolled, ms))
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
            .pointerInput(gestureMode) {
                // MASK ON: one finger drags a span, two or more pan and zoom — the same shape the
                // paint branch uses, for the same reason. A drag is horizontal only: a mask is a
                // stretch of TIME, and the vertical axis has nothing to say about it.
                if (gestureMode == GraphGesture.EDIT) {
                    // ONE hand-written loop rather than `detectDragGestures` beside
                    // `detectTransformGestures`, and for the reason the note above gives: a drag
                    // detector CONSUMES, and `detectTransformGestures` aborts the moment it sees a
                    // consumed change — so the two co-resident left the panel unpannable with two
                    // fingers for as long as edit mode was on. This is the shape the paint branch
                    // uses, counting pressed pointers itself: one finger selects, two or more pan
                    // and zoom, and a second finger landing mid-selection abandons the selection
                    // cleanly rather than smearing it across the pan.
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

                        // Resolved to an instant HERE rather than held as a pixel. The viewport
                        // moves under a drag — the forecast clock ticks the window forward on its
                        // own — and a start pixel read against a later viewport translates the
                        // whole stretch.
                        val fromTs = tsAt(startX)

                        // Which end of the EXISTING selection this gesture is dragging, if any.
                        // The OPPOSITE end becomes the anchor, and it is anchored INCLUSIVELY:
                        // `endMs` is exclusive, and handing it to `selectionOf` — which treats a
                        // boundary as lying inside the patch starting there — grew the untouched
                        // right edge by one patch on every left-handle resize.
                        var anchorTs = Long.MIN_VALUE
                        selNow?.let { sel ->
                            val ppmNow = box.width / viewSpanMs
                            val x0 = ((sel.startMs - viewStartMs) * ppmNow + box.left).toFloat()
                            val x1 = ((sel.endMs - viewStartMs) * ppmNow + box.left).toFloat()
                            val dLeft = kotlin.math.abs(startX - x0)
                            val dRight = kotlin.math.abs(startX - x1)
                            // Nearest handle wins outright. An if/else-if on the left one first
                            // takes both whenever the selection is drawn narrower than the grab
                            // radius, which is every one-patch selection on a 24 h window.
                            if (dLeft <= grabPx || dRight <= grabPx) {
                                anchorTs = if (dLeft <= dRight) sel.endMs - c.patchMs else sel.startMs
                                haptics.perform(HapticEvent.Tap)
                            }
                        }

                        var mode = EditGesture.SELECT
                        var moved = anchorTs != Long.MIN_VALUE
                        var emitted: MaskSelection? = null
                        // Latched BEFORE the loop. `selNow` tracks what this gesture has already
                        // emitted — a recomposition lands between the last emit and a second finger
                        // arriving — so restoring `selNow` on the switch would put back the stretch
                        // the aborted gesture just drew rather than the one it started from.
                        val selAtStart = selNow

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed >= 2 && mode != EditGesture.TRANSFORM) {
                                // The second finger declares intent. Anything this gesture had
                                // selected is put back, so a pan never leaves a stretch nobody
                                // aimed at highlighted.
                                if (emitted != null) sink(selAtStart)
                                mode = EditGesture.TRANSFORM
                            }
                            when (mode) {
                                EditGesture.TRANSFORM -> {
                                    // No slop gate: a pan that has to be earned would fight the
                                    // selection the other finger is still resting on.
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
                                    // A gesture shorter than the slop is a TOUCH, and a touch
                                    // selects nothing. Without it every stray contact on an armed
                                    // panel selected a patch. A handle grab is exempt: the finger
                                    // is already on the thing it means to move.
                                    if (!moved && kotlin.math.abs(ch.position.x - startX) >= slopPx) {
                                        moved = true
                                    }
                                    if (moved) {
                                        val anchor = if (anchorTs != Long.MIN_VALUE) anchorTs else fromTs
                                        val next = selectionOf(anchor, tsAt(ch.position.x), c)
                                        // EVERY move, not just the lift. Resizing that only landed
                                        // on touch-up meant dragging a handle blind and finding out
                                        // afterwards where it went.
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
                    // The OUTERMOST pair alone bounds the fan; the inner ones sit inside it.
                    if (rs.lo[0][i] < yMin) yMin = rs.lo[0][i]
                    if (rs.hi[0][i] > yMax) yMax = rs.hi[0][i]
                }
            }
            // The HINDSIGHT fan is deliberately NOT folded in, unlike the two above. Those are
            // forecasts still standing, and clipping one would hide something the panel is asserting;
            // a swept one has already been answered by the trace beside it, and it is that trace whose
            // scale carries the comparison. A forecast that went badly wrong would otherwise rescale
            // the axis until the truth it is being judged against was a flat line — and the plot clip
            // says "it left the chart" perfectly well on its own.
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
            // `onSurfaceVariant`, not a glucose colour: a reconstruction is a model's statement
            // about a slot, and drawing it in the trace's own ink would make it a glucose claim.
            val reconColor = cs.onSurfaceVariant.copy(alpha = 0.55f)
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
                labels = labelCache,
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

                // (4.45) STEP BARS in the same bottom band, drawn FIRST of the band's three layers so
                //        the two translucent curve fills read over them rather than under: a step bar
                //        is opaque measured context, and the curves are what the model made of it.
                if (stepsFrame != null && showSteps && !stepsFrame.isEmpty) {
                    fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                    drawStepsBars(
                        stepsFrame, AbsToPx(::absToPx),
                        bandTop = plotBottom - plotHeight * 0.30f, plotBottom = plotBottom,
                        color = stepsInk,
                        viewStartMs = viewStartMs, viewSpanMs = viewSpanMs,
                        dpPx = dpPx, bars = stepBars,
                    )
                }

                // (4.5) Curve overlay (carb Ra + insulin action) in the bottom band, UNDER the BG line
                //       so it never occludes the glucose trace (Phase 4 — toggleable).
                if (curveOverlay != null && curveToggles.any) {
                    fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                    val bandTop = plotBottom - plotHeight * 0.30f
                    drawCurveOverlay(
                        curveOverlay, curveToggles, AbsToPx(::absToPx), bandTop, plotBottom,
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
                //       Everything the lanes need is decided in composition: at refresh rate, the
                //       whole of this section is two translate-and-blit loops over lists already
                //       built. Nothing here animates — a mark carries no state to animate.
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

                // (4c) The model's reconstruction of a stretch of the curve, and the span the
                //      finger is selecting. DISPLAY ONLY — nothing drawn here is stored, and the act
                //      that writes one into the record lives in the Lab.
                lerpSelection(selTween.from, selTween.to, selProgress.value)?.let { s ->
                    val x0 = ((s.startMs - viewStartMs) * ppm + plotLeft).toFloat()
                    val x1 = ((s.endMs - viewStartMs) * ppm + plotLeft).toFloat()
                    if (x1 >= plotLeft && x0 <= plotRight) {
                        drawRect(
                            cs.primary.copy(alpha = 0.14f),
                            topLeft = Offset(maxOf(x0, plotLeft), plotTop),
                            size = Size(minOf(x1, plotRight) - maxOf(x0, plotLeft), plotHeight),
                        )
                        // A handle at each end, drawn full-height so the grab target is the whole
                        // edge rather than a dot the thumb has to find.
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
                // Kovatchev is a RISK axis, and `convertMgdlTo` leaves mg/dL alone in it — so a
                // reconstruction drawn through that helper lands at 120 on an axis whose whole range
                // is about ±3, i.e. off-plot. The risk transform lives in the Rust core and this
                // module keeps no copy of it, so a panel that cannot supply one draws no
                // reconstruction rather than a line in the wrong space. Thresholds already skip this
                // space for the same reason.
                val recon = lerpReconstruction(reconTween.from, reconTween.to, reconProgress.value)
                if (recon.isNotEmpty() && (frame.unit != UnitSpace.Kovatchev || kovatchevF != null)) {
                    drawReconstruction(
                        rows = recon,
                        xOf = { ts -> ((ts - viewStartMs) * ppm + plotLeft).toFloat() },
                        // The frame's own unit, through the same transforms the trace used — never
                        // a second spelling of either.
                        yOf = { mgdl ->
                            yToPx(
                                if (frame.unit == UnitSpace.Kovatchev) {
                                    (kovatchevF?.invoke(mgdl) ?: mgdl).toFloat()
                                } else {
                                    convertMgdlTo(mgdl.toFloat(), frame.unit)
                                },
                            )
                        },
                        // The forecast's own ink, because a fill and a forecast are one artifact
                        // under different inputs.
                        ink = cs.tertiary,
                        fanColor = cs.tertiary,
                        plotLeft = plotLeft,
                        plotRight = plotRight,
                        // The drawn TRACE at the bracketing slot, in pixels — whatever the panel
                        // shows there, so the reconstruction meets the curve the eye is following
                        // rather than a second reading of it. Null when the trace draws nothing at
                        // that slot, which is the ordinary case at the far end of a fill that runs
                        // to the newest measurement.
                        anchorPxAt = { ts ->
                            val i = frame.nearestIndex(ts.toDouble())
                            when {
                                i < 0 || kotlin.math.abs(frame.absMs(i) - ts) > GRID_HALF_MS -> null
                                // Never onto the model's OWN output. Joining a reconstruction to a
                                // neighbouring reconstruction and pinching the fan shut there says
                                // the two meet at something known, when both are the same guess.
                                // A carried-forward or warm-up point is still a join worth making —
                                // it is the curve the eye is following — and it keeps its own
                                // provenance styling in the trace beneath.
                                frame.flags[i] == GraphFrame.FLAG_RECONSTRUCTED -> null
                                else -> yToPx(frame.ys[i])
                            }
                        },
                        scratch = reconPath,
                    )
                }

                // (5) BG polyline, segment-styled by provenance; gaps broken. Suppressed when the smoothed
                //     model-input trace has replaced it (I5).
                if (!swapToSmoothed) for (i in iLo until iHi) {
                    if (frame.breakAfter[i]) continue
                    val fa = frame.flags[i]
                    val fb = frame.flags[i + 1]
                    // Two selections, not one `to`. Destructuring a `Pair` here allocated the pair AND
                    // boxed the `Color` (a value class, unboxed everywhere else) once per SEGMENT — 71
                    // at a 6 h window and up to `GraphFrame.maxPoints` fully zoomed out, every frame.
                    val warm = fa == GraphFrame.FLAG_WARMUP || fb == GraphFrame.FLAG_WARMUP
                    val interp = fa == GraphFrame.FLAG_INTERPOLATED || fb == GraphFrame.FLAG_INTERPOLATED
                    // The SEGMENT matters more than the marker: point markers are suppressed at
                    // 6 h and wider, so on every window the patient normally uses the polyline is
                    // the whole rendering, and a reconstruction falling through to `lineColor`
                    // would draw as an unbroken measured trace.
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

                // (6) Point markers — only when uncluttered, so distinctions stay legible. Suppressed while
                //     the smoothed trace is shown in place of the raw one (I5).
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
                    fun flush() { if (open) { drawPath(path, smColor, style = smoothedStroke); path.reset(); open = false } }
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

                // (6.4) HINDSIGHT — the forecast issued at the cursor's own cycle, swept as the thumb
                //       moves, so a day of past forecasts can be dragged across the trace that
                //       actually happened. Drawn BEFORE the live overlay so the forecast in force now
                //       stays on top of the ones that have already been answered, and in the SECOND
                //       accent (`secondary`) against the live fan's `tertiary`: the two are read
                //       together by construction here, so they must not share a hue.
                //
                //       The lookup is one bounded binary search per frame over the resident frame —
                //       no query, no allocation — which is what lets it re-anchor at pointer rate.
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

                // (6.5) Prediction overlay: quantile fan + median for each running model. Non-selected
                //       models are drawn first (faint), the selected model last (on top, full fan).
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

                // (6.6) The on-demand rolled forecast (I2), drawn in the forecast's own hand: it is
                //       the cycle's forecast re-fed to itself on the same fp32 path, and it stays
                //       display-only by TYPE — `:calc` cannot accept a `RolledForecast` — rather than
                //       by being painted to look provisional.
                rolled?.let { rs ->
                    fun absToPx(ms: Double): Float = (plotLeft + (ms - viewStartMs) * ppm).toFloat()
                    drawRolledSeries(
                        rs, AbsToPx(::absToPx), ValToPx(::yToPx),
                        cs.tertiary, cs.tertiary, rolledSeam, rolledPath,
                    )
                }

                // (7) Scrub cursor — time-anchored, so it reads in the forecast zone too (item 3). U8 — the
                //     read-out box is now PINNED at the right-hand middle of the plot (not floating by the
                //     thumb) and updates continuously as the thumb moves, so a finger never occludes it.
                if (!scrubMs.isNaN()) {
                    val cx = (plotLeft + (scrubMs - viewStartMs) * ppm).toFloat()
                    if (cx in plotLeft..plotRight) {
                        val sc = buildScrub(frame, predictions, curveOverlay, stepsFrame, predictedClock, rolled, scrubMs)
                        drawLine(cs.onSurface.copy(alpha = 0.5f), Offset(cx, plotTop), Offset(cx, plotBottom), 1f)
                        sc.bgValue?.let { drawCircle(cs.onSurface, 4f, Offset(cx, yToPx(it)), style = scrubDotStroke) }
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
                        // The maximum is floored at `plotTop` before use, because `coerceIn` THROWS
                        // when its maximum is below its minimum — and `plotBottom - boxH` drops below
                        // `plotTop` the moment the box is taller than the plot. `boxH` is a function
                        // of the ROW COUNT, so every row added to this read-out raises the plot height
                        // below which a scrub would crash rather than overflow. Pinned to the top
                        // instead, and the plot clip takes the overhang.
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

/** Sample the graph at absolute [ms] for the scrub read-out (item 3). BG comes from the reading
 *  series in the past, the selected model's median in the prediction zone, and the rolled forecast
 *  wherever no validated one reaches; carb/insulin rates from the overlay; the model clock from the
 *  circadian probe. */
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
        // Read from the frame regardless of whether the Steps overlay is being DRAWN, exactly as the
        // carb/insulin rates above are: the read-out reports what is known at the cursor, and a chip
        // governs what the band paints, not what the panel knows.
        //
        // A wired feed ALWAYS yields a row — an unmeasured bucket and a cursor off the grid both read
        // 0 rather than dropping the line, so the box keeps a fixed shape as the thumb travels. Only
        // the absence of a feed entirely omits it.
        steps = stepsFrame?.let { it.stepsAt(ms.toLong()) ?: 0 },
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
private val SCRUB_LABELS = listOf("BG", "Carb", "Ins", "Steps", "Local", "Model")

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
    // "*" marks the prediction zone. Nothing distinguishes a rolled step from a forecast one: the
    // roll IS the forecast re-fed to itself, and it cannot reach a rail, an alert or a dose whatever
    // the read-out says, because `:calc` cannot accept its type.
    val mark = if (sc.inPredZone) "*" else ""
    val bgStr = sc.bgValue?.let { formatValue(it, sc.unit) + mark } ?: "--"
    out.add("BG" to bgStr)
    sc.carbRate?.let { out.add("Carb" to "%.1f g".format(it)) }
    sc.insulinRate?.let { out.add("Ins" to "%.2f U".format(it)) }
    // Steps sits with the other measured channels, above the two clock rows.
    sc.steps?.let { out.add("Steps" to it.toString()) }
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
internal fun minValueSpan(unit: UnitSpace): Float = when (unit) {
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

/**
 * The date row's label — `January 7th, Wednesday` — for the local day [ms] falls in.
 *
 * English rather than the device locale, deliberately: the ordinal suffix has no analogue outside it,
 * so a translated month and weekday wrapped around an English `th` would read as half a translation.
 */
internal fun formatAxisDate(ms: Long, tzOffsetMin: Int): String {
    val d = Instant.ofEpochMilli(ms).atOffset(zoneOf(tzOffsetMin))
    return "${d.format(MONTH)} ${d.dayOfMonth}${ordinalSuffix(d.dayOfMonth)}, ${d.format(WEEKDAY)}"
}

/** The teens take `th` whatever their last digit would otherwise claim — 11th, 12th, 13th. */
private fun ordinalSuffix(day: Int): String = when {
    day / 10 == 1 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}

internal fun formatClock(ms: Long, tzOffsetMin: Int): String =
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
        LogMarker(t0 + 20 * step, CurveKind.CARB),
        LogMarker(t0 + 62 * step, CurveKind.CARB),
        LogMarker(t0 + 62 * step, CurveKind.INSULIN),
        LogMarker(t0 + 100 * step, CurveKind.INSULIN),
        LogMarker(t0 + 101 * step, CurveKind.INSULIN),
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

        // (1) Threshold band tints, if supplied.
        thresholds?.let { drawBands(it, unit, plotLeft, plotRight, ::yToPx, yMin, yMax, cs.error, cs.secondary) }

        // (2) Horizontal value grid + left-axis labels.
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
        // (3b) The date row, under the local-time labels: one label per calendar day in view, centred on
        //      that day's VISIBLE span. A day is named only where its span is wide enough to hold the
        //      whole label, which both keeps a sliver of a day either side of midnight from claiming a
        //      caption it cannot support and makes two labels colliding impossible — each sits inside
        //      its own stretch of axis, so the stretches being disjoint is the whole argument.
        //
        //      Suppressed once the ticks are THEMSELVES dates (`formatTime` turns to `MM-dd` at a 12 h
        //      step), where the row would only restate the label above it in a second format. That is
        //      also what bounds this loop: the step reaches 12 h by a 42 h span, so at most three days
        //      are ever walked — where the zoom ceiling is the whole stored history, and an unbounded
        //      walk would measure a label per day, on the UI thread, to draw none of them.
        if (tStepMs < 720L * 60_000L) {
            val dateStyle = TextStyle(color = labelColor, fontSize = 9.sp)
            val dateTop = plotBottom + 3f + measurer.measure("00:00", labelStyle).size.height + 1f
            // The strip below the plot is dp and the labels in it are sp, so past roughly a 1.4 font
            // scale the two rows outgrow it. Pin the date row to the strip's floor rather than let it
            // draw off the bottom of the panel — the trade the model axis already makes at its ceiling.
            val stripFloor = plotBottom + GraphInsets.Bottom.toPx()
            val dayMs = 86_400_000L
            var dayStart = floor((viewStartMs + tzMs) / dayMs) * dayMs - tzMs
            while (dayStart < endMs) {
                val visFrom = dayStart.coerceAtLeast(viewStartMs)
                val visTo = (dayStart + dayMs).coerceAtMost(endMs)
                // Any instant inside the day names it; local noon is the one no offset can push out of it.
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

// ── Edit mode's touch geometry ──────────────────────────────────────────────────────────
//
// A drag shorter than the slop selects NOTHING. On an armed panel every stray contact used to
// select a patch and reconstruct it, which is how a fill landed where nobody aimed.
private const val EDIT_DRAG_SLOP_DP = 16f

/** How far either side of a selection edge counts as grabbing that handle. */
private const val EDIT_HANDLE_GRAB_DP = 20f

/** The drawn width of a handle. Narrower than its grab target, deliberately: the bar marks the
 *  edge and the target is what the thumb actually has to hit. */
private const val EDIT_HANDLE_W_DP = 3f

/** How long the highlight takes to reach a selection's new edges. Short enough that a drag reads as
 *  the edge being pushed rather than followed, long enough to smooth a patch-sized jump. */
private const val SELECTION_TWEEN_MS = 130

/** How long the drawn line takes to reach the fan's newly-chosen level. The τ ladder has 17 stops,
 *  so a sweep across it is a run of these rather than one. */
private const val RECON_TWEEN_MS = 110

/** Half a grid step. A trace point further than this from a slot is a point at some other slot. */
private const val GRID_HALF_MS = 150_000.0
