package com.t1dm.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import com.t1dm.core.model.WarmupProgress
import com.t1dm.ui.graph.CurveOverlayFrame
import com.t1dm.ui.graph.CurveOverlayToggles
import com.t1dm.ui.graph.GlucoseGraph
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.PredSeries
import com.t1dm.ui.graph.curveOverlayOf
import com.t1dm.ui.graph.graphFrameOf
import com.t1dm.ui.graph.predOverlayOf

/**
 * The live BG dashboard (PLAN.private.md Phase 1 — "Dashboard shows the live graph + current
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
    warmup: WarmupProgress? = null,
) {
    val frame by produceState(GraphFrame.EMPTY, readings, unit) {
        value = graphFrameOf(readings, unit, kovatchevF = kovatchevF)
    }
    val overlay by produceState(emptyList<PredSeries>(), predictions, unit) {
        value = predOverlayOf(predictions, unit, kovatchevF = kovatchevF)
    }

    var toggles by remember { mutableStateOf(CurveOverlayToggles()) }

    // Reconstruct the carb/insulin channels over the readings' grid span (extended past the last
    // reading to cover the forecast horizon), off-thread. Only when a channel is toggled on and the
    // resolver is wired — otherwise the overlay is EMPTY and the graph draws nothing extra.
    val curveOverlay by produceState(CurveOverlayFrame.EMPTY, readings, predictions, toggles, curveChannels) {
        val resolver = curveChannels
        if (resolver == null || !toggles.any || readings.isEmpty()) {
            value = CurveOverlayFrame.EMPTY
            return@produceState
        }
        val gridStart = readings.minOf { it.tsMs } / STEP_MS * STEP_MS
        val lastReading = readings.maxOf { it.tsMs }
        val lastForecast = predictions.maxOfOrNull { it.anchorTsMs + it.horizonSteps.toLong() * it.stepMs } ?: lastReading
        val end = maxOf(lastReading, lastForecast)
        val nSteps = (((end - gridStart) / STEP_MS).toInt() + 1).coerceIn(1, MAX_OVERLAY_STEPS)
        val (carb, insulin) = resolver(gridStart, nSteps)
        value = curveOverlayOf(carb, insulin, gridStart, STEP_MS)
    }

    Column(Modifier.fillMaxSize()) {
        DashboardHeader(latest, activeSourceName, unit)
        warmup?.let { WarmupBanner(it) }
        if (iobCob != null || curveChannels != null) {
            OverlayControls(iobCob, toggles) { toggles = it }
        }
        GlucoseGraph(
            frame = frame,
            modifier = Modifier.fillMaxWidth().weight(1f),
            thresholds = thresholds,
            predictions = overlay,
            curveOverlay = curveOverlay,
            curveToggles = toggles,
        )
    }
}

private const val STEP_MS: Long = 300_000L
private const val MAX_OVERLAY_STEPS: Int = 4032 // ~14 days of 5-min buckets

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

/** IOB/COB read-out + the carb/insulin overlay toggles (PLAN.private.md Phase 4). */
@Composable
private fun OverlayControls(
    iobCob: IobCobReadout?,
    toggles: CurveOverlayToggles,
    onToggle: (CurveOverlayToggles) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
        iobCob?.let {
            Text(
                "IOB ${"%.1f".format(it.iobU)}U · COB ${"%.0f".format(it.cobG)}g" +
                    (it.minsSinceLastLoggedInsulin?.let { m -> " · logged ${m}m ago" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun DashboardHeader(latest: CgmReading?, activeSourceName: String?, unit: UnitSpace) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = formatBg(latest?.bgMgdl, unit),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = unitLabel(unit) + (latest?.let { statusSuffix(it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = trendArrow(latest?.trendTenthsPerMin), style = MaterialTheme.typography.headlineMedium)
            Text(text = activeSourceName ?: "no source", style = MaterialTheme.typography.bodySmall)
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
