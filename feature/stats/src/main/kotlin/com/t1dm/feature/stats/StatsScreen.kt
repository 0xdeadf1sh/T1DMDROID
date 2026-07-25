package com.t1dm.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.EpisodeSummary
import com.t1dm.core.model.StatsComposite
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.SubBands
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.TodBucket
import com.t1dm.core.model.UnitSpace

/**
 * The Stats screen (PLAN "Phase 6 — Stats"). A pure function of the hoisted [StatsViewModel.UiState]:
 * a 7/30/90-day window switcher, the AGP percentile ribbon, the TIR/TBR/TAR stacked bar with the
 * clinical sub-bands, the metric cards (GMI/CV/SD/mean/LBGI/HBGI/MAGE), the per-channel summaries
 * (shown only where data exists), a Kovatchev/unit toggle, and a manual Recompute. Every empty state
 * says WHY in plain language. No compute here — the composable only paints.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(
    state: StatsViewModel.UiState,
    kovatchevF: (Double) -> Double,
    onSelectWindow: (StatsWindow) -> Unit,
    onSetUnitSpace: (UnitSpace) -> Unit,
    onSetTargetRange: (Int, Int) -> Unit,
    onRecompute: () -> Unit,
    onExportPdf: () -> Unit = {},
    exportStatus: String? = null,
) {
    // Wrap in a Box so the busy indicator is a weightless overlay (issue 1/6): it never inserts or
    // removes a row, so switching window/unit or recomputing keeps every element exactly in place —
    // and, unlike the old reserved top slot, it costs no dead space above the window switcher.
    val haptics = rememberT1dmHaptics()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WindowSwitcher(state.window, onSelectWindow)
            UnitSwitcher(state.unitSpace, onSetUnitSpace)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onRecompute() },
                    enabled = !state.recomputing,
                ) {
                    if (state.recomputing) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Recomputing…")
                    } else {
                        Text("Recompute")
                    }
                }
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onExportPdf() },
                    enabled = state.composite != null,
                ) {
                    Text("Export PDF")
                }
            }
            exportStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }

            val composite = state.composite
            if (composite == null) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            composite.serverReason?.let {
                InfoCard(it)
            }

            if (state.emptyReason != null) {
                InfoCard(state.emptyReason)
                return@Column
            }

            val local = composite.local
            val unit = composite.unitSpace

            // ── AGP ────────────────────────────────────────────────────────────────────────────────
            if (local.agp.isNotEmpty()) {
                SectionCard("Ambulatory glucose profile") {
                    Text(
                        "Median line, 25-75 % and 5-95 % bands across the day",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    AgpChart(
                        agp = local.agp,
                        unit = unit,
                        target = composite.targetRange,
                        kovatchevF = kovatchevF,
                        medianColor = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                InfoCard("No local BG history in this window")
            }

            // ── Time in range ────────────────────────────────────────────────────────────────────
            SectionCard("Time in range — target ${composite.targetRange.lowMgdl}–${composite.targetRange.highMgdl} mg/dL") {
                TirBar(local.subBands)
                Box(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TirLegend("Below", local.tbr, BAND_LOW)
                    TirLegend("In range", local.tir, BAND_IN)
                    TirLegend("Above", local.tar, BAND_HIGH)
                }
                Box(Modifier.height(12.dp))
                TargetRangeEditor(
                    low = composite.targetRange.lowMgdl,
                    high = composite.targetRange.highMgdl,
                    onChange = onSetTargetRange,
                )
            }

            // ── Metric cards ─────────────────────────────────────────────────────────────────────
            SectionCard("Glycemic metrics") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("Mean", fmtLevel(local.meanBg, unit, kovatchevF), unitLabel(unit))
                    Metric("GMI", fmt(local.gmi, 1), "%")
                    Metric("CV", fmt(local.cv, 1), "%")
                    Metric("SD", fmtSpread(local.sd, unit), spreadUnit(unit))
                    Metric("LBGI", fmt(local.lbgi, 1), "risk")
                    Metric("HBGI", fmt(local.hbgi, 1), "risk")
                    Metric("MAGE", fmtSpread(local.mage, unit), spreadUnit(unit))
                    Metric("GRI", fmt(griOf(local.subBands), 0), "index")
                    Metric("Samples", local.nSamples.toString(), "")
                }
            }

            // ── Variability & risk indices ─────────────────────────────────────────────────────────
            SectionCard("Variability & risk") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("MODD", fmtSpread(local.modd, unit), spreadUnit(unit))
                    Metric("CONGA-1h", fmtSpread(local.conga1, unit), spreadUnit(unit))
                    Metric("CONGA-2h", fmtSpread(local.conga2, unit), spreadUnit(unit))
                    Metric("CONGA-4h", fmtSpread(local.conga4, unit), spreadUnit(unit))
                    Metric("Day-to-day", fmtSpread(local.dtdSd, unit), spreadUnit(unit))
                    Metric("J-index", fmt(local.jIndex, 1), "index")
                    Metric("M-value", fmt(local.mValue, 1), "index")
                    Metric("ADRR", fmt(local.adrr, 1), "risk")
                    Metric("GRADE", fmt(local.grade.grade, 1), "index")
                }
                Box(Modifier.height(6.dp))
                Text(
                    "GRADE — hypo ${fmtPct(local.grade.hypo)} · eu ${fmtPct(local.grade.eu)} · " +
                        "hyper ${fmtPct(local.grade.hyper)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }

            // ── Diurnal time-in-range (four 6-hour buckets) ─────────────────────────────────────────
            if (local.tod.any { it.n > 0 }) {
                SectionCard("Time in range by time of day") {
                    local.tod.forEach { b -> DiurnalRow(b) }
                }
            }

            // ── Glucose distribution histogram ──────────────────────────────────────────────────────
            if (local.histogram.any { it.count > 0 }) {
                SectionCard("Glucose distribution") {
                    Text(
                        "Share of readings per 20 mg/dL band",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    HistogramChart(local, composite.targetRange)
                }
            }

            // ── Hypo / hyper episode tables ─────────────────────────────────────────────────────────
            SectionCard("Excursion episodes") {
                EpisodeRow("Hypo (< ${composite.targetRange.lowMgdl})", local.hypoEpisodes, isHypo = true, unit = unit, kovatchevF = kovatchevF)
                Box(Modifier.height(8.dp))
                EpisodeRow("Hyper (> ${composite.targetRange.highMgdl})", local.hyperEpisodes, isHypo = false, unit = unit, kovatchevF = kovatchevF)
                if (local.hypoEpisodes.count == 0 && local.hyperEpisodes.count == 0) {
                    Box(Modifier.height(4.dp))
                    Text(
                        "No sustained excursions (≥2 readings) outside target",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }

            // ── Per-channel (only where data exists) ──────────────────────────────────────────────
            val channels = perChannelMetrics(local)
            if (channels.isNotEmpty()) {
                SectionCard("Treatments & activity") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        channels.forEach { (label, value, u) -> Metric(label, value, u) }
                    }
                }
            }

            // ── Server parity cross-check ─────────────────────────────────────────────────────────
            composite.server?.let { s ->
                SectionCard("Server cache (cross-check)") {
                    Text(
                        "n=${s.nSamples} · mean ${fmtLevel(s.meanBg, unit, kovatchevF)} ${unitLabel(unit)} · " +
                            "GMI ${fmt(s.gmi, 1)}% · CV ${fmt(s.cv, 1)}% · SD ${fmtSpread(s.sd, unit)} ${spreadUnit(unit)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Local: mean ${fmtLevel(local.meanBg, unit, kovatchevF)} · GMI ${fmt(local.gmi, 1)}% · " +
                            "CV ${fmt(local.cv, 1)}% · SD ${fmtSpread(local.sd, unit)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(16.dp).size(18.dp), strokeWidth = 2.dp)
        }
    }
}

// ── Switchers ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun WindowSwitcher(current: StatsWindow, onSelect: (StatsWindow) -> Unit) {
    val haptics = rememberT1dmHaptics()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsWindow.entries.forEach { w ->
            FilterChip(
                selected = w == current,
                onClick = { haptics.perform(HapticEvent.SegmentTick); onSelect(w) },
                label = { Text(w.wire) },
            )
        }
    }
}

@Composable
private fun UnitSwitcher(current: UnitSpace, onSelect: (UnitSpace) -> Unit) {
    val haptics = rememberT1dmHaptics()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UnitSpace.entries.forEach { u ->
            FilterChip(
                selected = u == current,
                onClick = { haptics.perform(HapticEvent.SegmentTick); onSelect(u) },
                label = { Text(unitLabel(u)) },
            )
        }
    }
}

// ── Building blocks ─────────────────────────────────────────────────────────────────────────

/** I15 — the Stats containers use the theme's `surfaceVariant` role (defined per-theme, light and dark)
 *  instead of Material's default card grey, so they read as part of the active palette. */
@Composable
private fun statsCardColors() =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = statsCardColors()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Box(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(Modifier.fillMaxWidth(), colors = statsCardColors()) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String) {
    Card(Modifier.width(104.dp), colors = statsCardColors()) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun TirBar(bands: SubBands) {
    val segs = listOf(
        bands.veryLow to BAND_VLOW,
        bands.low to BAND_LOW,
        bands.inRange to BAND_IN,
        bands.high to BAND_HIGH,
        bands.veryHigh to BAND_VHIGH,
    )
    val anyData = segs.any { it.first > 0.0 }
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp),
    ) {
        if (!anyData) {
            // I15 — the empty-state fill tracks the theme (was a hardcoded translucent white).
            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
            return@Row
        }
        segs.forEach { (frac, color) ->
            if (frac > 0.0) {
                Box(Modifier.weight(frac.toFloat()).fillMaxHeight().background(color))
            }
        }
    }
}

@Composable
private fun TargetRangeEditor(low: Int, high: Int, onChange: (Int, Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Target", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Stepper(low) { onChange((low + it).coerceIn(40, high - 5), high) }
        Text("–", style = MaterialTheme.typography.bodyMedium)
        Stepper(high) { onChange(low, (high + it).coerceIn(low + 5, 400)) }
        Text("mg/dL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun Stepper(value: Int, onDelta: (Int) -> Unit) {
    val haptics = rememberT1dmHaptics()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedButton(
            onClick = { haptics.perform(HapticEvent.SegmentTick); onDelta(-5) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
        ) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = { haptics.perform(HapticEvent.SegmentTick); onDelta(5) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
        ) { Text("+") }
    }
}

@Composable
private fun TirLegend(label: String, frac: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(10.dp).height(10.dp).background(color))
        Text("$label ${fmtPct(frac)}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun todLabel(startMin: Int): String = when (startMin) {
    0 -> "Night 00–06"
    360 -> "Morning 06–12"
    720 -> "Afternoon 12–18"
    else -> "Evening 18–24"
}

@Composable
private fun DiurnalRow(b: TodBucket) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(todLabel(b.startMin), style = MaterialTheme.typography.bodySmall)
            Text(
                if (b.n > 0) "in range ${fmtPct(b.tir)} · n=${b.n}" else "no readings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Box(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(14.dp)) {
            if (b.n == 0) {
                Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
            } else {
                listOf(b.tbr to BAND_LOW, b.tir to BAND_IN, b.tar to BAND_HIGH).forEach { (frac, c) ->
                    if (frac > 0.0) Box(Modifier.weight(frac.toFloat()).fillMaxHeight().background(c))
                }
            }
        }
    }
}

/** A simple vertical-bar histogram; each 20 mg/dL bin's height ∝ its share, coloured by which
 *  clinical band the bin's centre falls in relative to the target range. */
@Composable
private fun HistogramChart(s: AdvancedStats, target: TargetRange) {
    val maxFrac = s.histogram.maxOfOrNull { it.frac }?.takeIf { it > 0.0 } ?: 1.0
    Row(
        Modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        s.histogram.forEach { bin ->
            val mid = (bin.lo + bin.hi) / 2.0
            val color = when {
                mid < target.lowMgdl -> BAND_LOW
                mid > target.highMgdl -> BAND_HIGH
                else -> BAND_IN
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((bin.frac / maxFrac).toFloat().coerceIn(0f, 1f).let { if (bin.count > 0) it.coerceAtLeast(0.02f) else 0f })
                        .background(color),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("40", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text("mg/dL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text("400", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun EpisodeRow(label: String, e: EpisodeSummary, isHypo: Boolean, unit: UnitSpace, kovatchevF: (Double) -> Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        // weight(1f) so the descriptive line wraps instead of starving the count column (issue 11).
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (e.count > 0) {
                Text(
                    "${e.count} · avg ${fmtDurationMin(e.meanDurationMs)} · " +
                        "${if (isHypo) "nadir" else "peak"} ${fmtLevel(e.meanExtreme, unit, kovatchevF)} " +
                        "(worst ${fmtLevel(e.worstExtreme, unit, kovatchevF)}) ${unitLabel(unit)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            } else {
                Text("none", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
        Text(
            e.count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (isHypo) BAND_VLOW else BAND_VHIGH,
        )
    }
}

private fun fmtDurationMin(ms: Double): String {
    val min = (ms / 60_000.0).toInt()
    return if (min >= 60) "${min / 60}h${(min % 60).toString().padStart(2, '0')}" else "${min}m"
}

// ── Formatting ────────────────────────────────────────────────────────────────────────────────

private fun perChannelMetrics(s: AdvancedStats): List<Triple<String, String, String>> = buildList {
    if (s.totalCarbs > 0.0) add(Triple("Carbs/day", fmt(s.meanDailyCarbs, 0), "g"))
    if (s.totalBolus > 0.0 || s.totalBasal > 0.0) add(Triple("TDD", fmt(s.tdd, 1), "U/day"))
    if (s.totalBasal > 0.0) add(Triple("Bolus:basal", fmt(s.bolusBasalRatio, 2), ""))
    s.meanSteps?.let { add(Triple("Steps", fmt(it, 0), "/5min")) }
    s.mood?.let { add(Triple("Mood", fmt(it.mean, 1), "n=${it.n}")) }
}

internal fun unitLabel(unit: UnitSpace): String = when (unit) {
    UnitSpace.MgDl -> "mg/dL"
    UnitSpace.MmolL -> "mmol/L"
    UnitSpace.Kovatchev -> "risk f(g)"
}

/** Dispersion (SD, MAGE) is only meaningful in a linear space — never Kovatchev; show mg/dL then. */
private fun spreadUnit(unit: UnitSpace): String = if (unit == UnitSpace.MmolL) "mmol/L" else "mg/dL"

private fun fmtSpread(mgdl: Double, unit: UnitSpace): String =
    if (unit == UnitSpace.MmolL) fmt(mgdl / 18.0182, 1) else fmt(mgdl, 0)

private fun fmtLevel(mgdl: Double, unit: UnitSpace, kovatchevF: (Double) -> Double): String {
    val v = convertBg(mgdl, unit, kovatchevF)
    return when (unit) {
        UnitSpace.MgDl -> fmt(v, 0)
        else -> fmt(v, 1)
    }
}

private fun fmtPct(frac: Double): String = "${fmt(frac * 100.0, 1)}%"

/** Glycemia Risk Index (Klonoff 2022): a single 0-100 composite of the hypo/hyper sub-bands (fractions
 *  here, so ×100 to percent). GRI = 3·VLow + 2.4·Low + 1.6·VHigh + 0.8·High, capped at 100. */
internal fun griOf(b: SubBands): Double =
    (3.0 * b.veryLow * 100 + 2.4 * b.low * 100 + 1.6 * b.veryHigh * 100 + 0.8 * b.high * 100).coerceIn(0.0, 100.0)

private fun fmt(v: Double, dp: Int): String = String.format("%.${dp}f", v)

// Clinical band palette (fixed; not theme-linked — clinical convention).
private val BAND_VLOW = Color(0xFFB71C1C)
private val BAND_LOW = Color(0xFFF57C00)
private val BAND_IN = Color(0xFF2E7D32)
private val BAND_HIGH = Color(0xFFF9A825)
private val BAND_VHIGH = Color(0xFFC62828)
