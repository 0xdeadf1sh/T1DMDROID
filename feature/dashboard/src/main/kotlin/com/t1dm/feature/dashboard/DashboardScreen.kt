package com.t1dm.feature.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.SignalBars
import com.t1dm.core.design.iconStyleForTheme
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.RolledForecast
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.TempUnit
import com.t1dm.core.model.ThermalLevel
import com.t1dm.core.model.UnitSpace
import com.t1dm.core.model.WarmupProgress
import com.t1dm.ui.graph.CurveOverlayFrame
import com.t1dm.ui.graph.CurveOverlayToggles
import com.t1dm.ui.graph.GlucoseGraph
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.PredSeries
import com.t1dm.ui.graph.PredictedClock
import com.t1dm.ui.graph.RolledSeries
import com.t1dm.ui.graph.SmoothedTrace
import com.t1dm.ui.graph.rolledSeriesOf
import com.t1dm.ui.graph.smoothedTraceOf
import com.t1dm.ui.graph.curveOverlayOf
import com.t1dm.ui.graph.graphFrameOf
import com.t1dm.ui.graph.noFutureInsulinOverForecast
import com.t1dm.ui.graph.predOverlayOf

/**
 * The live BG dashboard (Phase 1 — "Dashboard shows the live graph + current
 * BG/trend from the Repository Flow"). It is a pure function of the state `:app` collects from the
 * repository: a header with the latest measurement + trend + active source, and the reusable
 * [GlucoseGraph] over a [GraphFrame] built off-thread by [graphFrameOf]. No storage, no service,
 * no `:inference` — just the observed truth.
 *
 * Phase 4 adds the toggleable curve overlays: the carb-appearance (Ra) and insulin-action curves
 * drawn UNDER the BG line, reconstructed from the logged events by `:app`'s `ChannelBuilder`
 * (off-thread), plus an IOB/COB read-out carrying its §3.6-F provenance. [curveChannels] resolves the
 * two per-5-min channels for a grid window; it is invoked off the main thread inside [produceState],
 * and the [CurveOverlayFrame] it yields is immutable primitive arrays the Canvas merely paints.
 */
@Composable
fun DashboardScreen(
    readings: List<CgmReading>,
    latest: CgmReading?,
    activeSourceName: String?,
    thresholds: AlertThresholds? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    predictions: List<ModelPrediction> = emptyList(),
    kovatchevF: ((Double) -> Double)? = null,
    iobCob: IobCobReadout? = null,
    curveChannels: (suspend (gridStartMs: Long, nSteps: Int) -> Pair<DoubleArray, DoubleArray>)? = null,
    basalChannel: (suspend (gridStartMs: Long, nSteps: Int) -> DoubleArray)? = null,
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
    // I11 — the user-entered sensor-lifetime expiry instant (absolute epoch-ms), counted down live to
    // the right of the TEMP readout. Null ⇒ nothing shown (no estimate entered). It is a user estimate,
    // NOT read from the passive-advertisement sensor.
    sensorExpiryMs: Long? = null,
    // Issues 7 & 9 — the warmup-surviving circadian belief, so the TOP axis renders the predicted
    // clock even while the BG forecast is (correctly) suppressed. Falls back to the selected
    // prediction's copy once a full cycle publishes.
    circadianTime: PredictedTime? = null,
    circadianAnchorMs: Long? = null,
    // Issue 13 — the causal SavGol smoother (mg/dL, clamps [20,500]) the model consumes; when wired, a
    // toggle overlays the smoothed model-input trace. Native call is passed as a lambda so this module
    // keeps no JNI dependency.
    smoothMgdl: ((DoubleArray) -> DoubleArray)? = null,
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
) {
    val frame by produceState(GraphFrame.EMPTY, readings, unit) {
        value = graphFrameOf(readings, unit, kovatchevF = kovatchevF)
    }
    val overlay by produceState(emptyList<PredSeries>(), predictions, unit) {
        value = predOverlayOf(predictions, unit, kovatchevF = kovatchevF)
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
    val curveOverlay by produceState(CurveOverlayFrame.EMPTY, readings, predictions, curveChannels, basalChannel, iobCob, rolledForecast) {
        val resolver = curveChannels
        if (resolver == null || readings.isEmpty()) {
            value = CurveOverlayFrame.EMPTY
            return@produceState
        }
        val oldestReading = readings.minOf { it.tsMs } / STEP_MS * STEP_MS
        val lastReading = readings.maxOf { it.tsMs }
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
        val (carb, insulin) = resolver(gridStart, nSteps)
        val basal = basalChannel?.invoke(gridStart, nSteps) ?: DoubleArray(0)
        value = curveOverlayOf(carb, insulin, gridStart, STEP_MS, basal)
    }

    // Issue 16: advise when NO insulin action covers the forecast horizon — neither a committed bolus
    // tail nor a basal schedule (the auto-extended basal is already folded into the combined channel,
    // so an active schedule keeps this false). Purely advisory; never actuates.
    val noFutureInsulin = remember(curveOverlay, predictions) {
        noFutureInsulinOverForecast(curveOverlay, predictions, System.currentTimeMillis())
    }

    // Issue 13: the smoothed model-input trace (built off-thread; null unless the smoother is wired).
    val smoothed by produceState<SmoothedTrace?>(null, readings, unit, smoothMgdl) {
        val f = smoothMgdl
        value = if (f == null || readings.isEmpty()) null
        else smoothedTraceOf(readings, unit, f, kovatchevF)
    }
    var showSmoothed by remember { mutableStateOf(false) }

    // The selected model's circadian-phase clock (item 21) + its approaching excursions (item 16).
    // Issues 7 & 9: prefer the in-cycle prediction's belief, but fall back to the warmup-surviving
    // [circadianTime] so the TOP axis still renders the predicted clock while forecasts are withheld.
    val selected = predictions.firstOrNull { it.selected }
    val clockSource = selected?.predictedTime ?: circadianTime
    val clockAnchor = selected?.anchorTsMs ?: circadianAnchorMs
    val predictedClock = if (clockSource != null && clockAnchor != null) clockSource.toClock(clockAnchor) else null

    Column(Modifier.fillMaxSize()) {
        reachability?.let {
            ReachabilityBar(it, signals, pulses, deviceTempC, temperatureUnit, sensorExpiryMs, thermalThresholdC, thermalWarnMarginC, stepsToday)
        }
        DashboardHeader(latest, activeSourceName, unit, signals?.cgmRssi ?: latest?.rssi)
        warmup?.let { WarmupBanner(it) }
        if (noFutureInsulin) NoFutureInsulinBanner()
        if (iobCob != null || curveChannels != null || smoothMgdl != null || onRoll != null) {
            OverlayControls(
                iobCob = iobCob,
                showNextForecast = warmup == null && !lowPowerActive,
                forecastAdaptive = forecastAdaptive,
                forecastPeriodMin = forecastPeriodMin,
                toggles = toggles,
                windowHours = windowHours,
                smoothAvailable = smoothMgdl != null,
                showSmoothed = showSmoothed,
                rollAvailable = onRoll != null,
                rollComputing = rollComputing,
                onRollClick = { showRollDialog = true },
                onToggle = { toggles = it },
                onToggleSmoothed = { showSmoothed = it },
                onWindow = { h ->
                    windowHours = h
                    onSetWindowHours?.invoke(h)
                },
            )
        }
        // I2 — the ephemeral rolled-forecast status line: a plain reason when it is degenerate/absent,
        // otherwise a note that the drawn tail is extrapolated + display-only, with a Clear affordance.
        rolledForecast?.takeIf { !it.isEmpty || it.reason != null }?.let { rf ->
            RolledStatusBanner(rf, onClear = onClearRoll)
        }
        GlucoseGraph(
            frame = frame,
            modifier = Modifier.fillMaxWidth().weight(1f),
            thresholds = thresholds,
            initialWindowMin = windowHours * 60f,
            predictions = overlay,
            curveOverlay = curveOverlay,
            curveToggles = toggles,
            rangeMinMgdl = rangeMinMgdl,
            rangeMaxMgdl = rangeMaxMgdl,
            predictedClock = predictedClock,
            smoothed = smoothed,
            showSmoothed = showSmoothed,
            rolled = rolledSeries,
            futureExtentMs = FUTURE_VIEW_MS,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(hours) }) { Text("Roll ${rollHoursLabel(hours)}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Roll the forecast forward") },
        text = {
            Column {
                Text(
                    "Rolling re-feeds the model's own 2-hour forecast back into its context $rolls " +
                        "time${if (rolls == 1) "" else "s"}. Beyond 2 hours the result is EXTRAPOLATED and " +
                        "unvalidated — error compounds with each roll. It is shown for inspection only and " +
                        "will not raise alerts.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text("Horizon: ${rollHoursLabel(hours)}", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = stepIdx,
                    onValueChange = { stepIdx = it },
                    valueRange = 1f..24f,
                    steps = 22, // 24 discrete stops (endpoints + 22 interior)
                )
                Text(
                    "30 min – 12 h · $rolls forward${if (rolls == 1) "" else "s"} on the CPU authority",
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
        rf.isEmpty -> "No rolled forecast to show."
        else -> "Rolled to ${rollHoursLabel(rf.requestedHours)} — the region past 2 h is extrapolated and display-only."
    }
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
            TextButton(onClick = it) { Text("Clear") }
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
            "Collecting context — %.1f / %.0f h of measured BG".format(warmup.measuredHours, warmup.requiredHours),
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
            "No insulin on board over the forecast window",
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
    showNextForecast: Boolean,
    forecastAdaptive: Boolean,
    forecastPeriodMin: Int,
    toggles: CurveOverlayToggles,
    windowHours: Int,
    smoothAvailable: Boolean,
    showSmoothed: Boolean,
    rollAvailable: Boolean,
    rollComputing: Boolean,
    onRollClick: () -> Unit,
    onToggle: (CurveOverlayToggles) -> Unit,
    onToggleSmoothed: (Boolean) -> Unit,
    onWindow: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // N2 — the Roll / Carbs / Insulin / Smoothed / 6h / 12h / 24h chips no longer fit across a phone
        // width, so they live in a HORIZONTALLY-SCROLLABLE row (mirroring the nav) — nothing clips.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // I2 — the Roll button, to the LEFT of the Carbs chip. Shows a spinner while computing.
            if (rollAvailable) {
                AssistChip(
                    onClick = { if (!rollComputing) onRollClick() },
                    enabled = !rollComputing,
                    label = { Text(if (rollComputing) "Rolling…" else "Roll") },
                    leadingIcon = if (rollComputing) {
                        { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) }
                    } else null,
                )
            }
            FilterChip(
                selected = toggles.carbs,
                onClick = { onToggle(toggles.copy(carbs = !toggles.carbs)) },
                label = { Text("Carbs") },
            )
            FilterChip(
                selected = toggles.insulin,
                onClick = { onToggle(toggles.copy(insulin = !toggles.insulin)) },
                label = { Text("Insulin") },
            )
            if (smoothAvailable) {
                FilterChip(
                    selected = showSmoothed,
                    onClick = { onToggleSmoothed(!showSmoothed) },
                    label = { Text("Smoothed") },
                )
            }
            listOf(6, 12, 24).forEach { h ->
                FilterChip(
                    selected = windowHours == h,
                    onClick = { onWindow(h) },
                    label = { Text("${h}h") },
                )
            }
        }
        // Issue 1 — the IOB/COB read-out and the live "next forecast" countdown share one line, kept
        // horizontally scrollable so neither clips. The countdown stays a separate composable so its
        // per-second tick never re-renders the static IOB/COB text. It is shown only when a forecast is
        // actually being made ([showNextForecast] = not in warmup and not low-power).
        if (iobCob != null || showNextForecast) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                iobCob?.let {
                    Text(
                        "IOB ${"%.1f".format(it.iobU)}U · COB ${"%.0f".format(it.cobG)}g" +
                            (it.minsSinceLastLoggedInsulin?.let { m -> " · logged ${m}m ago" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                if (showNextForecast) {
                    if (iobCob != null) {
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
            "Adaptive forecast enabled",
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
    val text = if (mins >= 1) "Next forecast in ${mins}m ${secs}s" else "Next forecast in ${secs}s"
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
        // I11 — the user-entered sensor lifetime, counted down live, immediately right of the TEMP chip.
        sensorExpiryMs?.let { SensorLifeChip(it) }
    }
}

/** I11 — the sensor-lifetime countdown chip. Because the AiDEX X is a passive advertisement listener
 *  we cannot read the sensor's true age, so this counts down a USER-ENTERED expiry instant, showing the
 *  largest meaningful unit (`10d` / `5h` / `10m` / `35s` · "rem."). It re-reads the wall clock on a
 *  cadence matched to the displayed granularity so it stays honest without a busy loop. It is plainly a
 *  user estimate, not a reading from the sensor (see Settings → CGM source). */
@Composable
private fun SensorLifeChip(expiryMs: Long) {
    val now by produceState(System.currentTimeMillis(), expiryMs) {
        while (true) {
            value = System.currentTimeMillis()
            val remaining = expiryMs - value
            // Tick every second under a minute, else once a minute — enough to keep the largest unit fresh.
            kotlinx.coroutines.delay(if (remaining in 1..60_000L) 1_000L else 60_000L)
        }
    }
    val remainingMs = expiryMs - now
    val text = if (remainingMs <= 0L) "sensor expired" else "${formatRemaining(remainingMs)} rem."
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (remainingMs <= 0L) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
}

/** F1 — a live "received Ns ago" chip under the source name, driven off the raw phone-receive wall time
 *  ([CgmReading.rxWallMs], before the grid snap) so the freshness of the CGM link is legible at a glance.
 *  It ticks each second while the reading is under a minute old, then falls back to a per-minute cadence
 *  so it stays honest without a busy loop. */
@Composable
private fun LastReadingChip(rxWallMs: Long) {
    val now by produceState(System.currentTimeMillis(), rxWallMs) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(if ((value - rxWallMs) in 0..60_000L) 1_000L else 60_000L)
        }
    }
    Text(
        "received ${formatAge((now - rxWallMs).coerceAtLeast(0))}",
        style = MaterialTheme.typography.labelSmall,
        // Tabular monospace so the per-second reflow (proportional digits) no longer shifts the chip.
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
}

/** Elapsed-time phrasing for the last-reading chip: seconds, minutes, hours (h + m), then days. The
 *  numeric fields are space-padded to a fixed width so — under the chip's monospace font — the string
 *  keeps a constant width as it ticks (a leading space is one digit cell), and the End-anchored chip
 *  never shifts when a single digit rolls over to two (9s → 10s). */
private fun formatAge(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "%2ds ago".format(s)
        s < 3600 -> "%2dm ago".format(s / 60)
        s < 86_400 -> "%2dh %2dm ago".format(s / 3600, (s % 3600) / 60)
        else -> "%2dd ago".format(s / 86_400)
    }
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
        ).value
    } else 1f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = "Heartbeat 60 bpm",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp).graphicsLayer { scaleX = scale; scaleY = scale },
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
    val alpha = if (animationsOn && periodMs > 0) {
        val transition = rememberInfiniteTransition(label = "reach")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse),
            label = "reachAlpha",
        ).value
    } else 1f
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
        val f = flash.value
        if (f > 0f) {
            Box(
                Modifier
                    .size(9.dp)
                    .graphicsLayer {
                        val s = 1f + f * 1.6f
                        scaleX = s; scaleY = s; this.alpha = f * 0.7f
                    }
                    .clip(CircleShape)
                    .background(color.copy(alpha = color.alpha)),
            )
        }
        Box(Modifier.size(9.dp).clip(CircleShape).background(color.copy(alpha = color.alpha * alpha)))
    }
}

/** N4a — the animated per-theme time-of-day icon, right of the current BG value. */
@Composable
private fun TimeOfDayIcon() {
    val animationsOn = LocalAnimationsEnabled.current
    // Re-evaluate the period once a minute so it stays honest without a busy loop.
    val hour by produceState(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
        while (true) {
            value = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            kotlinx.coroutines.delay(60_000)
        }
    }
    val style = iconStyleForTheme(LocalT1dmSemantics.current.id)
    val period = com.t1dm.core.design.dayPeriodFor(hour)
    val icon = remember(period, style) { com.t1dm.core.design.timeOfDayIcon(period, style) }
    // A subtle breathing scale; a static 1f when motion is disabled (N4c).
    val scale = if (animationsOn) {
        val transition = rememberInfiniteTransition(label = "tod")
        transition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
            label = "todScale",
        ).value
    } else 1f
    Icon(
        imageVector = icon,
        contentDescription = "Time of day: ${period.name.lowercase()}",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp).graphicsLayer { scaleX = scale; scaleY = scale },
    )
}

@Composable
private fun LinkHealth.color(): Color = when (this) {
    LinkHealth.OK -> Color(0xFF3DD68C)
    LinkHealth.DEGRADED -> MaterialTheme.colorScheme.secondary
    LinkHealth.DOWN -> MaterialTheme.colorScheme.error
    LinkHealth.OFF -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
}

@Composable
private fun DashboardHeader(latest: CgmReading?, activeSourceName: String?, unit: UnitSpace, cgmRssi: Int?) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = formatBg(latest?.bgMgdl, unit),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                // N4a — a per-theme morning/noon/evening/night icon derived from the ACTUAL local time,
                // in the same geometry system as the nav icons, subtly animated (honouring the
                // "disable all animations" toggle via LocalAnimationsEnabled).
                TimeOfDayIcon()
            }
            Text(
                text = unitLabel(unit) + (latest?.let { statusSuffix(it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = trendArrow(latest?.trendTenthsPerMin), style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = activeSourceName ?: "no source", style = MaterialTheme.typography.bodySmall)
                cgmRssi?.let { SignalBars(it) }
            }
            latest?.rxWallMs?.let { LastReadingChip(it) }
        }
    }
}

private fun statusSuffix(r: CgmReading): String = when {
    r.flag == ReadingFlag.WARMUP -> "  • warmup"
    r.provenance == ReadingProvenance.INTERPOLATED -> "  • interpolated"
    else -> ""
}

private fun unitLabel(unit: UnitSpace): String = when (unit) {
    UnitSpace.MgDl -> "mg/dL"
    UnitSpace.MmolL -> "mmol/L"
    UnitSpace.Kovatchev -> "risk"
}

private fun formatBg(bgMgdl: Int?, unit: UnitSpace): String {
    if (bgMgdl == null) return "--"
    return when (unit) {
        UnitSpace.MgDl, UnitSpace.Kovatchev -> bgMgdl.toString()
        UnitSpace.MmolL -> String.format("%.1f", bgMgdl / 18.0182)
    }
}

/** Coarse trend glyph from the 0.1 mg/dL/min rate (real arrows are Phase 7 polish). */
private fun trendArrow(tenths: Int?): String = when {
    tenths == null -> "→"
    tenths >= 30 -> "⇈"
    tenths >= 10 -> "↗"
    tenths <= -30 -> "⇊"
    tenths <= -10 -> "↘"
    else -> "→"
}
