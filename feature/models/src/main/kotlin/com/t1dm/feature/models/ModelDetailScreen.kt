package com.t1dm.feature.models

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.BacktestRefusal
import com.t1dm.core.model.BacktestSensor
import com.t1dm.core.model.BacktestStop
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.BandCalibrationOutcome
import com.t1dm.core.model.BandFitRefusal
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.ErrorGridLattices
import com.t1dm.core.model.HorizonMetrics
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.ModelBacktest
import com.t1dm.core.model.ModelMetrics
import com.t1dm.core.model.ModelTelemetry
import com.t1dm.core.model.displayName
import kotlin.math.abs

/** Realized accuracy per §6.1-6.3. */
@Composable
fun ModelDetailScreen(
    state: InferenceState,
    modelId: String,
    accuracy: ModelMetrics?,
    accuracyLoading: Boolean,
    onRecomputeAccuracy: () -> Unit,
    /** Null while lattices still classified off-main; empty when the core had none. */
    lattices: ErrorGridLattices?,
    /** Empty on a stub core, which leaves the axes unlabelled. */
    trendBinEdges: List<Double> = emptyList(),
    cgEga: CgEga?,
    cgEgaLoading: Boolean,
    onComputeCgEga: () -> Unit,
    /** §8.4. Null when never fitted. */
    bandCalibration: BandCalibration? = null,
    bandCalibrationFitting: Boolean = false,
    /** Null on a fresh open, so a reopen re-announces nothing already read. */
    bandCalibrationOutcome: BandCalibrationOutcome? = null,
    onFitBandCalibration: () -> Unit = {},
    /** Manual: a correction fitted across a sensor swap measures the gap between two sensors. */
    onDropBandCalibration: () -> Unit = {},
    /** Null until one is run for this model in this process. */
    backtest: ModelBacktest? = null,
    backtestSensors: List<BacktestSensor> = emptyList(),
    onBacktest: (days: Int, sourceIds: List<String>) -> Unit = { _, _ -> },
    onCancelBacktest: () -> Unit = {},
    onExportBacktest: () -> Unit = {},
    backtestExportStatus: String? = null,
) {
    val meta = state.metaOf(modelId)
    val telemetry = state.telemetryOf(modelId)
    val running = state.runningOf(modelId)
    val haptics = rememberT1dmHaptics()
    // Hoisted above LazyColumn: state in a lazy item dies on scroll-out, closing the dialog.
    var showDropCalibration by remember { mutableStateOf(false) }

    // Hoisted for the same reason; saveable so rotation keeps it, keyed on model, stored nowhere.
    var gridHorizonMin by rememberSaveable(modelId) { mutableStateOf(CLARKE_GRID_DEFAULT_MIN) }

    val backtestDone = backtest as? ModelBacktest.Done
    // Keyed on the run: a finished backtest opens on its own figures.
    var viewBacktest by rememberSaveable(modelId, backtestDone?.finishedAtMs) { mutableStateOf(true) }
    val showingBacktest = backtestDone != null && viewBacktest
    val shown = if (backtestDone != null && viewBacktest) backtestDone.metrics else accuracy
    val shownLoading = !showingBacktest && accuracyLoading

    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp).fadingEdges(listState),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(modelId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            running?.let {
                Text(
                    // displayName() carries precision; only a graph model is fp32 authority.
                    it.backend.displayName() + if (it.selected) " · SELECTED (fp32-authoritative)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        section("Model") {
            if (meta == null) {
                Note("No descriptor metadata")
            } else {
                KeyVal("parameters", meta.paramCount?.let { "${fmtParams(it)}  (${"%,d".format(it)})" } ?: "n/a")
                KeyVal("artifact size", meta.diskBytes?.let { fmtBytes(it) } ?: "n/a (no .pte on disk)")
                KeyVal("d_model / layers / heads", listOfNotNull(meta.dModel, meta.nLayers, meta.nHeads).joinToStringOrNa())
                KeyVal("patch dim", meta.patchDim?.toString() ?: "n/a")
                KeyVal("context patches", rangeOrNa(meta.minContextPatches, meta.maxContextPatches))
                KeyVal("forecast horizon", meta.predictionHorizonHours?.let { "$it h" } ?: "n/a")
                KeyVal("arch / ExecuTorch", "${meta.archVersion ?: "?"} / ${meta.executorchVersion ?: "?"}")
            }
        }

        section("Inference telemetry (this install)") {
            if (telemetry == null || telemetry.predictions == 0L) {
                Note("No forecasts recorded yet")
            } else {
                KeyVal("predictions made", "%,d".format(telemetry.predictions))
                KeyVal("avg exec time", fmtMs(telemetry.avgInferenceMs))
                KeyVal("total inference time", fmtDuration(telemetry.totalInferenceMs))
                state.latencyOf(modelId)?.let {
                    KeyVal("recent p50 / p95", "${fmtMs(it.p50Ms)} / ${fmtMs(it.p95Ms)}")
                }
            }
        }


        section("Backtest") {
            BacktestControls(backtest, backtestSensors, onBacktest, onCancelBacktest, onExportBacktest, backtestExportStatus)
        }

        // Keep prior rows through a recompute: collapse to "Computing…" only with no prior suite.
        val suite = shown?.suite
        val scored = suite?.horizons.orEmpty().filter { it.sufficient }

        section("Realized accuracy — band τ.25–.75") {
            if (backtestDone != null) AccuracySourcePicker(showingBacktest) { viewBacktest = it }
            Note(if (showingBacktest) "Replayed forecast vs realized BG" else "Forecast vs realized BG")
            when {
                scored.isNotEmpty() -> {
                    Table(bandTable(scored))
                    // §6.2: band figure may not stand apart from coverage/width; table has both.
                    ErrorByHorizonFigure(scored)
                }
                shownLoading -> Note("Computing…")
                else -> Note(emptyWhy(shown))
            }
            suite?.horizons.orEmpty().filterNot { it.sufficient }.forEach {
                Note("${it.horizonMin} min: n=${it.n}, need ${shown?.minSamples ?: 0}")
            }
            if (!showingBacktest) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { haptics.perform(HapticEvent.Tap); onRecomputeAccuracy() },
                    ) { Text("Recompute") }
                    if (accuracyLoading && scored.isNotEmpty()) {
                        Note("Recomputing…")
                    }
                }
            }
        }

        if (scored.isNotEmpty()) {
            // §6.2: realized coverage against what both bands claim.
            section("Calibration") { CalibrationFigure(scored) }
            section("Clarke zones — band τ.25–.75") { ClarkeFigure(scored) }
            // Five shares individually, never an A+B: the paper's panel declines to report one.
            section("DTS zones — band τ.25–.75") {
                DtsFigure(scored)
                Table(dtsTable(scored))
            }
            // Per horizon: trend agreement decays, pooling would hide that.
            section("Trend risk categories — median line") {
                Note("1 no risk · 2 under · 3 over · 4/5 extreme")
                TrendCategoryFigure(scored)
                Table(trendTable(scored))
            }
            // §6.2: the same block on the median line, kept a table apart from the band figures.
            section("Median line") { Table(medianTable(scored)) }
            section("Outer band τ.05–.95 · persistence") { Table(outerTable(scored)) }
            section("Excursions vs alarm bands") {
                Note("Hypo off the τ.25 edge, hyper off τ.75")
                Table(excursionTable(scored))
            }
        }

        // Outside the scored gate deliberately: a figure that declines to draw must say why.
        val pick = clarkeGridPick(suite?.horizons.orEmpty(), gridHorizonMin)
        val gridRefusal = pick.refusal(shown?.minSamples ?: 0)

        // One horizon for all three: a 30-min Clarke share never reads against a 120-min DTS one.
        gridSection(
            "Clarke error grid — median line",
            "Band projection clips to the truth; its grid reads as coverage",
            pick, gridRefusal, shown, { gridHorizonMin = it },
        ) { h ->
            when {
                lattices == null -> Note("Computing…")
                lattices.clarke.isEmpty -> Note("Zone regions unavailable")
                h.medianLine.points.isEmpty() -> Note("No scored pairs")
                else -> ErrorGridFigure(h.horizonMin, h.medianLine.points, { it.clarke.ordinal }, lattices.clarke)
            }
        }

        // Beside Clarke, not replacing: DTS zone A isn't Clarke's flat ±20%, edges are dts_risk's.
        gridSection(
            "DTS error grid — median line",
            "Klonoff 2024 · reading high scores worse than reading low",
            pick, gridRefusal, shown, { gridHorizonMin = it },
        ) { h ->
            when {
                lattices == null -> Note("Computing…")
                lattices.dts.isEmpty -> Note("Zone regions unavailable")
                h.medianLine.points.isEmpty() -> Note("No scored pairs")
                else -> ErrorGridFigure(h.horizonMin, h.medianLine.points, { it.dts.ordinal }, lattices.dts)
            }
        }

        // §6.2: band projection equals truth wherever covered; a rate off it sits on the diagonal.
        gridSection(
            "Trend accuracy — median line",
            "Rate over 15 min vs realized",
            pick, gridRefusal, shown, { gridHorizonMin = it },
        ) { h ->
            if (h.trend.isEmpty) Note("No scored pairs") else TrendMatrixFigure(h.trend, trendBinLabels(trendBinEdges))
        }

        // §6.3: whole window, the costly pass, so only on request.
        section("CG-EGA") {
            // A backtest walks it in the same pass; the live one only on request.
            val shownCgEga = if (showingBacktest) suite?.cgega else cgEga
            when {
                shownCgEga != null -> {
                    CgEgaFigure(shownCgEga)
                    Table(cgEgaTable(shownCgEga))
                }
                !showingBacktest && cgEgaLoading -> Note("Computing…")
                scored.isEmpty() -> Note("Needs scored windows")
                showingBacktest -> Note("Nothing scoreable")
                else -> TextButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onComputeCgEga() },
                ) { Text("Compute") }
            }
        }

        section("Band recalibration") {
            Note("Display only — alarms and doses read the raw band")
            if (bandCalibration == null) {
                Note("Not fitted — raw bands")
            } else {
                // The apply reads the same predicate; a lapsed correction's rows are a past record.
                if (bandCalibration.expiredAt(System.currentTimeMillis())) {
                    Note("Expired after ${bandCalibration.windowDays} d — raw bands")
                }
                KeyVal("fitted", "%tF %<tR".format(bandCalibration.fittedAtMs))
                KeyVal("fit / held out", "${bandCalibration.nCal} / ${bandCalibration.nEval}")
                KeyVal(
                    "cov τ.05–.95",
                    "${f2(bandCalibration.cov90Raw)} → ${f2(bandCalibration.cov90Cal)}",
                )
                KeyVal(
                    "band width",
                    "${f1(bandCalibration.meanWidth90Raw)} → ${f1(bandCalibration.meanWidth90Cal)} mg/dL",
                )
                KeyVal("max shift", "${f1(bandCalibration.maxAbsDeltaMgdl)} mg/dL")
            }
            // The two BandFitRefusal arms come first: neither reached the window walk.
            bandCalibrationOutcome?.let { o ->
                val fit = o.fit
                when {
                    o.refusal == BandFitRefusal.BUSY -> Note("Fit already running")
                    o.refusal == BandFitRefusal.HORIZON_UNKNOWN -> Note("No forecast yet — horizon unknown")
                    fit == null -> Note(emptyWhy(accuracy))
                    !fit.sufficient -> Note("${fit.nCal} fit windows, need ${fit.minCalWindows}")
                    else -> Unit // The rows above already say it.
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !bandCalibrationFitting,
                    onClick = { haptics.perform(HapticEvent.Tap); onFitBandCalibration() },
                ) { Text("Recalibrate") }
                // Confirmed: a refit costs half a day of matured windows to earn back.
                if (bandCalibration != null) {
                    TextButton(
                        enabled = !bandCalibrationFitting,
                        onClick = { haptics.perform(HapticEvent.Warn); showDropCalibration = true },
                    ) { Text("Drop") }
                }
                if (bandCalibrationFitting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
    }

    if (showDropCalibration) {
        AlertDialog(
            onDismissRequest = { haptics.perform(HapticEvent.Reject); showDropCalibration = false },
            title = { Text("Drop the band correction?") },
            text = { Text("Raw bands until a refit. Needs ~17 h of matured forecasts.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticEvent.Commit)
                        onDropBandCalibration()
                        showDropCalibration = false
                    },
                ) { Text("Drop") }
            },
            dismissButton = {
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Reject); showDropCalibration = false },
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Table(t: MetricTable) {
    com.t1dm.core.design.DataTable(t.columns, t.rows, minWidth = t.minWidth)
}

/** Minutes; 60 separates model error from persistence but keeps the scatter about the forecast. */
internal const val CLARKE_GRID_DEFAULT_MIN = 60

/** options: every scored horizon, ascending; selected is the record itself, not an index. */
internal data class ClarkeGridPick(
    val options: List<Int>,
    val selected: HorizonMetrics?,
) {
    /** Null where it may be drawn. Never substitutes another horizon. */
    fun refusal(minSamples: Int): String? =
        selected?.takeUnless { it.sufficient }?.let { "${it.horizonMin} min: n=${it.n}, need $minSamples" }
}

/** wantedMin is minutes, not an index, so it survives a recompute that adds/drops a horizon. */
internal fun clarkeGridPick(horizons: List<HorizonMetrics>, wantedMin: Int): ClarkeGridPick {
    val ordered = horizons.sortedBy { it.horizonMin }
    val chosen = ordered.firstOrNull { it.horizonMin == wantedMin }
        ?: ordered.minByOrNull { abs(it.horizonMin - wantedMin) }
    return ClarkeGridPick(ordered.map { it.horizonMin }, chosen)
}

/** Material3 1.3.1 performs no haptic of its own, so the tick is fired here. */
@Composable
private fun ClarkeHorizonPicker(options: List<Int>, selected: Int?, onSelect: (Int) -> Unit) {
    val haptics = rememberT1dmHaptics()
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        options.forEachIndexed { i, h ->
            SegmentedButton(
                selected = h == selected,
                onClick = { haptics.perform(HapticEvent.SegmentTick); onSelect(h) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text("${h}m") }
        }
    }
}

private val BACKTEST_DAYS = listOf(7, 14, 30)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BacktestControls(
    backtest: ModelBacktest?,
    sensors: List<BacktestSensor>,
    onRun: (days: Int, sourceIds: List<String>) -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    exportStatus: String?,
) {
    val haptics = rememberT1dmHaptics()
    val running = backtest as? ModelBacktest.Running
    var days by rememberSaveable { mutableStateOf(backtest?.days?.takeIf { it in BACKTEST_DAYS } ?: 7) }
    // Null until a chip is touched: the authoritative sensor alone.
    var picked by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val nowMs = remember(sensors) { System.currentTimeMillis() }
    val inWindow = sensors.filter { it.newestMs >= nowMs - days * 86_400_000L }
    val chosen = (picked ?: inWindow.filter { it.authoritative }.map { it.id }).filter { id -> inWindow.any { it.id == id } }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        BACKTEST_DAYS.forEachIndexed { i, d ->
            SegmentedButton(
                selected = d == days,
                enabled = running == null,
                onClick = { haptics.perform(HapticEvent.SegmentTick); days = d },
                shape = SegmentedButtonDefaults.itemShape(i, BACKTEST_DAYS.size),
            ) { Text("$d d") }
        }
    }
    if (inWindow.size > 1 || (inWindow.isNotEmpty() && chosen.isEmpty())) {
        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            inWindow.forEach { s ->
                val on = s.id in chosen
                FilterChip(
                    selected = on,
                    enabled = running == null,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); picked = if (on) chosen - s.id else chosen + s.id },
                    label = { Text(s.label) },
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (running == null) {
            TextButton(
                onClick = { haptics.perform(HapticEvent.Tap); onRun(days, chosen) },
                enabled = chosen.isNotEmpty(),
            ) { Text("Run") }
            if (inWindow.isEmpty()) Note("No readings in $days d")
        } else {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); onCancel() }) { Text("Cancel") }
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            if (running.total > 0) {
                Spacer(Modifier.width(8.dp))
                Mono("%,d / %,d".format(running.done, running.total))
            }
        }
    }
    when (backtest) {
        is ModelBacktest.Done -> {
            Note(
                "${backtest.days} d · %,d / %,d forecasts · ".format(backtest.nForecasts, backtest.nOrigins) +
                    fmtDuration(backtest.elapsedMs.toDouble()),
            )
            if (backtest.forecastsBySource.size > 1) {
                // Never the raw id: it can carry the serial the name-privacy setting hides.
                Note(
                    backtest.forecastsBySource.entries.joinToString(" · ") { (id, n) ->
                        "${sensors.firstOrNull { it.id == id }?.label ?: "CGM"} %,d".format(n)
                    },
                )
            }
            if (backtest.adapterAttached) Note("Adapter attached — may be in-sample")
            when (backtest.stopped) {
                BacktestStop.TOO_HOT -> Note("Stopped — too hot")
                BacktestStop.MODEL_CHANGED -> Note("Stopped — model reloaded")
                null -> Unit
            }
            TextButton(onClick = { haptics.perform(HapticEvent.Tap); onExport() }) { Text("Export PDF") }
            exportStatus?.let { Note(it) }
        }
        is ModelBacktest.Refused -> Note(
            when (backtest.refusal) {
                BacktestRefusal.BUSY -> "Another backtest running"
                BacktestRefusal.NOT_LOADED -> "Model not loaded"
                BacktestRefusal.NO_ARTIFACT -> "No artifact"
                BacktestRefusal.NO_SENSOR -> "No sensor"
                BacktestRefusal.NO_HISTORY -> "No readings in ${backtest.days} d"
                BacktestRefusal.FAILED -> "Failed"
            },
        )
        is ModelBacktest.Running, null -> Unit
    }
}

@Composable
private fun AccuracySourcePicker(backtest: Boolean, onSelect: (backtest: Boolean) -> Unit) {
    val haptics = rememberT1dmHaptics()
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        listOf(false to "Live", true to "Backtest").forEachIndexed { i, (isBacktest, label) ->
            SegmentedButton(
                selected = backtest == isBacktest,
                onClick = { haptics.perform(HapticEvent.SegmentTick); onSelect(isBacktest) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
            ) { Text(label) }
        }
    }
}

internal fun emptyWhy(m: ModelMetrics?): String {
    if (m == null) return "Insufficient history — nothing scored yet"
    val built = m.nMatured - m.nIncomplete
    return when {
        // Ahead of the history arms: after a sensor change there IS history.
        m.nForeignSource > 0 && m.nMatured == 0 ->
            "${m.nForeignSource} forecasts from other sensors — refit after ~17 h"
        m.nMatured == 0 -> "Insufficient history — no matured forecast yet"
        built == 0 -> "CGM gaps — ${m.nIncomplete} of ${m.nMatured} forecasts dropped"
        m.suite.nWindows == 0 -> "Fan not scoreable — $built forecasts rejected"
        else -> "Insufficient history — ${m.suite.nWindows} scored windows"
    }
}

/** A LazyListScope extension, not composable: body composes later, inside the lazy item. */
private inline fun androidx.compose.foundation.lazy.LazyListScope.gridSection(
    title: String,
    note: String,
    pick: ClarkeGridPick,
    refusal: String?,
    accuracy: ModelMetrics?,
    crossinline onSelect: (Int) -> Unit,
    crossinline body: @Composable (HorizonMetrics) -> Unit,
) {
    section(title) {
        Note(note)
        if (pick.options.size > 1) {
            ClarkeHorizonPicker(
                options = pick.options,
                selected = pick.selected?.horizonMin,
                onSelect = { onSelect(it) },
            )
        }
        val h = pick.selected
        when {
            h == null -> Note(emptyWhy(accuracy))
            refusal != null -> Note(refusal)
            else -> body(h)
        }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    crossinline body: @Composable () -> Unit,
) {
    item {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { body() }
    }
}

@Composable
private fun KeyVal(k: String, v: String) {
    com.t1dm.core.design.KeyValueRow(k, v, numeric = false)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Mono(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

private fun List<Int>.joinToStringOrNa(): String = if (isEmpty()) "n/a" else joinToString(" / ")

private fun rangeOrNa(lo: Int?, hi: Int?): String =
    if (lo == null && hi == null) "n/a" else "${lo ?: "?"}–${hi ?: "?"}"

private fun fmtMs(ms: Double): String = if (ms >= 100) "${ms.toInt()} ms" else "%.1f ms".format(ms)

internal fun fmtDuration(ms: Double): String = when {
    ms >= 3_600_000 -> "%.2f h".format(ms / 3_600_000)
    ms >= 60_000 -> "%.1f min".format(ms / 60_000)
    ms >= 1_000 -> "%.2f s".format(ms / 1_000)
    else -> "${ms.toInt()} ms"
}
