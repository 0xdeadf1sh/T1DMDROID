package com.t1dm.feature.models

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.BandCalibrationOutcome
import com.t1dm.core.model.BandFitRefusal
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.CgEgaRegion
import com.t1dm.core.model.ErrorGridLattices
import com.t1dm.core.model.ExcursionAccuracy
import com.t1dm.core.model.HorizonMetrics
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.ModelMetrics
import com.t1dm.core.model.PointBlock
import com.t1dm.core.model.TrendMatrix
import com.t1dm.core.model.TREND_CATEGORIES
import com.t1dm.core.model.ModelTelemetry
import com.t1dm.core.model.displayName
import kotlin.math.abs

/** Realized accuracy §6.1-6.3; CG-EGA diverges from T1DMAI's transposed metrics script. */
@Composable
fun ModelDetailScreen(
    state: InferenceState,
    modelId: String,
    accuracy: ModelMetrics?,
    accuracyLoading: Boolean,
    onRecomputeAccuracy: () -> Unit,
    /** Null while lattices are still classified off-main; empty when the core had none. */
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
    /** Manual: a swap-crossing correction measures the gap; only the user knows it swapped. */
    onDropBandCalibration: () -> Unit = {},
) {
    val meta = state.metaOf(modelId)
    val telemetry = state.telemetryOf(modelId)
    val running = state.runningOf(modelId)
    val haptics = rememberT1dmHaptics()
    // Hoisted above LazyColumn: section is a lazy item, remembered state dies when scrolled out.
    var showDropCalibration by remember { mutableStateOf(false) }

    // Hoisted for the same reason; saveable across rotation, fresh open starts at default.
    var gridHorizonMin by rememberSaveable(modelId) { mutableStateOf(CLARKE_GRID_DEFAULT_MIN) }

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


        // Keep prior rows through a recompute: collapse to "Computing…" only with no prior suite.
        val suite = accuracy?.suite
        val scored = suite?.horizons.orEmpty().filter { it.sufficient }

        section("Realized accuracy — band τ.25–.75") {
            Note("Forecast vs realized BG")
            when {
                scored.isNotEmpty() -> {
                    BandTable(scored)
                    // §6.2: a band figure can't stand apart from coverage/width; table has both.
                    ErrorByHorizonFigure(scored)
                }
                accuracyLoading -> Note("Computing…")
                else -> Note(emptyWhy(accuracy))
            }
            suite?.horizons.orEmpty().filterNot { it.sufficient }.forEach {
                Note("${it.horizonMin} min: n=${it.n}, need ${accuracy?.minSamples ?: 0}")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onRecomputeAccuracy() },
                ) { Text("Recompute") }
                if (accuracyLoading && scored.isNotEmpty()) {
                    Note("Recomputing…")
                }
            }
        }

        if (scored.isNotEmpty()) {
            // §6.2 — realized coverage against what both bands claim.
            section("Calibration") { CalibrationFigure(scored) }
            section("Clarke zones — band τ.25–.75") { ClarkeFigure(scored) }
            // Five shares individually, never an A+B: the paper's panel declines to report one.
            section("DTS zones — band τ.25–.75") {
                DtsFigure(scored)
                DtsTable(scored)
            }
            // Per horizon: trend agreement decays with horizon, and pooling would hide that.
            section("Trend risk categories — median line") {
                Note("1 no risk · 2 under · 3 over · 4/5 extreme")
                TrendCategoryFigure(scored)
                TrendTable(scored)
            }
            // §6.2 — the same block on the median line, kept a table apart from the band figures.
            section("Median line") { MedianTable(scored) }
            section("Outer band τ.05–.95 · persistence") { OuterTable(scored) }
            section("Excursions vs alarm bands") {
                Note("Hypo off the τ.25 edge, hyper off τ.75")
                ExcursionTable(scored)
            }
        }

        // Outside scored gate: a declined figure must say why; horizons are the suite's own.
        val pick = clarkeGridPick(suite?.horizons.orEmpty(), gridHorizonMin)
        val gridRefusal = pick.refusal(accuracy?.minSamples ?: 0)

        // One horizon behind all three: a 30-min Clarke share never reads against 120-min DTS.
        gridSection(
            "Clarke error grid — median line",
            "Band projection clips to the truth; its grid reads as coverage",
            pick, gridRefusal, accuracy, { gridHorizonMin = it },
        ) { h ->
            when {
                lattices == null -> Note("Computing…")
                lattices.clarke.isEmpty -> Note("Zone regions unavailable")
                h.medianLine.points.isEmpty() -> Note("No scored pairs")
                else -> ErrorGridFigure(h.horizonMin, h.medianLine.points, { it.clarke.ordinal }, lattices.clarke)
            }
        }

        // Beside Clarke: DTS zone A isn't Clarke's flat ±20%, reading high scores worse.
        gridSection(
            "DTS error grid — median line",
            "Klonoff 2024 · reading high scores worse than reading low",
            pick, gridRefusal, accuracy, { gridHorizonMin = it },
        ) { h ->
            when {
                lattices == null -> Note("Computing…")
                lattices.dts.isEmpty -> Note("Zone regions unavailable")
                h.medianLine.points.isEmpty() -> Note("No scored pairs")
                else -> ErrorGridFigure(h.horizonMin, h.medianLine.points, { it.dts.ordinal }, lattices.dts)
            }
        }

        // §6.2: band projection equals truth where covered, so a rate off it sits on the diagonal.
        gridSection(
            "Trend accuracy — median line",
            "Rate over 15 min vs realized",
            pick, gridRefusal, accuracy, { gridHorizonMin = it },
        ) { h ->
            if (h.trend.isEmpty) Note("No scored pairs") else TrendMatrixFigure(h.trend, trendBinLabels(trendBinEdges))
        }

        // §6.3 — whole window; the costly pass, so only on request.
        section("CG-EGA") {
            when {
                cgEga != null -> {
                    CgEgaFigure(cgEga)
                    CgEgaTable(cgEga)
                }
                cgEgaLoading -> Note("Computing…")
                scored.isEmpty() -> Note("Needs scored windows")
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
                // Apply reads the same predicate; a lapsed correction's rows record what it bought.
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
            // BandFitRefusal arms first: neither reached the walk, blames nobody's data.
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

// Column order follows `T1DMAI/realdata/report.py::_suite_table`.

private fun col(header: String, weight: Float) =
    com.t1dm.core.design.TableColumn(header, weight, numeric = true)

private const val WIDE = 980

/** §6.2: cov50/w50 share the row with band errors, so a swallowed band can't read flawless. */
@Composable
private fun BandTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.7f),
            col("RMSE pt", 1f), col("RMSE wm", 1f), col("MAE pt", 1f), col("MAE wm", 1f),
            col("MARD %", 1f), col("A %", 0.9f), col("A+B %", 1f), col("E %", 0.9f),
            col("cov50 %", 1f), col("w50", 0.9f), col("skill", 0.9f), col("n", 0.7f),
        ),
        rows = hs.map { h ->
            pointCells(h, h.band) + listOf(
                pct(h.bandCov50), f1(h.bandWidth50), skill(h.band), h.n.toString(),
            )
        },
        minWidth = WIDE,
    )
}

/** §6.2 — a different quantity on one forecast, so a separate table. A line has no coverage. */
@Composable
private fun MedianTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.7f),
            col("RMSE pt", 1f), col("RMSE wm", 1f), col("MAE pt", 1f), col("MAE wm", 1f),
            col("MARD %", 1f), col("A %", 0.9f), col("A+B %", 1f), col("E %", 0.9f),
            col("skill", 0.9f), col("n", 0.7f),
        ),
        rows = hs.map { h ->
            pointCells(h, h.medianLine) + listOf(skill(h.medianLine), h.n.toString())
        },
        minWidth = WIDE,
    )
}

/** The persistence baseline both bases' skill is measured against. */
@Composable
private fun OuterTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.8f),
            col("cov90 %", 1f), col("w90", 1f), col("persist pt", 1.2f), col("persist wm", 1.2f),
        ),
        rows = hs.map { h ->
            listOf(
                "${h.horizonMin}m", pct(h.bandCov90), f1(h.bandWidth90),
                f1(h.rmsePersistPoint), f1(h.rmsePersistWinmean),
            )
        },
    )
}

/** §6.1. The denominators sit beside the ratios: 1.00 over one crossing is not 1.00 over forty. */
@Composable
private fun ExcursionTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.7f),
            col("hypo rec", 1.1f), col("hypo prec", 1.2f), col("hypo t/p", 1.1f),
            col("hyper rec", 1.2f), col("hyper prec", 1.3f), col("hyper t/p", 1.2f),
        ),
        rows = hs.map { h ->
            listOf("${h.horizonMin}m") + excursionCells(h.hypo) + excursionCells(h.hyper)
        },
        minWidth = 620,
    )
}

/** No A+B: source panel declined one; cov50/w50 share row since clip(truth) scores zone A. */
@Composable
private fun DtsTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.8f),
            col("A %", 1f), col("B %", 1f), col("C %", 1f), col("D %", 1f), col("E %", 1f),
            col("|risk|", 1.1f), col("cov50 %", 1.1f), col("w50", 0.9f), col("n", 0.8f),
        ),
        rows = hs.map { h ->
            val b = h.band
            listOf(
                "${h.horizonMin}m",
                f1(b.dtsA), f1(b.dtsB), f1(b.dtsC), f1(b.dtsD), f1(b.dtsE),
                f3(b.dtsMeanAbsRisk), pct(h.bandCov50), f1(h.bandWidth50), h.n.toString(),
            )
        },
        minWidth = 760,
    )
}

/** n is the matrix's own count, below the horizon's where the 15-min lookback didn't reach. */
@Composable
private fun TrendTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.8f),
        ) + List(TREND_CATEGORIES) { col("${it + 1} %", 1f) } + listOf(col("n", 0.8f)),
        rows = hs.map { h -> listOf("${h.horizonMin}m") + trendCells(h.trend) },
        minWidth = 560,
    )
}

private fun trendCells(m: TrendMatrix): List<String> =
    List(TREND_CATEGORIES) { f1(m.categoryPct.getOrNull(it)) } + m.n.toString()

/** §6.3 — the whole window, so no horizon column and no horizon in any label. */
@Composable
private fun CgEgaTable(cg: CgEga) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("region", 1f),
            col("AP %", 1f), col("BE %", 1f), col("EP %", 1f), col("n", 0.8f),
        ),
        rows = listOf(
            cgEgaCells("hypo", cg.hypo),
            cgEgaCells("eu", cg.eu),
            cgEgaCells("hyper", cg.hyper),
        ),
    )
}

private fun pointCells(h: HorizonMetrics, b: PointBlock): List<String> = listOf(
    "${h.horizonMin}m",
    f1(b.rmsePoint), f1(b.rmseWinmean), f1(b.maePoint), f1(b.maeWinmean),
    f1(b.mard), f1(b.clarkeA), f1(b.clarkeAb), "%.2f".format(b.clarkeE),
)

private fun excursionCells(e: ExcursionAccuracy): List<String> =
    listOf(f2(e.recall), f2(e.precision), "${e.nTrue}/${e.nPred}")

private fun cgEgaCells(name: String, r: CgEgaRegion): List<String> =
    listOf(name, f1(r.apPct), f1(r.bePct), f1(r.epPct), r.n.toString())

private fun f1(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.1f".format(v)

private fun f2(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.2f".format(v)

private fun f3(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.3f".format(v)

private fun pct(v: Double): String = if (!v.isFinite()) "—" else "%.1f".format(v * 100)

private fun skill(b: PointBlock): String = f2(b.skillPoint)

/** Minutes; 60 separates model error from persistence, keeps scatter about the forecast. */
internal const val CLARKE_GRID_DEFAULT_MIN = 60

/** options: every scored horizon ascending; selected is the record, caption/n/scatter agree. */
internal data class ClarkeGridPick(
    val options: List<Int>,
    val selected: HorizonMetrics?,
) {
    /** Null where it may be drawn. Never substitutes another horizon. */
    fun refusal(minSamples: Int): String? =
        selected?.takeUnless { it.sufficient }?.let { "${it.horizonMin} min: n=${it.n}, need $minSamples" }
}

/** wantedMin is minutes not an index; missing horizons resolve to nearest, ties go shorter. */
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

private fun emptyWhy(m: ModelMetrics?): String {
    if (m == null) return "Insufficient history — nothing scored yet"
    val built = m.nMatured - m.nIncomplete
    return when {
        // Ahead of the history arms: after a sensor change there IS history.
        m.nForeignSource > 0 && m.nMatured == 0 ->
            "${m.nForeignSource} forecasts from the previous sensor — refit after ~17 h"
        m.nMatured == 0 -> "Insufficient history — no matured forecast yet"
        built == 0 -> "CGM gaps — ${m.nIncomplete} of ${m.nMatured} forecasts dropped"
        m.suite.nWindows == 0 -> "Fan not scoreable — $built forecasts rejected"
        else -> "Insufficient history — ${m.suite.nWindows} scored windows"
    }
}

/** LazyListScope extension, not composable: body composes later inside the lazy item. */
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

private fun fmtDuration(ms: Double): String = when {
    ms >= 3_600_000 -> "%.2f h".format(ms / 3_600_000)
    ms >= 60_000 -> "%.1f min".format(ms / 60_000)
    ms >= 1_000 -> "%.2f s".format(ms / 1_000)
    else -> "${ms.toInt()} ms"
}
