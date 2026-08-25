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
import com.t1dm.core.model.BASELINE_MODEL_ID
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.BandCalibrationOutcome
import com.t1dm.core.model.BandFitRefusal
import com.t1dm.core.model.BackendAvailability
import com.t1dm.core.model.BackendComparison
import com.t1dm.core.model.BackendId
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

/**
 * Realized accuracy per `SPEC/invariants.md` §6.1–6.3. CG-EGA diverges from
 * `T1DMAI/realdata/metrics.py`, which passes truth and forecast transposed — a different statistic,
 * not evidence about the export. [ModelMeta.reference] is parsed but must never be shown here.
 */
@Composable
fun ModelDetailScreen(
    state: InferenceState,
    modelId: String,
    accuracy: ModelMetrics?,
    accuracyLoading: Boolean,
    onRecomputeAccuracy: () -> Unit,
    /** Null while the lattices are still being classified off-main; empty when the core had none. */
    lattices: ErrorGridLattices?,
    /** Empty on a stub core, which leaves the axes unlabelled. */
    trendBinEdges: List<Double> = emptyList(),
    cgEga: CgEga?,
    cgEgaLoading: Boolean,
    onComputeCgEga: () -> Unit,
    catalog: List<BackendAvailability>,
    requestedBackend: BackendId?,
    comparison: BackendComparison?,
    onSelectBackend: (BackendId?) -> Unit,
    onRunComparison: () -> Unit,
    probeRunning: Boolean = false,
    /** Why the last probe produced no comparison; null when it produced one. */
    probeRefusal: String? = null,
    /** §8.4. Null when never fitted. */
    bandCalibration: BandCalibration? = null,
    bandCalibrationFitting: Boolean = false,
    /** Null on a fresh open, so a reopen re-announces nothing already read. */
    bandCalibrationOutcome: BandCalibrationOutcome? = null,
    onFitBandCalibration: () -> Unit = {},
    /** Manual because a correction fitted across a sensor swap measures the gap between two
     *  sensors, and only the user knows the sensor was swapped. */
    onDropBandCalibration: () -> Unit = {},
    /** Non-null only when this drill-down is the baseline's. */
    onFitBaseline: (() -> Unit)? = null,
    baselineFitting: Boolean = false,
    /** Null on a fresh open, so a reopen re-announces nothing already read. */
    baselineFitNote: String? = null,
) {
    val isBaseline = modelId == BASELINE_MODEL_ID
    val meta = state.metaOf(modelId)
    val telemetry = state.telemetryOf(modelId)
    val running = state.runningOf(modelId)
    val haptics = rememberT1dmHaptics()
    // Hoisted above the LazyColumn: `section` is a lazy item, so state remembered inside one dies
    // when it scrolls out and the dialog would close itself.
    var showDropCalibration by remember { mutableStateOf(false) }

    // Hoisted for the same reason. Saveable so a rotation keeps it; keyed on the model and stored
    // nowhere, so a fresh open starts at the default.
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
                    // displayName() already carries the precision; only a graph model can be the
                    // fp32 authority.
                    it.backend.displayName() + when {
                        it.selected && isBaseline -> " · SELECTED"
                        it.selected -> " · SELECTED (fp32-authoritative)"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isBaseline && onFitBaseline != null) {
            section("Baseline") {
                val b = state.baselineModel
                if (b == null) {
                    Note("Not fitted — no forecast")
                } else {
                    KeyVal("fitted", "%tF %<tR".format(b.fittedAtMs))
                    KeyVal("trained on", "${b.nTrainRows} rows")
                    KeyVal(
                        "features",
                        listOfNotNull(
                            "${b.spec.nLags} BG lags",
                            "IOB".takeIf { b.spec.useIob },
                            "COB".takeIf { b.spec.useCob },
                        ).joinToString(" · "),
                    )
                    KeyVal("horizon", "${b.spec.horizonSteps * 5 / 60} h")
                    // §3.6-B — the band IS the model here, so an uncalibrated one withholds every
                    // cycle.
                    if (!b.calibrated) Note("Band uncalibrated — forecasts withheld")
                }
                // Said here so the calculator's refusal does not read as a fault.
                Note("No dose advice — the calculator needs a graph model")
                baselineFitNote?.let { Note(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !baselineFitting,
                        onClick = { haptics.perform(HapticEvent.Commit); onFitBaseline() },
                    ) { Text(if (b == null) "Fit" else "Refit") }
                    if (baselineFitting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        // Skipped for the baseline: every field is descriptor- or artifact-derived, and it has
        // neither.
        if (!isBaseline) section("Model") {
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

        // [catalog]/[comparison] are the controller's SELECTED-model state, so a non-selected
        // running model would show the wrong model's availability and probe against it.
        if (running != null) {
            section("Compute backend") {
                if (running.selected) {
                    ComputeBackendControls(
                        running = running,
                        catalog = catalog,
                        requestedBackend = requestedBackend,
                        comparison = comparison,
                        onSelectBackend = onSelectBackend,
                        onRunComparison = onRunComparison,
                        probeRunning = probeRunning,
                        probeRefusal = probeRefusal,
                    )
                } else {
                    Note(
                        "Applies to the selected model — pick it on Models",
                    )
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
                    // §6.2 — a band figure may not stand apart from its coverage and width, and the
                    // table above carries both.
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

        // Outside the `scored` gate deliberately: a figure that declines to draw must say why.
        // The options are the suite's own horizons — a copy of `ACCURACY_HORIZONS_MIN` here would go
        // stale in silence — and [clarkeGridPick] returns the record, so label, `n` and pairs cannot
        // come apart.
        val pick = clarkeGridPick(suite?.horizons.orEmpty(), gridHorizonMin)
        val gridRefusal = pick.refusal(accuracy?.minSamples ?: 0)

        // One horizon behind all three, so a 30-minute Clarke share is never read against a
        // 120-minute DTS one.
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

        // Beside Clarke, not replacing it: the DTS zone A is not Clarke's flat ±20 %, and reading
        // high is penalised harder than reading low. The edges are `dts_risk`'s, in the crate.
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

        // §6.2 — the band projection equals the truth wherever the band covered, so a rate off it
        // would sit on the diagonal by construction. Median line only.
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

        // §8.4. Skipped for the baseline: the correction recalibrates a fan a model produced, and
        // the baseline's band IS its interval — a second one would stack two estimators of the same
        // thing.
        if (!isBaseline) section("Band recalibration") {
            Note("Display only — alarms and doses read the raw band")
            if (bandCalibration == null) {
                Note("Not fitted — raw bands")
            } else {
                // The apply reads the same predicate, so a lapsed correction's rows below are a
                // record of what it once bought, not the fan on screen.
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
            // The two BandFitRefusal arms come first: neither reached the window walk, so falling
            // through to `emptyWhy` would blame data nobody gathered.
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

/** §6.2. `cov50`/`w50` share the row with the band errors: they are what keeps a band widened
 *  until it swallows every truth from reading as flawless. */
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

/** No A+B column: the panel that published the grid declined to report one. `cov50`/`w50` share
 *  the row because the band projection is `clip(truth, lo, hi)`, so a covered pair scores
 *  `ln(1) = 0` — unconditionally zone A, and `pZA` is bounded below by the coverage. */
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

/** `n` is the matrix's own count, below the horizon's wherever the 15-minute lookback did not
 *  reach. */
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

/** Minutes. 60 is long enough for the model's error to separate from persistence, short enough
 *  that the scatter is still about the forecast. A default, not a member of the option list. */
internal const val CLARKE_GRID_DEFAULT_MIN = 60

/** [options] is every horizon the suite scored, ascending, insufficient ones included. [selected]
 *  is the record itself, not an index, so caption, `n` and scatter cannot come apart; null only
 *  where the suite scored no horizon. */
internal data class ClarkeGridPick(
    val options: List<Int>,
    val selected: HorizonMetrics?,
) {
    /** Null where it may be drawn. Never substitutes another horizon. */
    fun refusal(minSamples: Int): String? =
        selected?.takeUnless { it.sufficient }?.let { "${it.horizonMin} min: n=${it.n}, need $minSamples" }
}

/** [wantedMin] is minutes, not an index, so it survives a recompute that adds or drops a horizon.
 *  A horizon the suite does not carry resolves to the nearest offered one, ties to the shorter. */
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

/** §3.6-E — the display forecast only; dose advice stays on the fp32 XNNPACK CPU authority, or a
 *  backend that passed the agreement probe. */
@Composable
private fun ComputeBackendControls(
    running: com.t1dm.core.model.RunningModel,
    catalog: List<BackendAvailability>,
    requestedBackend: BackendId?,
    comparison: BackendComparison?,
    onSelectBackend: (BackendId?) -> Unit,
    onRunComparison: () -> Unit,
    probeRunning: Boolean,
    probeRefusal: String?,
) {
    var refusal by remember { mutableStateOf<String?>(null) }
    val haptics = rememberT1dmHaptics()

    Note(
        "Display forecast only; dose advice stays on CPU unless probed",
    )

    // What is actually executing; may differ from the request after a load failure.
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Executing on ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            running.backend.displayName(), // already ends in the precision
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
    }

    BackendChoiceRow(
        title = "Auto (fp32 CPU authority)",
        subtitle = "Authoritative XNNPACK CPU path — trusted for dose advice",
        available = true,
        selected = requestedBackend == null,
        onClick = { haptics.perform(HapticEvent.SegmentTick); refusal = null; onSelectBackend(null) },
    )
    // This build ships only the XNNPACK CPU and Vulkan paths; the Play-delivered NeuroPilot NPU
    // and legacy LiteRT rows are unreachable.
    val shown = catalog.filter {
        it.backend == BackendId.EXECUTORCH_XNNPACK_FP32 ||
            it.backend == BackendId.EXECUTORCH_VULKAN_FP16 ||
            it.backend == BackendId.EXECUTORCH_VULKAN_FP32
    }
    shown.forEach { b ->
        BackendChoiceRow(
            title = b.backend.displayName(),
            subtitle = buildString {
                if (b.authoritative) append("authority · ")
                if (b.available) append("available") else append("unavailable")
                b.reason?.let { append("\n"); append(it) }
            },
            available = b.available,
            selected = requestedBackend == b.backend,
            // Stays enabled deliberately: a disabled row could not explain itself.
            onClick = {
                if (b.available) {
                    haptics.perform(HapticEvent.SegmentTick)
                    refusal = null
                    onSelectBackend(b.backend)
                } else {
                    haptics.perform(HapticEvent.Reject)
                    refusal = "${b.backend.displayName()} unavailable — ${b.reason ?: "no artifact on device"}"
                }
            },
        )
    }
    // Each tap clears the other's, so whichever is set came from the last tap.
    (refusal ?: probeRefusal)?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Note(
        "Backend vs CPU on one input — a PASS unlocks dose advice",
    )
    Button(
        enabled = !probeRunning,
        onClick = { haptics.perform(HapticEvent.Tap); refusal = null; onRunComparison() },
    ) {
        if (probeRunning) {
            // Not the default primary: in a disabled filled button a primary spinner reads as live.
            CircularProgressIndicator(
                Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(if (probeRunning) "Probing…" else "Run agreement probe")
    }
    comparison?.let { BackendComparisonCard(it) }
}

@Composable
private fun BackendChoiceRow(
    title: String,
    subtitle: String,
    available: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (selected) "●" else "○",
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackendComparisonCard(c: BackendComparison) {
    val pass = c.agreementOk
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (pass) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${c.backend.displayName()}  vs  ${c.authority.displayName()}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Mono("warm median   GPU ${"%.2f".format(c.warmMedianMsBackend)} ms   CPU ${"%.2f".format(c.warmMedianMsAuthority)} ms")
            Mono("cold          GPU ${"%.1f".format(c.coldMsBackend)} ms   CPU ${"%.1f".format(c.coldMsAuthority)} ms")
            Mono("max|Δ| head_raw    ${"%.3e".format(c.maxAbsHeadRawDelta)}")
            Mono("max|Δ| mg/dL       ${"%.4f".format(c.maxAbsDecodedMgdlDelta)}  (tol ${"%.1f".format(c.toleranceMgdl)})")
            c.loadRssGrowthKb?.let { Mono("load RSS growth    $it KB (unified memory)") }
            Text(
                if (pass) "AGREEMENT: PASS — may feed dose advice."
                else "AGREEMENT: FAIL — forecast only; dose advice stays on the CPU authority.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (pass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A `LazyListScope` extension, not a composable: the body is composed later, inside the lazy
 *  item, whereas a `@Composable` helper would be called in the builder's non-composable scope. */
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
