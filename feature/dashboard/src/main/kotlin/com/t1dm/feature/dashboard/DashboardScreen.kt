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
import com.t1dm.core.design.LoggedEntryDialog
import com.t1dm.core.design.SignalBars
import com.t1dm.core.design.argbWithAlpha
import com.t1dm.core.design.iconStyleForTheme
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.IobCobReadout
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
import com.t1dm.ui.graph.GlucoseGraph
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.GraphInsets
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
import com.t1dm.ui.graph.rolledSeriesOf
import com.t1dm.ui.graph.smoothedTraceOf
import com.t1dm.ui.graph.stepsFrameOf
import com.t1dm.ui.graph.curveOverlayOf
import com.t1dm.ui.graph.graphFrameOf
import com.t1dm.ui.graph.noFutureInsulinOverForecast
import com.t1dm.ui.graph.predOverlayOf

/**
 * The live BG dashboard (Phase 1 — "Dashboard shows the live graph + current
 * BG/trend from the Repository Flow"). It is a pure function of the state `:app` collects from the
 * repository: the reachability chips, the reusable [GlucoseGraph] over a [GraphFrame] built
 * off-thread by [graphFrameOf], and the overlay controls under it. No storage, no service, no
 * `:inference` — just the observed truth.
 *
 * The current value, trend, source and link age are NOT here: they are `:app`'s bottom bar, which
 * carries them on every screen.
 *
 * Phase 4 adds the toggleable curve overlays: the carb-appearance (Ra) and insulin-action curves
 * drawn UNDER the BG line, reconstructed from the logged events by `:app`'s `ChannelBuilder`
 * (off-thread), plus an IOB/COB read-out carrying its §3.6-F provenance. [curveChannels] resolves the
 * three per-5-min series for a grid window — carbs, combined insulin, and basal — in ONE call, since
 * the basal series is a component of the combined one and resolving it separately re-read the same
 * window; it is invoked off the main thread inside [produceState], and the [CurveOverlayFrame] it
 * yields is immutable primitive arrays the Canvas merely paints.
 */
@Composable
fun DashboardScreen(
    readings: List<CgmReading>,
    thresholds: AlertThresholds? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    predictions: List<ModelPrediction> = emptyList(),
    kovatchevF: ((Double) -> Double)? = null,
    // `SPEC/inference.md` §8.4's band recalibration, fitted on device and applied by `:app` — the
    // calibrated fan for a prediction, or null for the raw one. DISPLAY ONLY, and this panel's
    // forecast overlay is the whole of its reach: [predictions] themselves stay raw, so the alarm
    // engine, the calculator rails and everything stored or pushed read the fan the model produced.
    // §8.4 pins the median, so the forecast line is the same either way.
    calibrateBands: ((ModelPrediction) -> List<Double>?)? = null,
    iobCob: IobCobReadout? = null,
    // The model-probed ISF/ICR beside the IOB/COB read-out. DISPLAY-ONLY and, like the rolled
    // forecast, a type nothing downstream accepts: `:app` probes it off three counterfactual rolls of
    // the fp32 authority, and no rail, alarm or stored row can read it. Null ⇒ the probe could not
    // justify a figure, and the line simply does not carry one — never a dash or a zero.
    sensitivity: SensitivityEstimate? = null,
    // (carb, combined insulin, basal-only) for one grid window, from ONE resolve. These were two
    // lambdas, and the overlay called both on every rebuild — which resolved the same padded window
    // twice and rebuilt the same basal representation twice, since the basal series is a component of
    // the combined insulin one rather than an independent quantity.
    curveChannels: (suspend (gridStartMs: Long, nSteps: Int) -> Triple<DoubleArray, DoubleArray, DoubleArray>)? = null,
    // The pedometer's per-bucket counts over one grid window, densified by `:app` off the sparse
    // `sample.steps` read. A lambda over an IntArray for the same reason [curveChannels] is one over
    // DoubleArrays: this module holds no `:data` dependency and must never see a Room row. Null ⇒ no
    // step source is wired, and the Steps chip is not offered.
    stepSeries: (suspend (gridStartMs: Long, nSteps: Int) -> IntArray)? = null,
    // The logged carb/insulin events the BG panel marks at its foot — the SAME feed the Logs panel
    // binds, as `:app` joined it against the upload queue. This screen neither re-derives the
    // committed/delivered verdict nor thins the list; it reduces the feed to markers for the panel
    // (which is given no amount and no row id it could act on) and keeps the rest for the modal a tap
    // on a mark opens. The reduction happens HERE so a mark and the row it stands for are the same
    // list position by construction, which is the whole of how a tap names what it hit.
    logEntries: List<LoggedEntry> = emptyList(),
    warmup: WarmupProgress? = null,
    // Phase 7A — BG-panel overhaul.
    // Issue 1 — suppress the "next forecast" countdown when no forecast is actually being made: during
    // warmup (context collection) or under battery-saver/low-power. The app wires the real value.
    lowPowerActive: Boolean = false,
    rangeMinMgdl: Int = 20,
    rangeMaxMgdl: Int = 250,
    initialWindowHours: Int = 6,
    onSetWindowHours: ((Int) -> Unit)? = null,
    reachability: BgReachability? = null,
    signals: BgSignals? = null,
    // I12 — per-channel data-movement tokens; a change flashes that channel's reachability light.
    pulses: BgPulses? = null,
    // U9 — no fan (RPM is permission-denied even to adb); show the device battery-sensor temperature,
    // labelled, in the user's chosen unit, to the right of the WCH reachability light.
    deviceTempC: Double? = null,
    temperatureUnit: TempUnit = TempUnit.CELSIUS,
    stepsToday: Int? = null,
    // The sensor time-left expiry instant (absolute epoch-ms), counted down live to the right of the
    // TEMP readout. USER-ENTERED: a passive advertisement carries no service-life fact, so Settings →
    // CGM collects the remaining life and stores an absolute instant. Null ⇒ no expiry countdown.
    sensorExpiryMs: Long? = null,
    // The instant the active sensor's warm-up ends (absolute epoch-ms), non-null ONLY while it is
    // genuinely warming up — the nullity IS the warm-up state, independent of [sensorExpiryMs] and of
    // whether the instant has already passed. While both are null the chip is not shown at all. This
    // is CGM sensor warm-up, not the inference context warm-up [warmup] carries.
    sensorWarmupEndMs: Long? = null,
    // Issues 7 & 9 — the warmup-surviving circadian belief, so the TOP axis renders the predicted
    // clock even while the BG forecast is (correctly) suppressed. Falls back to the selected
    // prediction's copy once a full cycle publishes.
    circadianTime: PredictedTime? = null,
    circadianAnchorMs: Long? = null,
    // Issue 13 — the causal SavGol smoother (mg/dL, clamps [20,500]) the model consumes; when wired, a
    // toggle overlays the smoothed model-input trace. Native call is passed as a lambda so this module
    // keeps no JNI dependency. [smoothingWindow] is the window that lambda was built with: it is not
    // used to smooth anything here, only to invalidate the cached trace when the setting changes (the
    // lambda itself is memoized by Compose and is NOT a reliable key).
    smoothMgdl: ((DoubleArray) -> DoubleArray)? = null,
    smoothingWindow: Int = 7,
    // I2 — the ephemeral, DISPLAY-ONLY rolled forecast (never drives an alert/dose). [onRoll] runs one
    // on-demand roll to the requested horizon (hours) on the fp32 CPU authority; [rollComputing] gates
    // the progress UI; [onClearRoll] dismisses the ephemeral overlay.
    rolledForecast: RolledForecast? = null,
    rollComputing: Boolean = false,
    onRoll: ((Double) -> Unit)? = null,
    onClearRoll: (() -> Unit)? = null,
    // F2 — forecast cadence. In adaptive mode a cycle runs on every CGM reading, so the fixed
    // grid-boundary countdown is meaningless (the panel just states it is adaptive); in timed mode the
    // countdown ticks to the next [forecastPeriodMin]-minute wall-clock boundary the driver fires on.
    forecastAdaptive: Boolean = true,
    forecastPeriodMin: Int = 5,
    // F6 — the thermal-gate threshold (battery-sensor °C) that colours the TEMP chip amber/red as the
    // device nears/crosses it. Null when the gate is disabled ⇒ the chip stays its neutral colour.
    thermalThresholdC: Double? = null,
    thermalWarnMarginC: Double = 3.0,
    // The BG panel's freehand annotation layer (Room v8), collected by `:app` — this module keeps no
    // storage dependency, exactly as it does for the readings and the curve channels. The render model
    // is built here off-thread like every other overlay. In-app panel only: it never reaches the
    // widget, the notification, or the watch.
    paintStrokes: List<PaintStroke> = emptyList(),
    // Persisting a stroke and removing one: the ONLY seam the annotation layer needs. Both are
    // `suspend` because the insert must hand back the row id it mints — that id is what makes undo, and
    // the eraser, addressable. Null on either ⇒ the paint toggle is not offered at all.
    onAddPaintStroke: (suspend (PaintStroke) -> Long)? = null,
    onDeletePaintStroke: (suspend (Long) -> Unit)? = null,
    // HINDSIGHT — the selected model's STORED forecasts over a window, so a long-press sweep can drag
    // the fan issued at each past cycle across the trace that actually followed it. A resolver rather
    // than a list because the window follows the viewport: it is called off the main thread with a
    // bucketed span whenever the pan crosses one, and never at all while the chip is off. Null ⇒ the
    // chip is not offered. Display-only, read-only — these are the rows as written.
    hindsightIn: (suspend (modelId: String, fromMs: Long, toMs: Long) -> List<ModelPrediction>)? = null,
    // The hill-climb minigame, whose terrain IS this panel's trace. A MODE, not a destination: while
    // it is on the panel renders [gameSlot] in the graph's place and everything else on the dashboard
    // — read-outs, IOB line, nav — stays exactly where it was. null ⇒ not offered at all, the same
    // availability rule every other affordance here follows.
    gameSlot: (@Composable (Modifier, trackFromMs: Long, dropAtMs: Long, spanMinutes: Float, predictedClock: PredictedClock?, onReady: () -> Unit, exit: () -> Unit) -> Unit)? = null,
) {
    // What the panel draws at its foot, and the one place the marker↔row correspondence is made.
    val logMarkers = remember(logEntries) { logEntries.map { it.marker } }
    // The logs a tap on a mark named, held by VALUE: the feed re-sorts under a landing reading and a
    // delete elsewhere can drop a row, and the dialog must go on restating what was tapped either way
    // (the same reason the Logs panel hoists its delete confirmation to the screen).
    var tappedLogs by remember { mutableStateOf<List<LoggedEntry>>(emptyList()) }
    var gameOn by remember { mutableStateOf(false) }
    // The chart's LIVE viewport, which pinch-zoom and pan move independently of the window chips.
    // Held here because drive mode adopts it wholesale — the panel must not zoom or re-span when the
    // game starts — and because a tap on the panel is turned into an instant through it.
    var viewStartMs by remember { mutableStateOf(0.0) }
    var viewSpanMs by remember { mutableStateOf(0.0) }
    // Where the car is to be dropped: the instant under the finger. Null until the user picks.
    var gameStartMs by remember { mutableStateOf<Long?>(null) }
    // The chart stays on top until the game has a drawable first frame. Both render the same
    // furniture and the same curve, so when it finally swaps there is nothing to see — which is the
    // whole point: dropping the car must not flash through a loading state.
    var gameReady by remember { mutableStateOf(false) }
    LaunchedEffect(gameOn) { if (!gameOn) { gameStartMs = null; gameReady = false } }
    LaunchedEffect(gameStartMs) { if (gameStartMs == null) gameReady = false }
    val frame by produceState(GraphFrame.EMPTY, readings, unit) {
        value = graphFrameOf(readings, unit, kovatchevF = kovatchevF)
    }
    // Only the SELECTED model's fan is painted; the other running models forecast for
    // telemetry/sync but must not stipple faint secondary fans over the BG panel.
    // Keyed on [calibrateBands] too: `:app` re-remembers that lambda exactly when the stored §8.4
    // correction changes, so a fresh fit repaints the fan without waiting for the next cycle.
    val overlay by produceState(emptyList<PredSeries>(), predictions, unit, calibrateBands) {
        value = predOverlayOf(
            predictions.filter { it.selected },
            unit,
            kovatchevF = kovatchevF,
            calibrateBands = calibrateBands,
        )
    }

    var toggles by remember { mutableStateOf(CurveOverlayToggles()) }
    var windowHours by remember(initialWindowHours) { mutableStateOf(initialWindowHours) }
    // Issue 7 — future-panning is ALWAYS available: the pannable right edge extends 24 h ahead so the
    // user can swipe right into the empty future (auto-follow still settles on the DATA/forecast end, so
    // the panel opens at "now"). No BG line is fabricated where no forecast exists.
    // I2 — the Roll confirmation dialog + its chosen horizon (30 min…12 h, 30-min steps, default 2 h).
    var showRollDialog by remember { mutableStateOf(false) }

    // The rolled fan converted to the active unit once, off-thread (mirrors the prediction overlay).
    val rolledSeries by produceState<RolledSeries?>(null, rolledForecast, unit) {
        value = rolledSeriesOf(rolledForecast, unit, kovatchevF)
    }

    // Reconstruct the carb/insulin channels over the readings' grid span, extended INTO THE FUTURE by
    // a fixed horizon so the committed doses' appearance/action tails are visible in the prediction
    // zone (item 2), not only their history — off-thread. Built whenever the resolver is wired (not
    // gated on the toggles) so the scrub read-out can report the rates even with the overlay hidden;
    // the DRAW is still gated by [toggles].
    // Keyed on iobCob too so a just-logged dose (which emits a new IOB/COB read-out) rebuilds the
    // overlay immediately, rather than waiting for the next CGM reading — this keeps the insulin/basal
    // overlay (issue 18) and the no-future-insulin advisory (issue 16) current the moment a dose lands.
    val curveOverlay by produceState(CurveOverlayFrame.EMPTY, readings, predictions, curveChannels, iobCob, rolledForecast) {
        val resolver = curveChannels
        if (resolver == null || readings.isEmpty()) {
            value = CurveOverlayFrame.EMPTY
            return@produceState
        }
        // Ends, not scans. `observeReadings` is `ORDER BY tsMs` ascending, so the bounds ARE the two
        // ends of the list. This block runs before the producer's first suspension point — i.e. on the
        // main thread — so as a `minOf`/`maxOf` pair it was two O(n) passes over the whole never-pruned
        // history every time this producer restarted, and its cost grew with every day of use.
        val oldestReading = readings.first().tsMs / STEP_MS * STEP_MS
        val lastReading = readings.last().tsMs
        val lastForecast = predictions.maxOfOrNull { it.anchorTsMs + it.horizonSteps.toLong() * it.stepMs } ?: lastReading
        val rolledEnd = rolledForecast?.takeUnless { it.isEmpty }?.horizonEndMs ?: lastReading
        // Always reach at least OVERLAY_FUTURE_MS past now so a just-logged dose shows its rising tail
        // even before a forecast exists (warmup); the same event-reconstructed channel carries both
        // the past and the future portions (bucketize lays the full curve across the window). Issue 7 —
        // reach the full 24 h future the graph can now always pan to (and any farther on-demand roll)
        // so the committed carb/insulin curves render across the empty future too.
        val end = maxOf(lastReading, lastForecast, rolledEnd, System.currentTimeMillis() + maxOf(OVERLAY_FUTURE_MS, FUTURE_VIEW_MS))
        // Anchor the window to the RECENT region ending at `end`. A server re-sync can push `readings`
        // back weeks; if gridStart tracked the oldest reading, the MAX_OVERLAY_STEPS cap would strand the
        // window in the far past and never reach `now` — so a just-logged dose (and recent carb/insulin)
        // would fall outside it. Clamp the start forward so the capped window always covers the present.
        val earliestStart = ((end / STEP_MS) - (MAX_OVERLAY_STEPS - 1L)) * STEP_MS
        val gridStart = maxOf(oldestReading, earliestStart)
        val nSteps = (((end - gridStart) / STEP_MS).toInt() + 1).coerceIn(1, MAX_OVERLAY_STEPS)
        val (carb, insulin, basal) = resolver(gridStart, nSteps)
        value = curveOverlayOf(carb, insulin, gridStart, STEP_MS, basal)
    }

    // Issue 16: advise when NO insulin action covers the forecast horizon — neither a committed bolus
    // tail nor a basal schedule (the auto-extended basal is already folded into the combined channel,
    // so an active schedule keeps this false). Purely advisory; never actuates.
    val noFutureInsulin = remember(curveOverlay, predictions) {
        noFutureInsulinOverForecast(curveOverlay, predictions, System.currentTimeMillis())
    }

    // Issue 13: the smoothed model-input trace (built off-thread; null unless the smoother is wired).
    val smoothed by produceState<SmoothedTrace?>(null, readings, unit, smoothMgdl, smoothingWindow) {
        val f = smoothMgdl
        value = if (f == null || readings.isEmpty()) null
        else smoothedTraceOf(readings, unit, f, kovatchevF)
    }
    var showSmoothed by remember { mutableStateOf(false) }

    // The STEPS overlay's render model, built off-thread over the SAME grid window the curve overlay
    // uses so the two band layers cannot disagree about where a bucket is. Kept a sibling of
    // [showSmoothed] rather than a third field on [toggles]: that type is the pair of model-input
    // channels one resolve yields, and steps are neither reconstructed nor a model input.
    //
    // Built whenever a loader is wired, NOT gated on the chip — the same rule [curveOverlay] follows,
    // and for the same reason: the scrub read-out reports the steps at the cursor whether or not the
    // band is painting them, so a chip governs what is DRAWN and never what the panel knows. The cost
    // is one sparse indexed query per readings emit (about one per five minutes), off the main thread.
    var showSteps by remember { mutableStateOf(false) }
    // A coarse tick on the 5-min grid. The steps window's right edge comes from the CLOCK, not from
    // the reading stream — steps keep accruing while the CGM is out of range — but a producer keyed
    // only on `readings` would never re-run during exactly that outage, and the widened window would
    // be inert. This changes value once per bucket, so it re-keys the producer at most every five
    // minutes however often it is polled.
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
        // The window the bars may span: the readings' own extent, capped to the same fortnight of
        // buckets the curve overlay caps to, and anchored on the RECENT end for the reason that one is
        // — a server re-sync can push `readings` back weeks, and a window stranded in the far past
        // would carry no bar the viewport could reach. No future half: a pedometer cannot report one.
        val oldest = readings.first().tsMs / STEP_MS * STEP_MS
        // The end bound comes from the CLOCK, not from the reading stream. The two sources are
        // independent: the pedometer keeps bucketing while the CGM is out of range, warming up or
        // simply not being heard, and a window ending at the last reading would silently drop every
        // step taken since — exactly the stretch a reader looking at a gap wants explained.
        val newest = maxOf(readings.last().tsMs, System.currentTimeMillis()) / STEP_MS * STEP_MS
        val earliest = ((newest / STEP_MS) - (MAX_OVERLAY_STEPS - 1L)) * STEP_MS
        val gridStart = maxOf(oldest, earliest)
        val nSteps = (((newest - gridStart) / STEP_MS).toInt() + 1).coerceIn(1, MAX_OVERLAY_STEPS)
        value = stepsFrameOf(load(gridStart, nSteps), gridStart, STEP_MS)
    }

    // The annotation layer's render model: decoded, ordered and thinned once off-thread, then culled
    // per-viewport by the Canvas — a pan or a zoom never rebuilds it.
    val paint by produceState<PaintFrame?>(null, paintStrokes) {
        value = if (paintStrokes.isEmpty()) null else paintFrameOf(paintStrokes)
    }

    // ── Hindsight: the past forecasts the scrub sweeps ───────────────────────────────────────────
    var showHindsight by remember { mutableStateOf(false) }
    // The load window, BUCKETED to the hour. The raw viewport moves on every frame of a pan, so
    // keying the producer on it would restart a database read per frame and cancel it a frame later;
    // rounded outward to an hour it re-reads only when the pan crosses a bucket, and the extra hour
    // at each end is already loaded when it does. Reaches HINDSIGHT_LEAD_MS further back than the
    // window starts because a cycle just off the left edge still forecasts INTO it.
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
    // Which model's history: the one whose fan is live on the panel, so both fans are the same model
    // and the comparison is a comparison. Nothing is swept when no model is selected.
    val hindsightSelected = predictions.firstOrNull { it.selected }
    val hindsightModelId = hindsightSelected?.modelId
    // ...and WHEN it last forecast. This is a key rather than a value the producer reads: the bucket
    // only moves when the pan crosses an hour, so without it the frame would be re-read at most once
    // an hour and every cycle issued since would be missing from the sweep — the most recent stretch,
    // which is the one most worth looking at, silently blank. Keyed on the cycle instant it rebuilds
    // once per cycle instead, which is one indexed query per forecast and only while the chip is on.
    val hindsightLatestCycleMs = hindsightSelected?.cycleTsMs
    val hindsight by produceState<HindsightFrame?>(
        null, hindsightBucket, hindsightModelId, hindsightLatestCycleMs, unit, kovatchevF, hindsightIn,
    ) {
        val resolve = hindsightIn
        val bucket = hindsightBucket
        val modelId = hindsightModelId
        value = if (resolve == null || bucket == null || modelId == null) null
        else hindsightFrameOf(resolve(modelId, bucket.first, bucket.second), unit, kovatchevF)
    }

    // ── Paint mode: palette selection + the session's undo history ───────────────────────────────
    // All of it is local, like [showSmoothed] and [toggles]: paint mode is a transient way of looking
    // at the panel, not a persisted preference, and the STROKES — the only durable part — live in Room
    // behind the two callbacks above. Nothing here is read by anything but this Canvas.
    val paintHaptics = rememberT1dmHaptics()
    val paintScope = rememberCoroutineScope()
    val paintAvailable = onAddPaintStroke != null && onDeletePaintStroke != null
    var paintOn by remember { mutableStateOf(false) }
    var paintTool by remember { mutableStateOf(PaintTool.DEFAULT) }
    var paintErasing by remember { mutableStateOf(false) }
    var paintWidthDp by remember { mutableStateOf(PaintTool.DEFAULT.defaultWidthDp) }
    var showPaintStyle by remember { mutableStateOf(false) }
    val defaultInk = LocalT1dmSemantics.current.inRange.toArgb()
    var paintColor by remember(defaultInk) {
        mutableStateOf(argbWithAlpha(defaultInk, PaintTool.DEFAULT.defaultAlpha))
    }
    // The session's history. An op is remembered with the stroke ITSELF, not merely its id, because a
    // re-insert mints a new row id — so undoing an erase, or redoing an undone stroke, has to carry the
    // geometry back to the store and then adopt the id it is given.
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
        // Captured BEFORE the delete: once the row is gone the flow no longer carries the geometry, and
        // undo would have nothing to put back.
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

    // The selected model's circadian-phase clock (item 21) + its approaching excursions (item 16).
    // Issues 7 & 9: prefer the in-cycle prediction's belief, but fall back to the warmup-surviving
    // [circadianTime] so the TOP axis still renders the predicted clock while forecasts are withheld.
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
                    // Picking a tool SEEDS its width and alpha; both stay overridable in the dialog.
                    paintWidthDp = t.defaultWidthDp
                    paintColor = seedInk(t, paintColor)
                },
                onSelectEraser = { paintErasing = !paintErasing },
                onOpenStyle = { showPaintStyle = true },
                onUndo = { undoPaint() },
                onRedo = { redoPaint() },
            )
        }
        // I2 — the ephemeral rolled-forecast status line: a plain reason when it is degenerate/absent,
        // otherwise a note that the drawn tail is extrapolated + display-only, with a Clear affordance.
        rolledForecast?.takeIf { !it.isEmpty || it.reason != null }?.let { rf ->
            RolledStatusBanner(rf, onClear = onClearRoll)
        }
        val panelModifier = Modifier.fillMaxWidth().weight(1f)
        val slot = gameSlot
        val spanMin = if (viewSpanMs > 0.0) (viewSpanMs / 60_000.0).toFloat() else windowHours * 60f
        val dropAt = gameStartMs
        Box(panelModifier) {
            // ONE call site for the game, always. Calling it from two branches (loading vs ready) put
            // it at two different positions in the composition tree, so flipping between them DISPOSED
            // the running world and built a fresh one — reloading the track and re-placing the car.
            // That was the flash, and the car jumping back to the left.
            if (gameOn && slot != null && dropAt != null) {
                slot(Modifier.fillMaxSize(), viewStartMs.toLong(), dropAt, spanMin, predictedClock, { gameReady = true }) {
                    gameOn = false
                }
            }
            // The chart stays ON TOP until the game can draw, then CROSS-FADES out over it.
            //
            // Both render the same furniture and the same curve, but not the same everything — the
            // chart also carries the forecast fan, the curve overlays and its own point styling, and
            // dropping all of that in a single frame reads as a flash however well the two agree
            // underneath. A short fade turns any residual difference into a blend. It is emitted until
            // the fade completes, not merely until the game is ready.
            // Respects the app-wide animations flag: with motion off the hand-off is instant.
            val motionOn = LocalAnimationsEnabled.current
            val handOff = gameOn && dropAt != null && gameReady
            val chartAlpha by animateFloatAsState(
                targetValue = if (handOff) 0f else 1f,
                animationSpec = tween(if (motionOn) 220 else 0),
                label = "chartHandOff",
            )
            if (chartAlpha > 0.001f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = chartAlpha }) {
                GlucoseGraph(
                frame = frame,
                modifier = Modifier.fillMaxSize(),
            thresholds = thresholds,
            initialWindowMin = windowHours * 60f,
            predictions = overlay,
            curveOverlay = curveOverlay,
            curveToggles = toggles,
            stepsFrame = stepsFrame,
            showSteps = showSteps,
            logMarkers = logMarkers,
            onMarkerTap = { hits -> tappedLogs = hits.mapNotNull { logEntries.getOrNull(it) } },
            rangeMinMgdl = rangeMinMgdl,
            rangeMaxMgdl = rangeMaxMgdl,
            predictedClock = predictedClock,
            smoothed = smoothed,
            showSmoothed = showSmoothed,
            rolled = rolledSeries,
            hindsight = hindsight,
            futureExtentMs = FUTURE_VIEW_MS,
            onViewportChange = { st, sp -> viewStartMs = st; viewSpanMs = sp },
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
        }
        // Under the graph, directly above the app's bottom bar: the chips are what the thumb reaches
        // for, so they sit where the thumb is. The read-out that used to head this panel moved into
        // that bar, where the nav wheel's hub fills the gap it left.
        if (iobCob != null || sensitivity != null || curveChannels != null || smoothMgdl != null ||
            onRoll != null || paintAvailable || gameSlot != null || hindsightIn != null ||
            stepSeries != null
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
                stepsAvailable = stepSeries != null,
                showSteps = showSteps,
                smoothAvailable = smoothMgdl != null,
                showSmoothed = showSmoothed,
                hindsightAvailable = hindsightIn != null,
                showHindsight = showHindsight,
                rollAvailable = onRoll != null,
                rollComputing = rollComputing,
                paintAvailable = paintAvailable,
                paintOn = paintOn,
                gameAvailable = gameSlot != null,
                gameOn = gameOn,
                onToggleGame = { gameOn = it },
                onRollClick = { showRollDialog = true },
                onToggle = { toggles = it },
                onToggleSteps = { showSteps = it },
                onToggleSmoothed = { showSmoothed = it },
                onToggleHindsight = { showHindsight = it },
                onTogglePaint = { on -> paintOn = on },
                onWindow = { h ->
                    windowHours = h
                    onSetWindowHours?.invoke(h)
                },
            )
        }
    }

    if (tappedLogs.isNotEmpty()) {
        LoggedEntryDialog(tappedLogs) { tappedLogs = emptyList() }
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

/**
 * One reversible edit to the annotation layer. [added] says which direction undoing it runs in — delete
 * the stroke, or put it back. [stroke] is `var` because a re-insert mints a NEW row id, and the op has
 * to adopt it or the next undo/redo would address a row that no longer exists.
 */
private class PaintUndoOp(var stroke: PaintStroke, val added: Boolean)

/** The same stroke under the row id the store just minted. [PaintStroke] is not a data class — array
 *  fields would give it an identity `equals` that lies — so the copy is spelled out. */
private fun PaintStroke.withId(newId: Long): PaintStroke =
    PaintStroke(newId, createdAtMs, tool, colorArgb, widthDp, tsMs, yFrac)

/** Honest units for the roll horizon: whole hours or "N h 30 min", and "30 min" below one hour. */
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

/**
 * The I2 Roll confirmation. The copy is deliberately honest: the roll re-feeds the model's own 2 h
 * forecast into its context N times, and everything beyond 2 h is EXTRAPOLATED, unvalidated, and shown
 * for inspection only — it never raises an alert. A slider picks 30 min…12 h in 30-min steps.
 */
@Composable
private fun RollConfirmDialog(onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    // Slider stops 1..24 map to 0.5 h … 12 h in 30-min steps; default index 4 = 2 h.
    var stepIdx by remember { mutableStateOf(4f) }
    val hours = (stepIdx.roundToInt().coerceIn(1, 24)) * 0.5
    val rolls = Math.ceil(hours / 2.0).toInt()
    val haptics = rememberT1dmHaptics()
    // The same three-beat every dialog in the app speaks (see ConfirmLogDialog): Warn on raise —
    // this one is asking the user to accept an unvalidated extrapolation — Confirm on accept, Reject
    // on cancel or a scrim/back dismissal.
    LaunchedEffect(Unit) { haptics.perform(HapticEvent.Warn) }
    // The stops are 30-min detents, so the tick is keyed on the ROUNDED index, never the raw Float:
    // Material's Slider reports a continuous value between stops and would tick on every pixel.
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
                    "Re-feeds the forecast $rolls time${if (rolls == 1) "" else "s"} — " +
                        "extrapolated, never alerts",
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

/** The ephemeral rolled-forecast status line (I2). States the honest disposition in plain language. */
@Composable
private fun RolledStatusBanner(rf: RolledForecast, onClear: (() -> Unit)?) {
    val msg = when {
        rf.reason != null -> rf.reason!!
        rf.isEmpty -> "No rolled forecast"
        else -> "Rolled to ${rollHoursLabel(rf.requestedHours)} — past 2 h extrapolated, display-only"
    }
    val haptics = rememberT1dmHaptics()
    // A roll that came back DEGENERATE is a result the user asked for and did not get; it lands while
    // they are watching the graph rather than the banner, so it says so in the hand. Keyed on the
    // flag, so a redraw of the same banner is silent.
    LaunchedEffect(rf.degenerate) { if (rf.degenerate) haptics.perform(HapticEvent.Warn) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            msg,
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

/** Build the graph's [PredictedClock] from a decoded circadian belief, gating out a diffuse (low-R)
 *  belief whose hour is effectively undefined — the top axis then quietly reads "model time n/a". */
private fun PredictedTime.toClock(anchorTsMs: Long): PredictedClock? =
    if (resultantR <= MIN_CLOCK_R) null
    else PredictedClock(predictedHour = predictedHour, anchorTsMs = anchorTsMs, resultantR = resultantR)

private const val STEP_MS: Long = 300_000L
private const val MAX_OVERLAY_STEPS: Int = 4032 // ~14 days of 5-min buckets
private const val OVERLAY_FUTURE_MS: Long = 6L * 3_600_000L // show ~6 h of future dose tails
private const val FUTURE_VIEW_MS: Long = 24L * 3_600_000L // I3 — +24 h future-view extent

// Hindsight's load window. Bucketed to the hour so a pan re-reads on bucket crossings rather than on
// frames; reaching one validated horizon before the viewport start, because a cycle just off the left
// edge still forecasts into what is on screen. Capped so a wide pinch cannot make one sweep resident
// over weeks — at 5-min cycles the cap is ~576 fans, a few hundred KB of floats.
private const val HINDSIGHT_BUCKET_MS: Long = 3_600_000L
private const val HINDSIGHT_LEAD_MS: Long = 2L * 3_600_000L
private const val HINDSIGHT_MAX_SPAN_MS: Long = 48L * 3_600_000L
/** Below this resultant length the circadian belief is too diffuse to anchor a clock axis on. */
private const val MIN_CLOCK_R: Double = 0.05

/**
 * The WARMUP-gate banner (inference-runtime.md): while fewer than the configured hours of MEASURED
 * context have accrued, the forecast overlay is empty and this states the progress. Cleared the
 * moment the gate is met and a cycle publishes.
 */
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

/**
 * The item-16 advisory: no insulin action covers the forecast window — no committed bolus tail and no
 * basal schedule reaching into it. Advisory only (the app never actuates); it states the plain-language
 * reason so an absent basal/bolus is visible rather than silently assumed.
 */
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

/** IOB/COB read-out + the carb/insulin overlay toggles (Phase 4) + the 6/12/24h window buttons
 *  (item 5). */
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
    stepsAvailable: Boolean,
    showSteps: Boolean,
    smoothAvailable: Boolean,
    showSmoothed: Boolean,
    hindsightAvailable: Boolean,
    showHindsight: Boolean,
    rollAvailable: Boolean,
    rollComputing: Boolean,
    paintAvailable: Boolean,
    paintOn: Boolean,
    gameAvailable: Boolean,
    gameOn: Boolean,
    onToggleGame: (Boolean) -> Unit,
    onRollClick: () -> Unit,
    onToggle: (CurveOverlayToggles) -> Unit,
    onToggleSteps: (Boolean) -> Unit,
    onToggleSmoothed: (Boolean) -> Unit,
    onToggleHindsight: (Boolean) -> Unit,
    onTogglePaint: (Boolean) -> Unit,
    onWindow: (Int) -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // N2 — the Roll / Carbs / Insulin / Steps / Smoothed / 6h / 12h / 24h chips no longer fit across
        // a phone width, so they live in a HORIZONTALLY-SCROLLABLE row (mirroring the nav) — nothing clips.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // I2 — the Roll button, to the LEFT of the Carbs chip. Shows a spinner while computing.
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
            // Carbs / Insulin / Steps / Smoothed / Paint are LATCHES, not a single-choice group, so each speaks
            // the mirrored ToggleOn/ToggleOff pair rather than a chip picker's detent — the hand can
            // tell "I turned the overlay on" from "I switched to the 12 h window" without looking.
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
            // Steps shares the band with Carbs and Insulin, so it sits with them rather than with the
            // chips that change what the panel IS (Smoothed swaps the trace, Drive swaps the panel).
            if (stepsAvailable) {
                FilterChip(
                    selected = showSteps,
                    onClick = { haptics.toggled(!showSteps); onToggleSteps(!showSteps) },
                    label = { Text("Steps") },
                )
            }
            if (smoothAvailable) {
                FilterChip(
                    selected = showSmoothed,
                    onClick = { haptics.toggled(!showSmoothed); onToggleSmoothed(!showSmoothed) },
                    label = { Text("Smoothed") },
                )
            }
            // Hindsight arms the sweep; it draws nothing until a long-press scrub is under way, since
            // the cursor is what picks the cycle.
            if (hindsightAvailable) {
                FilterChip(
                    selected = showHindsight,
                    onClick = { haptics.toggled(!showHindsight); onToggleHindsight(!showHindsight) },
                    label = { Text("Hindsight") },
                )
            }
            // Paint mode. While it is on, one finger draws on the panel and two or more pan/zoom it;
            // the long-press scrub is suspended, because a long press is how a stroke begins.
            if (paintAvailable) {
                FilterChip(
                    selected = paintOn,
                    onClick = { haptics.toggled(!paintOn); onTogglePaint(!paintOn) },
                    label = { Text("Paint") },
                )
            }
            // Game mode swaps what the PANEL renders — the dashboard around it is untouched, so the
            // read-outs, the IOB line and the nav all stay put. A mode, not a destination.
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
        // Issue 1 — the IOB/COB read-out and the live "next forecast" countdown share one line, kept
        // horizontally scrollable so neither clips. The countdown stays a separate composable so its
        // per-second tick never re-renders the static IOB/COB text. It is shown only when a forecast is
        // actually being made ([showNextForecast] = not in warmup and not low-power).
        // The ICR/ISF estimate sits between COB and the provenance tail, so the line reads
        // on-board-now then per-unit-response then how old the insulin fact is. Assembled as parts
        // rather than concatenated fragments: the estimate is independently absent from the IOB/COB
        // pair, and either missing half must not leave a stray separator behind.
        //
        // Unlike every other segment here, the ICR/ISF pair is shown even when there is nothing to
        // show — as "N/A". A blank where a figure belongs cannot be told apart from a feature that
        // never shipped, and the pair's whole remaining purpose is to report what the model does.
        val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        val suspectInk = MaterialTheme.colorScheme.error
        // Wording and ORDER come from `:core:design` OnBoardReadout, the one definition the Meals and
        // Insulin panels also render — they carry the same two numbers and had drifted into showing
        // them in opposite orders. Only the density is local: this line is packed and shares a
        // scrollable row with the forecast countdown.
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

/** Issue 2 — a live "Next forecast in Xm Ys" readout. In TIMED mode the driver fires one cycle on each
 *  [forecastPeriodMin]-minute wall-clock boundary, so this ticks every second down to it (shows "Xm Ys"
 *  at/above one minute, "Ys" below). In ADAPTIVE mode a cycle runs on every reading, so there is no
 *  fixed boundary to count toward — the line simply states that adaptive cadence is in effect. */
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

/** Milliseconds until the next [periodMs] wall-clock boundary — the next timed model cycle. */
private fun nextForecastRemainingMs(periodMs: Long): Long {
    val now = System.currentTimeMillis()
    return (now / periodMs + 1) * periodMs - now
}

// ─── Reachability + signal strength chrome (items 20 & 23) ─────────────────────────────────────

/** Health of one link the traffic light shows. OFF = not configured/paired (a neutral grey, not a
 *  fault). Neutral by design so `:feature:dashboard` stays free of `:sync`/`:watch` — `:app` maps
 *  the transport/link state onto this. */
enum class LinkHealth { OK, DEGRADED, DOWN, OFF }

/** One reachability light + its tap-to-reveal plain-language label (human-readable everywhere). */
data class ReachLight(val health: LinkHealth, val label: String)

/** The three BG-panel reachability lights (item 23): server / CGM / watch. */
data class BgReachability(val server: ReachLight, val cgm: ReachLight, val watch: ReachLight)

/** BLE signal strengths surfaced in the BG panel (item 20). Watch RSSI is null until a source wires
 *  `readRemoteRssi` through `:watch` — the field lights up automatically when present. */
data class BgSignals(val cgmRssi: Int? = null, val watchRssi: Int? = null)

/** I12 — per-channel "last activity" tokens: a monotonically-advancing key (a timestamp works) that
 *  changes the instant a channel MOVES — CGM on a new reading, SRV on a send/receive, WCH on a
 *  push/ack. A change triggers a one-shot flash on that channel's light, DISTINCT from its steady
 *  colour state. Unchanged/zero ⇒ no flash. `:app` supplies the tokens; the dashboard only animates. */
data class BgPulses(val server: Long = 0L, val cgm: Long = 0L, val watch: Long = 0L)

/** A centered vitals row across the top of the BG panel: the three reachability traffic-lights (server /
 *  CGM / watch), the labelled device temperature (U9 — there is no readable fan), the step count, a
 *  fixed-60-bpm liveness heartbeat, and the sensor-lifetime countdown. */
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
        // Issue 3: the CGM link's signal bars live ONLY in the header now (the "AiDEX X … −60 dBm"
        // meter); this chip keeps just its traffic-light so the CGM RSSI is shown in exactly one place.
        ReachChip("CGM", r.cgm, null, pulses?.cgm ?: 0L)
        ReachChip("WCH", r.watch, signals?.watchRssi, pulses?.watch ?: 0L)
        deviceTempC?.let { TempChip(it, tempUnit, thermalThresholdC, thermalWarnMarginC) }
        stepsToday?.let { StepsChip(it) }
        // A fixed-60-bpm liveness heartbeat, just past the steps count (never a real heart-rate reading).
        HeartbeatChip()
        // The sensor time-left, counted down live, right of TEMP: to the end of warm-up while the sensor
        // is warming up, otherwise to the user-entered expiry instant. Either instant alone is enough to
        // show it — the two are independent, and gating the whole chip on the expiry would suppress the
        // warm-up state whenever no expiry is known.
        if (sensorExpiryMs != null || sensorWarmupEndMs != null) {
            SensorLifeChip(sensorExpiryMs, sensorWarmupEndMs)
        }
    }
}

/**
 * The sensor time-left chip, in three states. Its two instants have DIFFERENT provenance: [expiryMs] is
 * the user-entered sensor lifetime (Settings → CGM), because a passive advertisement listener cannot read
 * the sensor's true age; [warmupEndMs] alone is sensor-anchored — the sensor's own age (`minFromStart`)
 * plus the active source's configured warm-up window (also on the CGM panel).
 *
 *  - **warming up** ([warmupEndMs] non-null) — counts down to the END OF WARM-UP.
 *  - **live** — counts down to [expiryMs].
 *  - **expired** — `EXP`.
 *
 * The first two are deliberately indistinguishable as text: both are a BARE countdown in the largest
 * meaningful unit (`10d` / `5h` / `10m` / `35s`), because this chip sits in a row of terse abbreviations
 * and a prefix overflowed its width, wrapping mid-word. Colour is what separates them — which is exactly
 * the sort of distinction a screen reader cannot see, so each state also carries a `contentDescription`
 * naming what its number counts down to. Sighted ambiguity was chosen; inaccessibility was not.
 *
 * **The warm-up STATE is [warmupEndMs]'s nullity, not its ordering against the clock.** The flow supplies
 * an instant only while the pipeline flags the sensor `WARMUP`, and this chip does not re-derive that
 * verdict from the deadline — the two are computed off the same window and the same sensor age, but the
 * flag is what the trace, the header suffix and the CGM light all already agree with. So a warm-up
 * deadline that has slipped into the past while the flag still stands means the sensor is still warming
 * and the app cannot say for how much longer — not that warm-up ended. The chip keeps the warm-up colour
 * and prints `WARM` in place of a countdown: it will not fabricate an instant it has not been given, and
 * it will not fall back to a live countdown that contradicts everything else on the panel at once.
 *
 * Both instants are optional and independent; the caller composes this whenever either exists. `EXP` is
 * reachable only with an [expiryMs] to have passed.
 */
@Composable
private fun SensorLifeChip(expiryMs: Long?, warmupEndMs: Long?) {
    val now by produceState(System.currentTimeMillis(), expiryMs, warmupEndMs) {
        while (true) {
            val wall = System.currentTimeMillis()
            value = wall
            // Count down whichever instant is in play; crossing the end of warm-up shortens the cadence
            // on its own, because the target then becomes expiry.
            val remaining = ((warmupEndMs?.takeIf { it > wall } ?: expiryMs) ?: wall) - wall
            // Tick every second under a minute, else once a minute — enough to keep the largest unit fresh.
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
        // Warm-up borrows the colour the graph already paints WARMUP readings in (`secondary`), so the
        // chip and the trace say the same thing at the same moment.
        color = when {
            expired -> MaterialTheme.colorScheme.error
            warmingUp -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        },
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

/** Largest meaningful unit of a positive remaining duration: days, else hours, else minutes, else
 *  seconds. */
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

/** The device temperature (U9): the battery sensor's reading in the chosen unit, LABELLED as such so
 *  it is never mistaken for a fan/ambient figure (there is no readable fan RPM on this device). F6 — it
 *  also carries the inference thermal-gate state: as the battery sensor nears the gate threshold the
 *  glyph turns amber (WARN band) and turns red once it crosses (CRITICAL, inference paused). A null
 *  [thermalThresholdC] (gate disabled) leaves it its neutral colour. */
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

/** BG-panel steps chip: a walking-figure glyph + today's step count, human-readable (400 / 5K / 5.4K). */
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

/** As-is below 1000, else "K" (e.g. 400, 5K, 5.4K, 12K). */
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
        // N4b — the traffic light animates by state: steady green when OK, a slow amber pulse when
        // degraded, an urgent red pulse when down; static (no pulse) when animations are disabled (N4c).
        // I12 — additionally, a one-shot flash halo fires whenever [pulseKey] changes (the channel moved).
        PulsingDot(light.health, pulseKey)
        Text(tag, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        rssi?.let { SignalBars(it) }
    }
}

/** The BG-panel liveness heart (N4a family): a themed heart glyph that beats at a FIXED 60 bpm — one
 *  lub-dub per second, so the animation cadence *is* the rate — with that rate spelled out as a "60"
 *  to its right (mirroring the steps chip). Decorative liveness, not a reading from any sensor; the
 *  heart collapses to a static glyph the instant [LocalAnimationsEnabled] is off (N4c). */
@Composable
private fun HeartbeatChip() {
    val animationsOn = LocalAnimationsEnabled.current
    val style = iconStyleForTheme(LocalT1dmSemantics.current.id)
    val icon = remember(style) { com.t1dm.core.design.heartIcon(style) }
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    // 60 bpm ⇒ a 1000 ms beat period. Keyframed as a lub-dub (two quick contractions, then rest) so it
    // reads as a pulse rather than a breath; the keyframe span equals the period, fixing the cadence.
    //
    // The State is HELD, never unwrapped here: `.value` is read inside the graphicsLayer block below, so
    // a beat invalidates that layer's placement alone. Read in composition — as it was — every frame of
    // an animation that never stops bought a recomposition and a relayout of this Row to move one glyph.
    // Same rule, for the same reason, as `pulseHighlight` (core/design/Pulse.kt).
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

/** The animated reachability light (N4b/N4c): a pulsing dot whose cadence encodes severity, collapsing
 *  to a steady dot the instant [LocalAnimationsEnabled] is off. I12 — on top of the STATE animation, a
 *  transient halo blooms and fades once each time [pulseKey] changes (the channel moved: a new reading,
 *  a send/receive, a push/ack). The halo is an expanding ring — visually distinct from the steady/
 *  degraded/down colour states — and is suppressed entirely when animations are disabled. */
@Composable
private fun PulsingDot(health: LinkHealth, pulseKey: Long = 0L) {
    val animationsOn = LocalAnimationsEnabled.current
    val color = health.color()
    // Only DEGRADED (slow) and DOWN (urgent) pulse; OK/OFF are steady.
    val periodMs = when (health) {
        LinkHealth.DEGRADED -> 1400
        LinkHealth.DOWN -> 600
        else -> 0
    }
    // Held as State, unwrapped in the layer blocks below (see [HeartbeatChip]). This one matters most of
    // the three: it runs precisely when the link is DEGRADED or DOWN, i.e. when the phone is already
    // struggling, and it drove BOTH a Modifier-chain rebuild and — through the flash below — an
    // add/remove of a child node, so every frame recomposed and re-laid-out the dot.
    val pulseAlpha = if (animationsOn && periodMs > 0) {
        val transition = rememberInfiniteTransition(label = "reach")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse),
            label = "reachAlpha",
        )
    } else remember { mutableFloatStateOf(1f) }
    // I12 — the one-shot data-movement flash. `snapTo(1)` then `animateTo(0)` gives an expanding,
    // fading ring; keyed on [pulseKey] so it re-fires on every channel move. Never animates on the
    // very first composition (pulseKey seeds from the current value in the caller) or when motion is off.
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
        // The halo is composed UNCONDITIONALLY and hidden by its own layer alpha, rather than gated on
        // `flash.value > 0f` in composition: that test added a node when the ring bloomed and removed it
        // when it died, so the 700 ms flash recomposed and re-measured this Box on every frame of it.
        // At f = 0 the layer draws nothing (alpha 0, scale 1) and the node is the same 9.dp as its
        // sibling, so neither the pixels nor the measured size move.
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
        // The severity pulse as a layer alpha rather than a per-frame `background(color.copy(…))`: a
        // solid fill of alpha `A` composited at layer alpha `a` is the same source-over result as a fill
        // of alpha `A·a`, which is exactly what the old copy computed — without rebuilding the chain.
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

/**
 * The car's start line: a tap on the panel, mapped back through the viewport the graph just reported.
 *
 * This replaces a day-picker modal. The instant under the finger IS the instant the run begins, so
 * placing the car is one gesture on the thing being described rather than a dialog about it. The
 * x→time map is the graph's own — inset for the value axis — so the car lands where it was pointed at
 * whatever the pan or zoom.
 *
 * [topInset] is the panel's own top strip, not a constant: the hint hangs below it because that strip
 * is where the model's predicted-clock labels are drawn, and a fixed pad put the hint over them.
 */
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
