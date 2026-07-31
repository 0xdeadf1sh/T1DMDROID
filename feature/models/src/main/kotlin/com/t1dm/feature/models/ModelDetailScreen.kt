package com.t1dm.feature.models

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.BackendAvailability
import com.t1dm.core.model.BackendComparison
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.CgEgaRegion
import com.t1dm.core.model.ExcursionAccuracy
import com.t1dm.core.model.HorizonMetrics
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.ModelMetrics
import com.t1dm.core.model.PointBlock
import com.t1dm.core.model.ModelTelemetry
import com.t1dm.core.model.displayName

/**
 * The per-model PERFORMANCE drill-down (Phase 7C — item 24). Three blocks:
 *
 *  1. META — parameter count, on-disk size, arch dims + geometry (item 7, size reasoning).
 *  2. TELEMETRY — this install's cumulative avg inference EXEC time, #predictions, and TOTAL time
 *     spent in the backend forward (the [ModelTelemetry] the CycleRunner updates + persists).
 *  3. ON-DEVICE REALIZED ACCURACY — the full metric suite of `SPEC/invariants.md` §6.1-6.3,
 *     computed by the golden-gated Rust core over stored `prediction` rows vs the realized
 *     `cgm_reading` trajectory. It reproduces `T1DMAI/realdata/metrics.py::compute_suite`, so these
 *     figures are directly comparable to that project's validation table — every block except
 *     CG-EGA, which is not: that project passes the truth and the forecast to `cg_ega_counts`
 *     transposed, so the %AP/%BE/%EP it publishes assigns the glycaemic region by the forecast and
 *     is a different statistic from the one shown here (see the divergence note above the CG-EGA
 *     block in `t1dm-core::accuracy`). A gap between the two is not evidence about the export.
 *
 *     Two identity rules govern how it may be shown, both from the spec:
 *
 *       - The BASIS is part of every figure (§6.2). The band projection is the headline and the
 *         median line is a separate table beneath it — never one column carrying both. And because
 *         a wider band can only lower the error, no band figure appears without `band_cov50` and
 *         `band_width50` in the same row: a band widened until it swallows every truth scores a
 *         flawless zero, and those two are the only things that expose it.
 *       - CG-EGA is a WHOLE-WINDOW statistic (§6.3), so it carries no horizon label. It is also the
 *         costly pass, and is computed only on a tap.
 *
 *     A horizon with too little matured history says so PLAINLY (never a noisy stat), and an empty
 *     panel states which of the two reasons it is: nothing matured yet, or matured forecasts whose
 *     realized trajectory had a CGM gap.
 *
 *     Each table is paired with a Canvas figure (AccuracyFigures.kt) over the SAME rows — the table
 *     is the exact number, the figure the shape: the error against the persistence baseline it is
 *     scored against, the coverage against the target it claims, and the two zone partitions, which
 *     are read as proportions rather than as four decimals apiece. A figure only ever receives
 *     horizons that PASSED `sufficient`, so none of them draws an axis over a horizon the tables
 *     have just declined to score.
 *
 * Everything is advisory: the accuracy of a FORECAST, never a dosing claim.
 *
 * **The descriptor's reference metrics are not shown, and must not be.** [ModelMeta.reference] is
 * still parsed — the `model_card` block is part of the descriptor — so it remains one field access
 * away, and it does not belong on this screen: it is a held-out validation table from another
 * dataset, and beneath the realized suite it reads as a second opinion on THIS patient's forecasts,
 * which is the one thing it cannot be. The realized numbers are the only accuracy claim made here.
 */
@Composable
fun ModelDetailScreen(
    state: InferenceState,
    modelId: String,
    accuracy: ModelMetrics?,
    accuracyLoading: Boolean,
    onRecomputeAccuracy: () -> Unit,
    cgEga: CgEga?,
    cgEgaLoading: Boolean,
    onComputeCgEga: () -> Unit,
    catalog: List<BackendAvailability>,
    requestedBackend: BackendId?,
    comparison: BackendComparison?,
    onSelectBackend: (BackendId?) -> Unit,
    onRunComparison: () -> Unit,
) {
    val meta = state.metaOf(modelId)
    val telemetry = state.telemetryOf(modelId)
    val running = state.runningOf(modelId)
    val haptics = rememberT1dmHaptics()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(modelId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            running?.let {
                Text(
                    "${it.backend.displayName()} · ${it.precision.name}" + (if (it.selected) " · SELECTED (fp32-authoritative)" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 1. Meta ──
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

        // ── 2. Cumulative telemetry ──
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

        // ── Compute backend (per-model forecast switcher; issue 20 STEP 4) ──
        // Only meaningful while the model is in the running set (a loaded backend to switch), AND only
        // for the SELECTED model: the switcher's [catalog]/[comparison] are the controller's single
        // selected-model-bound state (backendCatalog / backendComparison), so rendering them for a
        // non-selected running model would show the SELECTED model's availability + run the agreement
        // probe against it — a mismatch. Selecting a model rebuilds that catalog for it (selectModel).
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
                    )
                } else {
                    Note(
                        "Applies to the selected model — pick it on Models",
                    )
                }
            }
        }

        // ── 3. On-device realized accuracy ──
        // Keep the prior rows on screen through a recompute (issue 6): only collapse to the one-line
        // "Computing…" when there is NO prior suite; otherwise the tables stay put and a subtle
        // inline hint (in the button row, so section height is unchanged) marks the refresh.
        val suite = accuracy?.suite
        val scored = suite?.horizons.orEmpty().filter { it.sufficient }

        section("Realized accuracy — band τ.25–.75") {
            Note("Forecast vs realized BG (advisory — not a dosing claim)")
            when {
                scored.isNotEmpty() -> {
                    BandTable(scored)
                    // Kept in this section deliberately: §6.2 forbids a band figure standing apart
                    // from its coverage and width, and the table above carries both.
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
            // Both bands' realized coverage against what they claim (§6.2) — the figure that says
            // whether the fan is honest, and the only reading under which a flawless error column
            // can still be a bad forecast.
            section("Calibration") { CalibrationFigure(scored) }
            // The basis is part of a figure's identity (§6.2) and this one is off the band, like
            // every other level metric — so the header carries it, exactly as the band table's does.
            section("Clarke zones — band τ.25–.75") { ClarkeFigure(scored) }
            // §6.2 — the same block on the median line, kept a table apart from the band figures.
            section("Median line") { MedianTable(scored) }
            section("Outer band τ.05–.95 · persistence") { OuterTable(scored) }
            section("Excursions vs alarm bands") {
                Note("Hypo off the τ.25 edge, hyper off τ.75")
                ExcursionTable(scored)
            }
        }

        // ── 3b. CG-EGA — whole window (§6.3), computed only on request ──
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
    }
}

// ── The realized-accuracy tables ───────────────────────────────────────────────────────────────
//
// Column order follows `T1DMAI/realdata/report.py::_suite_table` so a figure here lines up with the
// same figure there. Every table scrolls sideways (DataTable) rather than crushing its columns.

private fun col(header: String, weight: Float) =
    com.t1dm.core.design.TableColumn(header, weight, numeric = true)

private const val WIDE = 980

/** §6.2 headline. `cov50`/`w50` are in the SAME row as the band errors, deliberately: they are what
 *  keeps a band widened until it swallows every truth from reading as a flawless score. */
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

/** The same block on the median line — a different quantity on one forecast (§6.2), so a separate
 *  table rather than extra columns. No coverage: the median is a line and has none. */
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

/** The outer envelope and the persistence baseline both bases' skill is measured against. */
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

/** §6.1 band-edge recall/precision, with the denominators beside them — a recall of 1.00 over one
 *  true crossing is not the same claim as one over forty. */
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

private fun pct(v: Double): String = if (!v.isFinite()) "—" else "%.1f".format(v * 100)

private fun skill(b: PointBlock): String = f2(b.skillPoint)

/** Why the panel is empty — never merely THAT it is. */
private fun emptyWhy(m: ModelMetrics?): String {
    if (m == null) return "Insufficient history — nothing scored yet"
    val built = m.nMatured - m.nIncomplete
    return when {
        m.nMatured == 0 -> "Insufficient history — no matured forecast yet"
        built == 0 -> "CGM gaps — ${m.nIncomplete} of ${m.nMatured} forecasts dropped"
        m.suite.nWindows == 0 -> "Fan not scoreable — $built forecasts rejected"
        else -> "Insufficient history — ${m.suite.nWindows} scored windows"
    }
}

/**
 * The per-model forecast-backend chooser (issue 20 STEP 4, relocated from Settings). Governs the
 * DISPLAY forecast only; per §3.6-E dose advice always runs on the fp32 XNNPACK CPU authority (or a
 * backend that PASSED the agreement probe), so a non-authoritative choice renders the forecast while
 * dosing stays pinned to the CPU authority.
 */
@Composable
private fun ComputeBackendControls(
    running: com.t1dm.core.model.RunningModel,
    catalog: List<BackendAvailability>,
    requestedBackend: BackendId?,
    comparison: BackendComparison?,
    onSelectBackend: (BackendId?) -> Unit,
    onRunComparison: () -> Unit,
) {
    var refusal by remember { mutableStateOf<String?>(null) }
    val haptics = rememberT1dmHaptics()

    Note(
        "Display forecast only; dose advice stays on CPU unless probed",
    )

    // Live truth: what is ACTUALLY executing (may differ from the request on a load failure).
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Executing on ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${running.backend.displayName()} · ${running.precision.name}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
    }

    // Auto row (auto = the fp32 CPU authority).
    BackendChoiceRow(
        title = "Auto (fp32 CPU authority)",
        subtitle = "Authoritative XNNPACK CPU path — trusted for dose advice",
        available = true,
        selected = requestedBackend == null,
        onClick = { haptics.perform(HapticEvent.SegmentTick); refusal = null; onSelectBackend(null) },
    )
    // This build ships exactly two real compute paths: the XNNPACK CPU authority and the Vulkan GPU
    // delegate. The Play-delivered NeuroPilot NPU / legacy LiteRT rows are not reachable here.
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
            // An unavailable backend is a REAL refusal — the row stays enabled deliberately so it can
            // explain itself in [refusal] rather than going dead, which is exactly the case the
            // vocabulary's Reject exists for (a disabled control could say nothing at all).
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
    refusal?.let {
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
        onClick = { haptics.perform(HapticEvent.Tap); refusal = null; onRunComparison() },
    ) { Text("Run agreement probe") }
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

// ── small building blocks ──

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
