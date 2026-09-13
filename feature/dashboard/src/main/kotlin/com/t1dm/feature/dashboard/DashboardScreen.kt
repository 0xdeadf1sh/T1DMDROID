package com.t1dm.feature.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.OnBoardReadout
import com.t1dm.core.design.LogEdit
import com.t1dm.core.design.LoggedEntryDialog
import com.t1dm.core.design.SignalBars
import com.t1dm.core.design.argbWithAlpha
import com.t1dm.core.design.crossfadeOnSwap
import com.t1dm.core.model.MaskGeometry
import com.t1dm.core.model.ReconstructedBg
import com.t1dm.core.model.SpanLinePreview
import com.t1dm.ui.graph.MaskControls
import com.t1dm.ui.graph.MaskSelection
import com.t1dm.core.design.iconStyleForTheme
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.isRealMeasurement
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.InsulinChoice
import com.t1dm.core.model.LoggedEntry
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.PaintTool
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.RolledForecast
import com.t1dm.core.model.SensitivityEstimate
import com.t1dm.core.model.TempUnit
import com.t1dm.core.model.ThermalLevel
import com.t1dm.core.model.UnitSpace
import com.t1dm.core.model.WarmupProgress
import com.t1dm.ui.graph.CurveOverlayFrame
import com.t1dm.ui.graph.CurveOverlayToggles
import com.t1dm.ui.graph.OverlayInput
import com.t1dm.ui.graph.GlucoseGraph
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.GraphInsets
import com.t1dm.ui.graph.GraphScrub
import com.t1dm.ui.graph.geometryOf
import com.t1dm.ui.graph.PaintControls
import com.t1dm.ui.graph.PaintFrame
import com.t1dm.ui.graph.PredSeries
import com.t1dm.ui.graph.HindsightFrame
import com.t1dm.ui.graph.PredictedClock
import com.t1dm.ui.graph.RolledSeries
import com.t1dm.ui.graph.SmoothedTrace
import com.t1dm.ui.graph.StepsFrame
import com.t1dm.ui.graph.hindsightFrameOf
import com.t1dm.ui.graph.paintFrameOf
import com.t1dm.ui.graph.paintsBand
import com.t1dm.ui.graph.rolledSeriesOf
import com.t1dm.ui.graph.smoothedTraceOf
import com.t1dm.ui.graph.stepsFrameOf
import com.t1dm.ui.graph.curveOverlayOf
import com.t1dm.ui.graph.graphFrameOf
import com.t1dm.ui.graph.noFutureInsulinOverForecast
import com.t1dm.ui.graph.predOverlayOf

/** Pure function of the state `:app` collects. No storage, no service, no `:inference`. */
@Composable
fun DashboardScreen(
    readings: List<CgmReading>,
    // Identity only: chart cross-fades on change (sensor or unit); null on either side skips.
    swapKey: Any? = null,
    thresholds: AlertThresholds? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    predictions: List<ModelPrediction> = emptyList(),
    kovatchevF: ((Double) -> Double)? = null,
    // `SPEC/inference.md` §8.4; DISPLAY ONLY — [predictions] stays raw for alarms, rails, storage.
    calibrateBands: ((ModelPrediction) -> List<Double>?)? = null,
    // Hindsight sweep's correction, batched: one apply, so no fan draws a different basis.
    calibrateFans: ((modelId: String, fansMgdl: () -> List<Double>, steps: Int, nQuantiles: Int) -> List<Double>?)? = null,
    iobCob: IobCobReadout? = null,
    // Display only; null ⇒ the probe could not justify a figure — never a dash or a zero.
    sensitivity: SensitivityEstimate? = null,
    // (carb, combined insulin, basal-only) for one grid window, from ONE resolve.
    curveChannels: (suspend (gridStartMs: Long, nSteps: Int) -> OverlayInput)? = null,
    // Per-bucket step counts; a lambda since this module has no `:data` dep. Null ⇒ no step bars.
    stepSeries: (suspend (gridStartMs: Long, nSteps: Int) -> IntArray)? = null,
    // Same feed the Logs panel binds, reduced to markers here so a tap's index names its row.
    logEntries: List<LoggedEntry> = emptyList(),
    /** Offered by the tapped-mark dialog when a dose is retyped. */
    insulins: List<InsulinChoice> = emptyList(),
    /** Null leaves the tapped-mark dialog read-only. */
    onEditLog: ((LoggedEntry, LogEdit) -> Unit)? = null,
    onDeleteLog: ((LoggedEntry) -> Unit)? = null,
    reconstructed: List<ReconstructedBg> = emptyList(),
    /** Non-null puts the panel in edit mode. */
    maskControls: MaskControls? = null,
    /** The geometry is derived from where the stretch sits, not chosen. */
    onFillSpan: ((MaskSelection, MaskGeometry) -> Unit)? = null,
    /** Verbatim from the runner, refusals included. */
    maskNote: String? = null,
    /** Erase every BG in `[fromMs, toMs]`, locally and on the server. Null omits the affordance. */
    onCutBg: ((fromMs: Long, toMs: Long) -> Unit)? = null,
    onUndoBgEdit: (() -> Unit)? = null,
    /** The stack lives in `:app`, not here: an edit outlives this composable. */
    canUndoBgEdit: Boolean = false,
    /** Write the selected span into the record as stored, syncable samples. */
    onPromoteSpan: ((Long) -> Unit)? = null,
    onDemoteSpan: ((Long) -> Unit)? = null,
    /** Throw an unpromoted span away. */
    onDiscardSpan: ((Long) -> Unit)? = null,
    /** Moves the line to the fan's τ-th quantile and STORES that level. Fired on release. */
    onRetauSpan: ((spanStartMs: Long, tau: Double) -> Unit)? = null,
    /** Reads the fan at a τ the thumb is still travelling through, storing nothing. */
    onPreviewTau: ((spanStartMs: Long, tau: Double) -> Unit)? = null,
    /** The uncommitted line, applied over [reconstructed]. */
    tauPreview: SpanLinePreview? = null,
    // Where the record begins vs [readings]: the panel loads a window. Null ⇒ oldest is the floor.
    historyFloorMs: Long? = null,
    onExtendHistory: (Long) -> Unit = {},
    // Selected model's horizon end, supplied with the fan withheld too; layout only, draws nothing.
    forecastEndMs: Long? = null,
    warmup: WarmupProgress? = null,
    // Suppresses the "next forecast" countdown when no forecast is being made.
    lowPowerActive: Boolean = false,
    rangeMinMgdl: Int = 20,
    rangeMaxMgdl: Int = 250,
    initialWindowHours: Int = 6,
    onSetWindowHours: ((Int) -> Unit)? = null,
    reachability: BgReachability? = null,
    signals: BgSignals? = null,
    pulses: BgPulses? = null,
    deviceTempC: Double? = null,
    temperatureUnit: TempUnit = TempUnit.CELSIUS,
    stepsToday: Int? = null,
    // Sensor expiry instant (epoch-ms): reported age + service life. Null ⇒ no countdown.
    sensorExpiryMs: Long? = null,
    // Active sensor warm-up end (epoch-ms); nullity IS the state. Distinct from [warmup]'s context.
    sensorWarmupEndMs: Long? = null,
    // Warmup-surviving belief, so the top axis renders a clock while the forecast is suppressed.
    circadianTime: PredictedTime? = null,
    circadianAnchorMs: Long? = null,
    // SavGol smoother (mg/dL, clamps [20,500]); [smoothingWindow] only busts the memo cache.
    smoothMgdl: ((DoubleArray) -> DoubleArray)? = null,
    smoothingWindow: Int = 7,
    // Ephemeral, DISPLAY-ONLY forecast; never drives an alert/dose. [onRoll] rolls to hours ahead.
    rolledForecast: RolledForecast? = null,
    rollComputing: Boolean = false,
    onRoll: ((Double) -> Unit)? = null,
    onClearRoll: (() -> Unit)? = null,
    // Adaptive: a cycle runs on every reading, no countdown. Timed: ticks to next period boundary.
    forecastAdaptive: Boolean = true,
    forecastPeriodMin: Int = 5,
    // The thermal-gate threshold in battery-sensor °C; null ⇒ the gate is disabled.
    thermalThresholdC: Double? = null,
    thermalWarnMarginC: Double = 3.0,
    // Freehand annotation layer, collected by `:app`. In-app only — never widget, notif, or watch.
    paintStrokes: List<PaintStroke> = emptyList(),
    // `suspend`: insert hands back the row id undo/erase address by. Null either ⇒ no paint toggle.
    onAddPaintStroke: (suspend (PaintStroke) -> Long)? = null,
    onDeletePaintStroke: (suspend (Long) -> Unit)? = null,
    // Selected model's stored forecasts; a resolver so the window follows the viewport, off-thread.
    hindsightIn: (suspend (modelId: String, fromMs: Long, toMs: Long) -> List<ModelPrediction>)? = null,
    // Hill-climb minigame; terrain IS this panel's trace. A mode, not a destination. Null ⇒ off.
    gameSlot: (@Composable (Modifier, trackFromMs: Long, dropAtMs: Long, spanMinutes: Float, predictedClock: PredictedClock?, onReady: () -> Unit, exit: () -> Unit) -> Unit)? = null,
) {
    val logMarkers = remember(logEntries) { logEntries.map { it.marker } }
    // Held by VALUE: the feed re-sorts and rows can be deleted; the dialog keeps restating the tap.
    var tappedLogs by remember { mutableStateOf<List<LoggedEntry>>(emptyList()) }
    var gameOn by remember { mutableStateOf(false) }
    // Chart's LIVE viewport, moved by pinch/pan; drive mode adopts it, a tap turns into an instant.
    var viewStartMs by remember { mutableStateOf(0.0) }
    var viewSpanMs by remember { mutableStateOf(0.0) }
    // Where the car is to be dropped: the instant under the finger. Null until the user picks.
    var gameStartMs by remember { mutableStateOf<Long?>(null) }
    var gameReady by remember { mutableStateOf(false) }
    LaunchedEffect(gameOn) { if (!gameOn) { gameStartMs = null; gameReady = false } }
    LaunchedEffect(gameStartMs) { if (gameStartMs == null) gameReady = false }
    val frame by produceState(GraphFrame.EMPTY, readings, unit) {
        value = graphFrameOf(readings, unit, kovatchevF = kovatchevF)
    }
    // Built BEFORE the forecast overlay: the correction below is decided on it.
    val rolledSeries by produceState<RolledSeries?>(null, rolledForecast, unit) {
        value = rolledSeriesOf(rolledForecast, unit, kovatchevF)
    }

    // [RolledSeries.paintsBand]: a roll exists but past the validated horizon only a median draws.
    val rollOnPanel = rolledSeries?.paintsBand() == true

    // Only the selected fan paints; §8.4 correction drops with a rolled band up (avoids 2 bases).
    val overlay by produceState(emptyList<PredSeries>(), predictions, unit, calibrateBands, rollOnPanel) {
        value = predOverlayOf(
            predictions.filter { it.selected },
            unit,
            kovatchevF = kovatchevF,
            calibrateBands = calibrateBands.takeIf { !rollOnPanel },
        )
    }

    var toggles by remember { mutableStateOf(CurveOverlayToggles()) }
    var windowHours by remember(initialWindowHours) { mutableStateOf(initialWindowHours) }
    var showRollDialog by remember { mutableStateOf(false) }


    // Carb/insulin/exercise channels, extended into the future for tails; not gated on the toggles.
    val curveOverlay by produceState(CurveOverlayFrame.EMPTY, readings, predictions, curveChannels, iobCob, rolledForecast) {
        val resolver = curveChannels
        if (resolver == null || readings.isEmpty()) {
            value = CurveOverlayFrame.EMPTY
            return@produceState
        }
        // Ends, not scans: `observeReadings` is `ORDER BY tsMs` asc, so bounds are list ends.
        val oldestReading = readings.first().tsMs / STEP_MS * STEP_MS
        val lastReading = readings.last().tsMs
        val lastForecast = predictions.maxOfOrNull { it.anchorTsMs + it.horizonSteps.toLong() * it.stepMs } ?: lastReading
        val rolledEnd = rolledForecast?.takeUnless { it.isEmpty }?.horizonEndMs ?: lastReading
        // Reaches past now: a just-logged dose's tail shows pre-forecast, across the pannable span.
        val end = maxOf(lastReading, lastForecast, rolledEnd, System.currentTimeMillis() + maxOf(OVERLAY_FUTURE_MS, FUTURE_VIEW_MS))
        // Anchored on the recent end: a re-sync can push `readings` back weeks and strand the cap.
        val earliestStart = ((end / STEP_MS) - (MAX_OVERLAY_STEPS - 1L)) * STEP_MS
        val gridStart = maxOf(oldestReading, earliestStart)
        val nSteps = (((end - gridStart) / STEP_MS).toInt() + 1).coerceIn(1, MAX_OVERLAY_STEPS)
        val ch = resolver(gridStart, nSteps)
        value = curveOverlayOf(ch.carb, ch.insulin, gridStart, STEP_MS, ch.basal, ch.exercise)
    }

    // No insulin over the horizon: no bolus tail, no basal (folded into the channel). Advisory.
    val noFutureInsulin = remember(curveOverlay, predictions) {
        noFutureInsulinOverForecast(curveOverlay, predictions, System.currentTimeMillis())
    }

    val smoothed by produceState<SmoothedTrace?>(null, readings, unit, smoothMgdl, smoothingWindow) {
        val f = smoothMgdl
        value = if (f == null || readings.isEmpty()) null
        else smoothedTraceOf(readings, unit, f, kovatchevF)
    }
    var showSmoothed by remember { mutableStateOf(false) }

    // Steps window's right edge is the CLOCK, not readings — steps accrue while CGM is dropped.
    val stepGridTick by produceState(0L) {
        while (true) {
            value = System.currentTimeMillis() / STEP_MS
            kotlinx.coroutines.delay(30_000)
        }
    }
    val stepsFrame by produceState<StepsFrame?>(null, readings, stepSeries, stepGridTick) {
        val load = stepSeries
        if (load == null || readings.isEmpty()) {
            value = null
            return@produceState
        }
        // Capped to the curve overlay's window, anchored on recent end; no future — no pedometer.
        val oldest = readings.first().tsMs / STEP_MS * STEP_MS
        val newest = maxOf(readings.last().tsMs, System.currentTimeMillis()) / STEP_MS * STEP_MS
        val earliest = ((newest / STEP_MS) - (MAX_OVERLAY_STEPS - 1L)) * STEP_MS
        val gridStart = maxOf(oldest, earliest)
        val nSteps = (((newest - gridStart) / STEP_MS).toInt() + 1).coerceIn(1, MAX_OVERLAY_STEPS)
        value = stepsFrameOf(load(gridStart, nSteps), gridStart, STEP_MS)
    }

    // Built once off-thread; the Canvas culls per viewport, so a pan or a zoom never rebuilds it.
    val paint by produceState<PaintFrame?>(null, paintStrokes) {
        value = if (paintStrokes.isEmpty()) null else paintFrameOf(paintStrokes)
    }

    var showHindsight by remember { mutableStateOf(false) }
    // Bucketed to the hour, re-reading on crossings not per frame; reaches HINDSIGHT_LEAD_MS back.
    val hindsightBucket = remember(viewStartMs, viewSpanMs, showHindsight) {
        // viewStartMs is 0.0 until the panel has laid out and reported its viewport once.
        if (!showHindsight || viewSpanMs <= 0.0 || viewStartMs <= 0.0) null
        else {
            val from = ((viewStartMs - HINDSIGHT_LEAD_MS) / HINDSIGHT_BUCKET_MS).toLong() * HINDSIGHT_BUCKET_MS
            val to = ((viewStartMs + viewSpanMs) / HINDSIGHT_BUCKET_MS).toLong() * HINDSIGHT_BUCKET_MS + HINDSIGHT_BUCKET_MS
            // A pinch can zoom out far past the window chips; cap what one sweep may hold resident.
            from.coerceAtLeast(to - HINDSIGHT_MAX_SPAN_MS) to to
        }
    }
    // The model whose fan is live on the panel, so the comparison is a comparison.
    val hindsightSelected = predictions.firstOrNull { it.selected }
    val hindsightModelId = hindsightSelected?.modelId
    // A key, not a producer value: without it the sweep misses cycles since the bucket last moved.
    val hindsightLatestCycleMs = hindsightSelected?.cycleTsMs
    // Keyed on [calibrateFans] so a fresh §8.4 fit redraws the sweep.
    val hindsight by produceState<HindsightFrame?>(
        null, hindsightBucket, hindsightModelId, hindsightLatestCycleMs, unit, kovatchevF, hindsightIn,
        calibrateFans, rollOnPanel,
    ) {
        val resolve = hindsightIn
        val bucket = hindsightBucket
        val modelId = hindsightModelId
        // Gated with the overlay: a calibrated sweep beside a raw fan is two bases on one plot.
        val calibrate = calibrateFans.takeIf { !rollOnPanel }
        value = if (resolve == null || bucket == null || modelId == null) null
        else hindsightFrameOf(
            resolve(modelId, bucket.first, bucket.second),
            unit,
            kovatchevF,
            calibrate?.let { cf -> { fans, steps, nq -> cf(modelId, fans, steps, nq) } },
        )
    }

    // Transient like [showSmoothed]: only the strokes are durable, and live behind the callbacks.
    val paintHaptics = rememberT1dmHaptics()
    val paintScope = rememberCoroutineScope()
    val paintAvailable = onAddPaintStroke != null && onDeletePaintStroke != null
    var paintOn by remember { mutableStateOf(false) }
    val editAvailable = maskControls != null && (onFillSpan != null || onCutBg != null)
    var editOn by remember(editAvailable) { mutableStateOf(false) }
    // One stretch, not a set: the bar must act on the thing the panel is highlighting.
    var editSelection by remember { mutableStateOf<MaskSelection?>(null) }
    var showFills by remember { mutableStateOf(true) }
    var confirmCut by remember { mutableStateOf<MaskSelection?>(null) }
    // The panel has `weight`, so this is its complement: shrinking this grows the graph.
    var controlsHeightDp by remember { mutableStateOf(CONTROLS_HEIGHT_MAX_DP) }
    // Held past release: a control that lived only while a finger was down could not be pressed.
    var scrubbed by remember { mutableStateOf<GraphScrub?>(null) }
    var paintTool by remember { mutableStateOf(PaintTool.DEFAULT) }
    var paintErasing by remember { mutableStateOf(false) }
    var paintWidthDp by remember { mutableStateOf(PaintTool.DEFAULT.defaultWidthDp) }
    var showPaintStyle by remember { mutableStateOf(false) }
    val defaultInk = LocalT1dmSemantics.current.inRange.toArgb()
    var paintColor by remember(defaultInk) {
        mutableStateOf(argbWithAlpha(defaultInk, PaintTool.DEFAULT.defaultAlpha))
    }
    val paintUndo = remember { mutableStateListOf<PaintUndoOp>() }
    val paintRedo = remember { mutableStateListOf<PaintUndoOp>() }

    fun commitStroke(stroke: PaintStroke) {
        val add = onAddPaintStroke ?: return
        paintScope.launch {
            val op = PaintUndoOp(stroke.withId(add(stroke)), added = true)
            paintUndo.add(op)
            paintRedo.clear()
        }
    }

    fun eraseStroke(id: Long) {
        val del = onDeletePaintStroke ?: return
        // Captured BEFORE the delete: once the row is gone, undo has nothing to put back.
        val victim = paintStrokes.firstOrNull { it.id == id } ?: return
        paintScope.launch {
            del(id)
            paintUndo.add(PaintUndoOp(victim, added = false))
            paintRedo.clear()
        }
    }

    fun undoPaint() {
        if (paintUndo.isEmpty()) return
        val op = paintUndo.removeAt(paintUndo.lastIndex)
        paintHaptics.perform(HapticEvent.Tap)
        paintScope.launch {
            if (op.added) onDeletePaintStroke?.invoke(op.stroke.id)
            else onAddPaintStroke?.invoke(op.stroke)?.let { op.stroke = op.stroke.withId(it) }
            paintRedo.add(op)
        }
    }

    fun redoPaint() {
        if (paintRedo.isEmpty()) return
        val op = paintRedo.removeAt(paintRedo.lastIndex)
        paintHaptics.perform(HapticEvent.Tap)
        paintScope.launch {
            if (op.added) onAddPaintStroke?.invoke(op.stroke)?.let { op.stroke = op.stroke.withId(it) }
            else onDeletePaintStroke?.invoke(op.stroke.id)
            paintUndo.add(op)
        }
    }

    // Rows as DRAWN: stored fan plus any uncommitted τ line. One list feeds panel and edit bar.
    val shown = remember(reconstructed, tauPreview) {
        val p = tauPreview
        if (p == null) {
            reconstructed
        } else {
            reconstructed.map { row ->
                val v = if (row.spanStartMs == p.spanStartMs) p.mgdl[row.tsMs] else null
                if (v == null) row else row.copy(mgdl = v, tau = p.tau)
            }
        }
    }
    val sel = editSelection
    val selRows = remember(sel, shown) {
        sel?.let { r -> shown.filter { it.tsMs >= r.startMs && it.tsMs < r.endMs } }.orEmpty()
    }
    // One span, or none: a selection straddling two spans names neither.
    val selSpan = remember(selRows) {
        selRows.map { it.spanStartMs }.distinct().singleOrNull()?.let { start ->
            start to selRows.first()
        }
    }
    val cutCount = remember(sel, readings) {
        sel?.let { r ->
            readings.count {
                it.tsMs >= r.startMs && it.tsMs < r.endMs && it.bgMgdl != null &&
                    isRealMeasurement(it.provenance, it.flag)
            }
        } ?: 0
    }
    val editState = BgEditState(
        hasSelection = sel != null,
        cutCount = cutCount,
        // Named by the geometry it would run: a forecast is not stored and an infill may be.
        fillLabel = sel?.takeIf { onFillSpan != null && maskControls?.fromDescriptor == true }?.let {
            when (geometryOf(it, maskControls!!)) {
                MaskGeometry.FORECAST -> "Forecast"
                MaskGeometry.BACKCAST -> "Backcast"
                MaskGeometry.INFILL -> "Fill"
            }
        },
        spanStartMs = selSpan?.first,
        spanPromoted = selSpan?.second?.promoted == true,
        // A pre-fan row has two edges and nothing between them; there is no level to sweep to.
        spanTauSweepable = selSpan != null && selSpan.second.promoted.not() &&
            selRows.all { it.bands.isNotEmpty() } && onRetauSpan != null,
        tau = selSpan?.second?.tau ?: 0.5,
        canUndo = canUndoBgEdit,
        busy = false,
    )

    // Prefer the in-cycle belief, else the warmup-surviving one, so the clock outlasts warmup.
    val selected = predictions.firstOrNull { it.selected }
    val clockSource = selected?.predictedTime ?: circadianTime
    val clockAnchor = selected?.anchorTsMs ?: circadianAnchorMs
    val predictedClock = if (clockSource != null && clockAnchor != null) clockSource.toClock(clockAnchor) else null

    Column(Modifier.fillMaxSize()) {
        reachability?.let {
            ReachabilityBar(it, signals, pulses, deviceTempC, temperatureUnit, sensorExpiryMs, sensorWarmupEndMs, thermalThresholdC, thermalWarnMarginC, stepsToday)
        }
        warmup?.let { WarmupBanner(it) }
        if (noFutureInsulin) NoFutureInsulinBanner()
        // Only a failure speaks: a row rendered anyway took its height out of the panel's weight.
        rolledForecast?.takeIf { it.reason != null || it.isEmpty }?.let { rf ->
            RolledStatusBanner(rf, onClear = onClearRoll)
        }
        val panelModifier = Modifier.fillMaxWidth().weight(1f)
        // Drawn OVER the panel; declared after the chart, so toolbars take the touch first.
        @Composable
        fun PanelToolbars() {
            if (paintOn && paintAvailable) {
                PaintPalette(
                    tool = paintTool,
                    erasing = paintErasing,
                    colorArgb = paintColor,
                    canUndo = paintUndo.isNotEmpty(),
                    canRedo = paintRedo.isNotEmpty(),
                    onSelectTool = { t ->
                        paintErasing = false
                        paintTool = t
                        paintWidthDp = t.defaultWidthDp
                        paintColor = seedInk(t, paintColor)
                    },
                    onSelectEraser = { paintErasing = !paintErasing },
                    onOpenStyle = { showPaintStyle = true },
                    onUndo = { undoPaint() },
                    onRedo = { redoPaint() },
                )
            }
            if (editOn && editAvailable) {
                BgEditBar(
                    state = editState,
                    note = maskNote,
                    showSpans = showFills,
                    onDone = { editOn = false; editSelection = null },
                    onCut = { editSelection?.let { confirmCut = it } },
                    onFill = {
                        val c = maskControls
                        val s2 = editSelection
                        if (c != null && s2 != null) onFillSpan?.invoke(s2, geometryOf(s2, c))
                    },
                    onTau = { t -> editState.spanStartMs?.let { onRetauSpan?.invoke(it, t) } },
                    onTauPreview = { t -> editState.spanStartMs?.let { onPreviewTau?.invoke(it, t) } },
                    onPromote = { editState.spanStartMs?.let { onPromoteSpan?.invoke(it) } },
                    onDemote = { editState.spanStartMs?.let { onDemoteSpan?.invoke(it) } },
                    onDiscard = { editState.spanStartMs?.let { onDiscardSpan?.invoke(it) } },
                    onUndo = { onUndoBgEdit?.invoke() },
                    onToggleSpans = { showFills = it },
                )
            }
        }
        val slot = gameSlot
        val spanMin = if (viewSpanMs > 0.0) (viewSpanMs / 60_000.0).toFloat() else windowHours * 60f
        val dropAt = gameStartMs
        Box(panelModifier) {
            // ONE call site: two branches sit at two composition spots and DISPOSE on flip.
            if (gameOn && slot != null && dropAt != null) {
                slot(Modifier.fillMaxSize(), viewStartMs.toLong(), dropAt, spanMin, predictedClock, { gameReady = true }) {
                    gameOn = false
                }
            }
            // Chart stays ON TOP until the game can draw, then cross-fades; no loading-gap flash.
            val motionOn = LocalAnimationsEnabled.current
            val handOff = gameOn && dropAt != null && gameReady
            val chartAlpha by animateFloatAsState(
                targetValue = if (handOff) 0f else 1f,
                animationSpec = tween(if (motionOn) 220 else 0),
                label = "chartHandOff",
            )
            if (chartAlpha > 0.001f) {
                // Its own layer: an alpha change stays a RenderNode property, never re-records.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = chartAlpha }
                        .crossfadeOnSwap(swapKey),
                ) {
                GlucoseGraph(
                frame = frame,
                modifier = Modifier.fillMaxSize(),
            thresholds = thresholds,
            initialWindowMin = windowHours * 60f,
            predictions = overlay,
            curveOverlay = curveOverlay,
            curveToggles = toggles,
            stepsFrame = stepsFrame,
            logMarkers = logMarkers,
            onMarkerTap = { hits -> tappedLogs = hits.mapNotNull { logEntries.getOrNull(it) } },
            onScrub = { s -> if (s != null) scrubbed = s },
            reconstructed = if (showFills) shown else emptyList(),
            kovatchevF = kovatchevF,
            // Null unless the chip is lit: a non-null `maskControls` IS edit mode in the graph.
            maskControls = if (editOn) maskControls else null,
            editSelection = editSelection,
            onEditSelection = { editSelection = it },
            rangeMinMgdl = rangeMinMgdl,
            rangeMaxMgdl = rangeMaxMgdl,
            predictedClock = predictedClock,
            smoothed = smoothed,
            showSmoothed = showSmoothed,
            rolled = rolledSeries,
            hindsight = hindsight,
            futureExtentMs = FUTURE_VIEW_MS,
            reservedEndMs = forecastEndMs,
            domainFloorMs = historyFloorMs,
            onViewportChange = { st, sp ->
                viewStartMs = st
                viewSpanMs = sp
                // One span of slack, so the next chunk loads before a pan reaches the loaded edge.
                val oldestHeld = readings.firstOrNull()?.tsMs
                val floor = historyFloorMs
                if (oldestHeld != null && floor != null && floor < oldestHeld && st - sp <= oldestHeld) {
                    onExtendHistory((st - sp).toLong().coerceAtLeast(floor))
                }
            },
            paint = paint,
            paintControls = if (paintOn && paintAvailable) {
                PaintControls(paintTool.key, paintColor, paintWidthDp, paintErasing)
            } else null,
                    onPaintStroke = { stroke -> commitStroke(stroke) },
                    onErasePaintStroke = { id -> eraseStroke(id) },
                )
                // Only before the car is placed: once dropped, a stray tap must not re-place it.
                if (gameOn && slot != null && dropAt == null) {
                    TapToPlace(viewStartMs, viewSpanMs, GraphInsets.top(predictedClock != null)) { gameStartMs = it }
                }
                }
            }
            // Declared last: a chip press is taken here, never the panel's gesture handler beneath.
            if ((paintOn && paintAvailable) || (editOn && editAvailable)) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        // Records the hit only, so dead ground reaches the panel; CONSUMES NOTHING.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent()
                            }
                        },
                ) { PanelToolbars() }
            }
        }
        if (iobCob != null || sensitivity != null || curveChannels != null || smoothMgdl != null ||
            onRoll != null || paintAvailable || gameSlot != null || hindsightIn != null ||
            stepSeries != null || editAvailable
        ) {
            PanelResizeHandle(
                heightDp = controlsHeightDp,
                onHeightDp = { controlsHeightDp = it },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    // A CAP, never a fixed height: fixed padded the block out with empty surface.
                    .heightIn(max = controlsHeightDp.dp)
                    .clipToBounds(),
            ) {
            OverlayControls(
                iobCob = iobCob,
                sensitivity = sensitivity,
                unit = unit,
                showNextForecast = warmup == null && !lowPowerActive,
                forecastAdaptive = forecastAdaptive,
                forecastPeriodMin = forecastPeriodMin,
                toggles = toggles,
                windowHours = windowHours,
                smoothAvailable = smoothMgdl != null,
                showSmoothed = showSmoothed,
                hindsightAvailable = hindsightIn != null,
                showHindsight = showHindsight,
                rollAvailable = onRoll != null,
                rollComputing = rollComputing,
                rollClearable = rolledForecast?.let { !it.isEmpty } == true && onClearRoll != null,
                onClearRoll = { onClearRoll?.invoke() },
                paintAvailable = paintAvailable,
                paintOn = paintOn,
                editAvailable = editAvailable,
                editOn = editOn,
                gameAvailable = gameSlot != null,
                gameOn = gameOn,
                onToggleGame = { gameOn = it },
                onRollClick = { showRollDialog = true },
                onToggle = { toggles = it },
                onToggleSmoothed = { showSmoothed = it },
                onToggleHindsight = { showHindsight = it },
                onTogglePaint = { on -> paintOn = on; if (on) editOn = false },
                onToggleEdit = { on ->
                    editOn = on
                    if (on) paintOn = false else editSelection = null
                },
                onWindow = { h ->
                    windowHours = h
                    onSetWindowHours?.invoke(h)
                },
            )
            }
        }
    }

    if (tappedLogs.isNotEmpty()) {
        LoggedEntryDialog(
            entries = tappedLogs,
            insulins = insulins,
            onEdit = onEditLog,
            onDelete = onDeleteLog,
        ) { tappedLogs = emptyList() }
    }

    if (showPaintStyle) {
        PaintStyleDialog(
            colorArgb = paintColor,
            widthDp = paintWidthDp,
            onColorChange = { paintColor = it },
            onWidthChange = { paintWidthDp = it.coerceIn(PAINT_WIDTH_MIN_DP, PAINT_WIDTH_MAX_DP) },
            onDismiss = { showPaintStyle = false },
        )
    }

    confirmCut?.let { range ->
        val haptics = rememberT1dmHaptics()
        val tz = scrubbed?.tzOffsetMin ?: readings.lastOrNull()?.tzOffsetMin ?: 0
        LaunchedEffect(range) { haptics.perform(HapticEvent.Warn) }
        AlertDialog(
            onDismissRequest = { haptics.perform(HapticEvent.Reject); confirmCut = null },
            confirmButton = {
                TextButton(onClick = {
                    haptics.perform(HapticEvent.Confirm)
                    // The end is exclusive on the panel and inclusive on the grid the store keys.
                    onCutBg?.invoke(range.startMs, range.endMs - STEP_MS)
                    confirmCut = null
                }) { Text("Cut") }
            },
            dismissButton = {
                TextButton(onClick = { haptics.perform(HapticEvent.Reject); confirmCut = null }) {
                    Text("Cancel")
                }
            },
            title = {
                Text("Cut $cutCount from " + hhmm(range.startMs, tz) + "–" + hhmm(range.endMs - STEP_MS, tz))
            },
            // The cut reaches the server the moment it is made; the undo is held in memory.
            text = { Text("Erased here and on the server. Undo holds until you leave the app.") },
        )
    }

    if (showRollDialog) {
        RollConfirmDialog(
            onDismiss = { showRollDialog = false },
            onConfirm = { hours ->
                showRollDialog = false
                onRoll?.invoke(hours)
            },
        )
    }
}

/** [added] says which direction undo runs. [stroke] is `var`: a re-insert mints a NEW row id. */
private class PaintUndoOp(var stroke: PaintStroke, val added: Boolean)

/** Spelled out: [PaintStroke] is not a data class — array fields would give it a lying `equals`. */
private fun PaintStroke.withId(newId: Long): PaintStroke =
    PaintStroke(newId, createdAtMs, tool, colorArgb, widthDp, tsMs, yFrac)

/** The slot's OWN stored offset, never the phone's current zone — `SPEC/invariants.md` §2. */
private fun hhmm(tsMs: Long, tzOffsetMin: Int): String {
    val mins = Math.floorMod((tsMs / 60_000L) + tzOffsetMin, 1440L).toInt()
    return "%02d:%02d".format(mins / 60, mins % 60)
}

private fun rollHoursLabel(hours: Double): String {
    val totalHalf = Math.round(hours * 2).toInt() // in 30-min units
    val h = totalHalf / 2
    val half = totalHalf % 2 == 1
    return when {
        h == 0 -> "30 min"
        half -> "$h h 30 min"
        else -> "$h h"
    }
}

/** Beyond 2 h the roll is EXTRAPOLATED, unvalidated — inspection only, never raises an alert. */
@Composable
private fun RollConfirmDialog(onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    // Slider stops 1..24 map to 0.5 h … 12 h in 30-min steps; default index 4 = 2 h.
    var stepIdx by remember { mutableStateOf(4f) }
    val hours = (stepIdx.roundToInt().coerceIn(1, 24)) * 0.5
    val rolls = Math.ceil(hours / 2.0).toInt()
    val haptics = rememberT1dmHaptics()
    LaunchedEffect(Unit) { haptics.perform(HapticEvent.Warn) }
    // Keyed on the ROUNDED index, never the raw Float: Slider reports continuous values.
    val stopDetent = rememberHapticDetent()
    AlertDialog(
        onDismissRequest = { haptics.perform(HapticEvent.Reject); onDismiss() },
        confirmButton = {
            TextButton(
                onClick = { haptics.perform(HapticEvent.Confirm); onConfirm(hours) },
            ) { Text("Roll ${rollHoursLabel(hours)}") }
        },
        dismissButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); onDismiss() }) { Text("Cancel") }
        },
        title = { Text("Roll the forecast") },
        text = {
            Column {
                Text(
                    "Re-feeds the forecast $rolls time${if (rolls == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text("Horizon: ${rollHoursLabel(hours)}", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = stepIdx,
                    onValueChange = { stepIdx = it; stopDetent.at(it.roundToInt()) },
                    valueRange = 1f..24f,
                    steps = 22, // 24 discrete stops (endpoints + 22 interior)
                )
                Text(
                    "30 min–12 h · $rolls forward${if (rolls == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
    )
}

@Composable
private fun RolledStatusBanner(rf: RolledForecast, onClear: (() -> Unit)?) {
    // Only a FAILURE speaks: a whole roll is drawn on the panel in the forecast's own hand.
    val msg = when {
        rf.reason != null -> rf.reason!!
        rf.isEmpty -> "No rolled forecast"
        else -> null
    }
    val haptics = rememberT1dmHaptics()
    // A degenerate roll lands while watched, so it warns; keyed on the flag, a redraw stays silent.
    LaunchedEffect(rf.degenerate) { if (rf.degenerate) haptics.perform(HapticEvent.Warn) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            msg.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = if (rf.degenerate) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f),
        )
        onClear?.let {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); it() }) { Text("Clear") }
        }
    }
}

/** Null for a diffuse (low-R) belief, whose hour is effectively undefined. */
private fun PredictedTime.toClock(anchorTsMs: Long): PredictedClock? =
    if (resultantR <= MIN_CLOCK_R) null
    else PredictedClock(predictedHour = predictedHour, anchorTsMs = anchorTsMs, resultantR = resultantR)

private const val STEP_MS: Long = 300_000L
private const val MAX_OVERLAY_STEPS: Int = 4032 // ~14 days of 5-min buckets
private const val OVERLAY_FUTURE_MS: Long = 6L * 3_600_000L // show ~6 h of future dose tails
private const val FUTURE_VIEW_MS: Long = 24L * 3_600_000L // +24 h future-view extent

// Bucketed to the hour, re-reading on crossings; capped so one sweep can't hold weeks resident.
private const val HINDSIGHT_BUCKET_MS: Long = 3_600_000L
private const val HINDSIGHT_LEAD_MS: Long = 2L * 3_600_000L
private const val HINDSIGHT_MAX_SPAN_MS: Long = 48L * 3_600_000L
/** Below this resultant length the belief is too diffuse to anchor a clock axis on. */
private const val MIN_CLOCK_R: Double = 0.05

/** Below the configured MEASURED-context hours the overlay is empty; states the progress. */
@Composable
private fun WarmupBanner(warmup: WarmupProgress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "Collecting context — %.1f / %.0f h BG".format(warmup.measuredHours, warmup.requiredHours),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(
            progress = { warmup.fraction.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** Advisory only; the app never actuates. */
@Composable
private fun NoFutureInsulinBanner() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "No insulin on board over the forecast",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun OverlayControls(
    iobCob: IobCobReadout?,
    sensitivity: SensitivityEstimate?,
    unit: UnitSpace,
    showNextForecast: Boolean,
    forecastAdaptive: Boolean,
    forecastPeriodMin: Int,
    toggles: CurveOverlayToggles,
    windowHours: Int,
    smoothAvailable: Boolean,
    showSmoothed: Boolean,
    hindsightAvailable: Boolean,
    showHindsight: Boolean,
    rollAvailable: Boolean,
    rollComputing: Boolean,
    /** True only while a roll is actually drawn. */
    rollClearable: Boolean,
    onClearRoll: () -> Unit,
    paintAvailable: Boolean,
    paintOn: Boolean,
    editAvailable: Boolean,
    editOn: Boolean,
    gameAvailable: Boolean,
    gameOn: Boolean,
    onToggleGame: (Boolean) -> Unit,
    onRollClick: () -> Unit,
    onToggle: (CurveOverlayToggles) -> Unit,
    onToggleSmoothed: (Boolean) -> Unit,
    onToggleHindsight: (Boolean) -> Unit,
    onTogglePaint: (Boolean) -> Unit,
    onToggleEdit: (Boolean) -> Unit,
    onWindow: (Int) -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rollAvailable) {
                AssistChip(
                    onClick = { if (!rollComputing) { haptics.perform(HapticEvent.Tap); onRollClick() } },
                    enabled = !rollComputing,
                    label = { Text(if (rollComputing) "Rolling…" else "Roll") },
                    leadingIcon = if (rollComputing) {
                        { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) }
                    } else null,
                )
            }
            if (rollClearable) {
                AssistChip(
                    onClick = { haptics.perform(HapticEvent.Reject); onClearRoll() },
                    label = { Text("Clear roll") },
                )
            }
            // Latches, not a single-choice group: each speaks ToggleOn/ToggleOff, not a detent.
            FilterChip(
                selected = toggles.carbs,
                onClick = {
                    haptics.toggled(!toggles.carbs)
                    onToggle(toggles.copy(carbs = !toggles.carbs))
                },
                label = { Text("Carbs") },
            )
            FilterChip(
                selected = toggles.insulin,
                onClick = {
                    haptics.toggled(!toggles.insulin)
                    onToggle(toggles.copy(insulin = !toggles.insulin))
                },
                label = { Text("Insulin") },
            )
            FilterChip(
                selected = toggles.exercise,
                onClick = {
                    haptics.toggled(!toggles.exercise)
                    onToggle(toggles.copy(exercise = !toggles.exercise))
                },
                label = { Text("Exercise") },
            )
            if (smoothAvailable) {
                FilterChip(
                    selected = showSmoothed,
                    onClick = { haptics.toggled(!showSmoothed); onToggleSmoothed(!showSmoothed) },
                    label = { Text("Smoothed") },
                )
            }
            // Arms the sweep; it draws nothing until a long-press scrub is under way.
            if (hindsightAvailable) {
                FilterChip(
                    selected = showHindsight,
                    onClick = { haptics.toggled(!showHindsight); onToggleHindsight(!showHindsight) },
                    label = { Text("Hindsight") },
                )
            }
            // While on, one finger draws and 2+ pan/zoom; the long-press scrub is suspended.
            if (paintAvailable) {
                FilterChip(
                    selected = paintOn,
                    onClick = { haptics.toggled(!paintOn); onTogglePaint(!paintOn) },
                    label = { Text("Paint") },
                )
            }
            // Mutually exclusive with Paint at the caller: both claim the same horizontal drag.
            if (editAvailable) {
                FilterChip(
                    selected = editOn,
                    onClick = { haptics.toggled(!editOn); onToggleEdit(!editOn) },
                    label = { Text("Edit") },
                )
            }
            if (gameAvailable) {
                FilterChip(
                    selected = gameOn,
                    onClick = { haptics.toggled(!gameOn); onToggleGame(!gameOn) },
                    label = { Text("Drive") },
                )
            }
            // The window buttons ARE a single-choice group — a detent, not a latch.
            listOf(6, 12, 24).forEach { h ->
                FilterChip(
                    selected = windowHours == h,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); onWindow(h) },
                    label = { Text("${h}h") },
                )
            }
        }
        // Countdown is separate: its tick never re-renders IOB/COB. ICR/ISF shows N/A when empty.
        val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        val suspectInk = MaterialTheme.colorScheme.error
        // Wording/ORDER come from `:core:design` OnBoardReadout, shared with Meals/Insulin panels.
        val sep = OnBoardReadout.separator(compact = true)
        val readout = remember(iobCob, sensitivity, unit, ink, suspectInk) {
            buildAnnotatedString {
                val parts = ArrayList<AnnotatedString.Builder.() -> Unit>()
                iobCob?.let {
                    parts += { append(OnBoardReadout.iob(it.iobU, compact = true)) }
                    parts += { append(OnBoardReadout.cob(it.cobG, compact = true)) }
                }
                val sensParts = OnBoardReadout.sensitivityParts(sensitivity, unit, compact = true)
                val marked = sensitivity != null && OnBoardReadout.suspect(sensitivity)
                parts += {
                    withStyle(SpanStyle(color = if (marked) suspectInk else ink)) {
                        sensParts.forEachIndexed { i, p -> if (i > 0) append(sep); append(p) }
                    }
                }
                iobCob?.minsSinceLastLoggedInsulin?.let { m -> parts += { append("logged ${m}m ago") } }

                withStyle(SpanStyle(color = ink)) {
                    parts.forEachIndexed { i, part ->
                        if (i > 0) append(sep)
                        part()
                    }
                }
            }
        }
        if (readout.isNotEmpty() || showNextForecast) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (readout.isNotEmpty()) {
                    Text(
                        readout,
                        style = MaterialTheme.typography.labelMedium,
                        color = ink,
                    )
                }
                if (showNextForecast) {
                    if (readout.isNotEmpty()) {
                        Text(
                            " · ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    NextForecastCountdown(forecastAdaptive, forecastPeriodMin)
                }
            }
        }
    }
}

@Composable
private fun NextForecastCountdown(adaptive: Boolean, periodMin: Int) {
    if (adaptive) {
        Text(
            "Adaptive forecast",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        return
    }
    val periodMs = periodMin.coerceAtLeast(1) * 60_000L
    val remainingMs by produceState(nextForecastRemainingMs(periodMs), periodMs) {
        while (true) {
            value = nextForecastRemainingMs(periodMs)
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val totalSec = (remainingMs / 1000).coerceAtLeast(0)
    val mins = totalSec / 60
    val secs = totalSec % 60
    val text = if (mins >= 1) "Next forecast ${mins}m ${secs}s" else "Next forecast ${secs}s"
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
}

/** Milliseconds until the next [periodMs] wall-clock boundary. */
private fun nextForecastRemainingMs(periodMs: Long): Long {
    val now = System.currentTimeMillis()
    return (now / periodMs + 1) * periodMs - now
}

/** OFF = not configured/paired — neutral grey, not a fault. `:app` maps transport state to this. */
enum class LinkHealth { OK, DEGRADED, DOWN, OFF }

data class ReachLight(val health: LinkHealth, val label: String)

data class BgReachability(val server: ReachLight, val cgm: ReachLight, val watch: ReachLight)

/** [watchRssi] is null until a source wires `readRemoteRssi` through `:watch`. */
data class BgSignals(val cgmRssi: Int? = null, val watchRssi: Int? = null)

/** Per-channel "last activity" tokens: a change flashes the light; unchanged/zero ⇒ none. */
data class BgPulses(val server: Long = 0L, val cgm: Long = 0L, val watch: Long = 0L)

@Composable
private fun ReachabilityBar(
    r: BgReachability,
    signals: BgSignals?,
    pulses: BgPulses?,
    deviceTempC: Double?,
    tempUnit: TempUnit,
    sensorExpiryMs: Long?,
    sensorWarmupEndMs: Long?,
    thermalThresholdC: Double?,
    thermalWarnMarginC: Double,
    stepsToday: Int?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReachChip("SRV", r.server, null, pulses?.server ?: 0L)
        // No bars: CGM RSSI is shown in the header, exactly once.
        ReachChip("CGM", r.cgm, null, pulses?.cgm ?: 0L)
        ReachChip("WCH", r.watch, signals?.watchRssi, pulses?.watch ?: 0L)
        deviceTempC?.let { TempChip(it, tempUnit, thermalThresholdC, thermalWarnMarginC) }
        stepsToday?.let { StepsChip(it) }
        HeartbeatChip()
        // Either instant alone is enough: gating on expiry suppresses warm-up when none is known.
        if (sensorExpiryMs != null || sensorWarmupEndMs != null) {
            SensorLifeChip(sensorExpiryMs, sensorWarmupEndMs)
        }
    }
}

/** Warm-up STATE is [warmupEndMs]'s nullity, not clock order: past deadline still prints `WARM`. */
@Composable
private fun SensorLifeChip(expiryMs: Long?, warmupEndMs: Long?) {
    val now by produceState(System.currentTimeMillis(), expiryMs, warmupEndMs) {
        while (true) {
            val wall = System.currentTimeMillis()
            value = wall
            val remaining = ((warmupEndMs?.takeIf { it > wall } ?: expiryMs) ?: wall) - wall
            // A second under a minute, else a minute — enough to keep the largest unit fresh.
            kotlinx.coroutines.delay(if (remaining in 1..60_000L) 1_000L else 60_000L)
        }
    }
    val warmingUp = warmupEndMs != null
    val remainingMs = (warmupEndMs ?: expiryMs ?: now) - now
    val expired = !warmingUp && remainingMs <= 0L
    // Warming with the deadline behind us: state known, duration not.
    val openEnded = warmingUp && remainingMs <= 0L
    val text = when {
        expired -> "EXP"
        openEnded -> "WARM"
        else -> formatRemaining(remainingMs)
    }
    val spoken = when {
        expired -> "Sensor expired"
        openEnded -> "Sensor warming up"
        warmingUp -> "Warm-up ends in $text"
        else -> "Sensor $text left"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        // The colour the graph already paints WARMUP readings in, so chip and trace agree.
        color = when {
            expired -> MaterialTheme.colorScheme.error
            warmingUp -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        },
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

private fun formatRemaining(ms: Long): String {
    val totalSec = ms / 1000
    val days = totalSec / 86_400
    val hours = totalSec / 3_600
    val minutes = totalSec / 60
    return when {
        days >= 1 -> "${days}d"
        hours >= 1 -> "${hours}h"
        minutes >= 1 -> "${minutes}m"
        else -> "${totalSec}s"
    }
}

/** Battery reading, LABELLED so it's never a fan value. Null [thermalThresholdC] ⇒ neutral. */
@Composable
private fun TempChip(celsius: Double, unit: TempUnit, thermalThresholdC: Double?, thermalWarnMarginC: Double) {
    val level = com.t1dm.core.model.thermalLevel(celsius, thermalThresholdC, thermalWarnMarginC)
    val color = when (level) {
        ThermalLevel.NORMAL -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        ThermalLevel.WARN -> LocalT1dmSemantics.current.high
        ThermalLevel.CRITICAL -> LocalT1dmSemantics.current.urgentHigh
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(
            painter = painterResource(com.t1dm.feature.dashboard.R.drawable.ic_temp),
            contentDescription = "Device temperature",
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(unit.format(celsius), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun StepsChip(steps: Int) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(
            painter = painterResource(com.t1dm.feature.dashboard.R.drawable.ic_steps),
            contentDescription = "Steps today",
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(humanSteps(steps), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun humanSteps(n: Int): String {
    if (n < 1000) return n.toString()
    val k = n / 1000.0
    if (k >= 10) return "${Math.round(k)}K"
    val s = "%.1f".format(k)
    return (if (s.endsWith(".0")) s.dropLast(2) else s) + "K"
}

@Composable
private fun ReachChip(tag: String, light: ReachLight, rssi: Int?, pulseKey: Long = 0L) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PulsingDot(light.health, pulseKey)
        Text(tag, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        rssi?.let { SignalBars(it) }
    }
}

/** A FIXED 60 bpm — decorative liveness, never a sensor reading. Static when animations are off. */
@Composable
private fun HeartbeatChip() {
    val animationsOn = LocalAnimationsEnabled.current
    val style = iconStyleForTheme(LocalT1dmSemantics.current.id)
    val icon = remember(style) { com.t1dm.core.design.heartIcon(style) }
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    // State HELD, unwrapped in graphicsLayer only, so a beat skips recomposing this Row.
    val scale = if (animationsOn) {
        val transition = rememberInfiniteTransition(label = "heartbeat")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1000
                    1.0f at 0
                    1.30f at 110
                    1.0f at 230
                    1.17f at 340
                    1.0f at 470
                    1.0f at 1000
                },
            ),
            label = "heartbeatScale",
        )
    } else remember { mutableFloatStateOf(1f) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = "Heartbeat 60 bpm",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
        Text("60", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Pulse cadence encodes severity, steady with animations off. Halo blooms once per [pulseKey]. */
@Composable
private fun PulsingDot(health: LinkHealth, pulseKey: Long = 0L) {
    val animationsOn = LocalAnimationsEnabled.current
    val color = health.color()
    val periodMs = when (health) {
        LinkHealth.DEGRADED -> 1400
        LinkHealth.DOWN -> 600
        else -> 0
    }
    // Held as State, unwrapped below (see [HeartbeatChip]); avoids per-frame re-layout of the dot.
    val pulseAlpha = if (animationsOn && periodMs > 0) {
        val transition = rememberInfiniteTransition(label = "reach")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse),
            label = "reachAlpha",
        )
    } else remember { mutableFloatStateOf(1f) }
    // Never fires on the first composition: the caller seeds [pulseKey] from the current value.
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }
    if (animationsOn) {
        LaunchedEffect(pulseKey) {
            if (pulseKey != 0L) {
                flash.snapTo(1f)
                flash.animateTo(0f, tween(700))
            }
        }
    }
    Box(contentAlignment = Alignment.Center) {
        // Composed UNCONDITIONALLY, hidden by layer alpha: gating re-measured this Box per frame.
        Box(
            Modifier
                .size(9.dp)
                .graphicsLayer {
                    val f = flash.value
                    val s = 1f + f * 1.6f
                    scaleX = s; scaleY = s; this.alpha = f * 0.7f
                }
                .clip(CircleShape)
                .background(color),
        )
        // Layer alpha, not per-frame `background(color.copy(…))`: same result, no chain rebuild.
        Box(
            Modifier
                .size(9.dp)
                .graphicsLayer { this.alpha = pulseAlpha.value }
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun LinkHealth.color(): Color = when (this) {
    LinkHealth.OK -> Color(0xFF3DD68C)
    LinkHealth.DEGRADED -> MaterialTheme.colorScheme.secondary
    LinkHealth.DOWN -> MaterialTheme.colorScheme.error
    LinkHealth.OFF -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
}

/** Tap mapped through the last-reported viewport. [topInset] is the strip clock labels draw in. */
@Composable
private fun BoxScope.TapToPlace(
    viewStartMs: Double,
    viewSpanMs: Double,
    topInset: Dp,
    onPlace: (Long) -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    val density = LocalDensity.current
    val leftInset = with(density) { GraphInsets.Left.toPx() }
    val rightInset = with(density) { GraphInsets.Right.toPx() }
    Box(
        Modifier
            .matchParentSize()
            .pointerInput(viewStartMs, viewSpanMs) {
                detectTapGestures { pos ->
                    if (viewSpanMs <= 0.0) return@detectTapGestures
                    val w = (size.width - leftInset - rightInset).coerceAtLeast(1f)
                    val frac = ((pos.x - leftInset) / w).coerceIn(0f, 1f)
                    haptics.perform(HapticEvent.Confirm)
                    onPlace((viewStartMs + frac * viewSpanMs).toLong())
                }
            },
    ) {
        Text(
            "Tap to drop the car",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + 2.dp),
        )
    }
}

/** Resizes the controls block; the panel's `weight` grows by exactly what the read-out gives up. */
@Composable
private fun PanelResizeHandle(heightDp: Float, onHeightDp: (Float) -> Unit) {
    val haptics = rememberT1dmHaptics()
    val detent = rememberHapticDetent(HapticEvent.SegmentTick)
    val density = LocalDensity.current
    // Keyed on `Unit` (re-key cancels the drag) and REUSED, so a direct param read would freeze it.
    val currentHeight by rememberUpdatedState(heightDp)
    val emit by rememberUpdatedState(onHeightDp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .pointerInput(Unit) {
                // Accumulated: `detectVerticalDragGestures` reports delta since the PREVIOUS event.
                var live = 0f
                detectVerticalDragGestures(
                    onDragStart = { live = currentHeight; haptics.perform(HapticEvent.DragStart) },
                    onDragEnd = { haptics.perform(HapticEvent.DragEnd) },
                ) { change, dy ->
                    change.consume()
                    // Below; bottom fixed, so subtracting moves the edge with the finger.
                    live = (live - dy / density.density)
                        .coerceIn(CONTROLS_HEIGHT_MIN_DP, CONTROLS_HEIGHT_MAX_DP)
                    detent.at((live / 8f).toInt())
                    emit(live)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
        )
    }
}

/** The chips row and nothing else — the graph at its tallest. */
private const val CONTROLS_HEIGHT_MIN_DP = 44f

/** Every read-out line at the largest font scale, with the graph at its shortest. */
private const val CONTROLS_HEIGHT_MAX_DP = 260f
