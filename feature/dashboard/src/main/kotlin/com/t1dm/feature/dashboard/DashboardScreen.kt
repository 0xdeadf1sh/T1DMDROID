package com.t1dm.feature.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.iconStyleForTheme
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import com.t1dm.core.model.WarmupProgress
import com.t1dm.ui.graph.CurveOverlayFrame
import com.t1dm.ui.graph.CurveOverlayToggles
import com.t1dm.ui.graph.ExcursionMarker
import com.t1dm.ui.graph.GlucoseGraph
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.PredSeries
import com.t1dm.ui.graph.PredictedClock
import com.t1dm.ui.graph.SmoothedTrace
import com.t1dm.ui.graph.smoothedTraceOf
import com.t1dm.ui.graph.curveOverlayOf
import com.t1dm.ui.graph.excursionsOf
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
    rangeMinMgdl: Int = 20,
    rangeMaxMgdl: Int = 250,
    initialWindowHours: Int = 6,
    onSetWindowHours: ((Int) -> Unit)? = null,
    reachability: BgReachability? = null,
    signals: BgSignals? = null,
    // Issues 7 & 9 — the warmup-surviving circadian belief, so the TOP axis renders the predicted
    // clock even while the BG forecast is (correctly) suppressed. Falls back to the selected
    // prediction's copy once a full cycle publishes.
    circadianTime: PredictedTime? = null,
    circadianAnchorMs: Long? = null,
    // Issue 13 — the causal SavGol smoother (mg/dL, clamps [20,500]) the model consumes; when wired, a
    // toggle overlays the smoothed model-input trace. Native call is passed as a lambda so this module
    // keeps no JNI dependency.
    smoothMgdl: ((DoubleArray) -> DoubleArray)? = null,
) {
    val frame by produceState(GraphFrame.EMPTY, readings, unit) {
        value = graphFrameOf(readings, unit, kovatchevF = kovatchevF)
    }
    val overlay by produceState(emptyList<PredSeries>(), predictions, unit) {
        value = predOverlayOf(predictions, unit, kovatchevF = kovatchevF)
    }

    var toggles by remember { mutableStateOf(CurveOverlayToggles()) }
    var windowHours by remember(initialWindowHours) { mutableStateOf(initialWindowHours) }

    // Reconstruct the carb/insulin channels over the readings' grid span, extended INTO THE FUTURE by
    // a fixed horizon so the committed doses' appearance/action tails are visible in the prediction
    // zone (item 2), not only their history — off-thread. Built whenever the resolver is wired (not
    // gated on the toggles) so the scrub read-out can report the rates even with the overlay hidden;
    // the DRAW is still gated by [toggles].
    // Keyed on iobCob too so a just-logged dose (which emits a new IOB/COB read-out) rebuilds the
    // overlay immediately, rather than waiting for the next CGM reading — this keeps the insulin/basal
    // overlay (issue 18) and the no-future-insulin advisory (issue 16) current the moment a dose lands.
    val curveOverlay by produceState(CurveOverlayFrame.EMPTY, readings, predictions, curveChannels, basalChannel, iobCob) {
        val resolver = curveChannels
        if (resolver == null || readings.isEmpty()) {
            value = CurveOverlayFrame.EMPTY
            return@produceState
        }
        val gridStart = readings.minOf { it.tsMs } / STEP_MS * STEP_MS
        val lastReading = readings.maxOf { it.tsMs }
        val lastForecast = predictions.maxOfOrNull { it.anchorTsMs + it.horizonSteps.toLong() * it.stepMs } ?: lastReading
        // Always reach at least OVERLAY_FUTURE_MS past now so a just-logged dose shows its rising tail
        // even before a forecast exists (warmup); the same event-reconstructed channel carries both
        // the past and the future portions (bucketize lays the full curve across the window).
        val end = maxOf(lastReading, lastForecast, System.currentTimeMillis() + OVERLAY_FUTURE_MS)
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
    val excursions: List<ExcursionMarker> = remember(predictions, thresholds) {
        if (thresholds == null) emptyList()
        else excursionsOf(predictions, thresholds.lowMgdl, thresholds.highMgdl)
    }

    Column(Modifier.fillMaxSize()) {
        reachability?.let { ReachabilityBar(it, signals) }
        DashboardHeader(latest, activeSourceName, unit, signals?.cgmRssi ?: latest?.rssi)
        warmup?.let { WarmupBanner(it) }
        if (noFutureInsulin) NoFutureInsulinBanner()
        if (iobCob != null || curveChannels != null || smoothMgdl != null) {
            OverlayControls(
                iobCob = iobCob,
                toggles = toggles,
                windowHours = windowHours,
                smoothAvailable = smoothMgdl != null,
                showSmoothed = showSmoothed,
                onToggle = { toggles = it },
                onToggleSmoothed = { showSmoothed = it },
                onWindow = { h ->
                    windowHours = h
                    onSetWindowHours?.invoke(h)
                },
            )
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
            excursions = excursions,
            smoothed = smoothed,
            showSmoothed = showSmoothed,
        )
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
        Text(
            "Forecasts resume once enough real history exists to condition on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
        Text(
            "Neither a committed bolus tail nor a basal schedule covers the hours ahead. If you take " +
                "long-acting basal, set its schedule so the model and calculator see your background insulin.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/** IOB/COB read-out + the carb/insulin overlay toggles (Phase 4) + the 6/12/24h window buttons
 *  (item 5). */
@Composable
private fun OverlayControls(
    iobCob: IobCobReadout?,
    toggles: CurveOverlayToggles,
    windowHours: Int,
    smoothAvailable: Boolean,
    showSmoothed: Boolean,
    onToggle: (CurveOverlayToggles) -> Unit,
    onToggleSmoothed: (Boolean) -> Unit,
    onWindow: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // N2 — the Carbs / Insulin / Smoothed / 6h / 12h / 24h chips no longer fit across a phone width,
        // so they live in a HORIZONTALLY-SCROLLABLE row (mirroring the scrollable nav) — nothing clips.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        iobCob?.let {
            Text(
                "IOB ${"%.1f".format(it.iobU)}U · COB ${"%.0f".format(it.cobG)}g" +
                    (it.minsSinceLastLoggedInsulin?.let { m -> " · logged ${m}m ago" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
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

/** Three centered traffic-lights across the top of the BG panel; tapping toggles the labels. */
@Composable
private fun ReachabilityBar(r: BgReachability, signals: BgSignals?) {
    var showLabels by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { showLabels = !showLabels },
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReachChip("SRV", r.server, showLabels, null)
        // Issue 3: the CGM link's signal bars live ONLY in the header now (the "AiDEX X … −60 dBm"
        // meter); this chip keeps just its traffic-light so the CGM RSSI is shown in exactly one place.
        ReachChip("CGM", r.cgm, showLabels, null)
        ReachChip("WCH", r.watch, showLabels, signals?.watchRssi)
    }
}

@Composable
private fun ReachChip(tag: String, light: ReachLight, showLabel: Boolean, rssi: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // N4b — the traffic light animates by state: steady green when OK, a slow amber pulse when
        // degraded, an urgent red pulse when down; static (no pulse) when animations are disabled (N4c).
        PulsingDot(light.health)
        Text(tag, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        rssi?.let { SignalBars(it) }
        if (showLabel) {
            Text(light.label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

/** The animated reachability light (N4b/N4c): a pulsing dot whose cadence encodes severity, collapsing
 *  to a steady dot the instant [LocalAnimationsEnabled] is off. */
@Composable
private fun PulsingDot(health: LinkHealth) {
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
    Box(Modifier.size(9.dp).clip(CircleShape).background(color.copy(alpha = color.alpha * alpha)))
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

/** A four-bar RSSI meter + the raw dBm (item 20). Buckets follow the usual BLE bands. */
@Composable
private fun SignalBars(rssi: Int) {
    val filled = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        // N4d — the bars sat slightly too low against the source name / dBm text; a small upward offset
        // lifts them to the text centre while keeping the ascending-bar baseline.
        Row(
            Modifier.offset(y = (-2).dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            for (i in 1..4) {
                Box(
                    Modifier.width(3.dp).height((3 + i * 2).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (i <= filled) on else off),
                )
            }
        }
        Text(" ${rssi}dBm", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
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
