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
import com.t1dm.core.model.AccuracyReport
import com.t1dm.core.model.BackendAvailability
import com.t1dm.core.model.BackendComparison
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.HorizonAccuracy
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.ModelTelemetry
import com.t1dm.core.model.ReferenceMetrics
import com.t1dm.core.model.displayName

/**
 * The per-model PERFORMANCE drill-down (Phase 7C — item 24). Four blocks:
 *
 *  1. META — parameter count, on-disk size, arch dims + geometry (item 7, size reasoning).
 *  2. TELEMETRY — this install's cumulative avg inference EXEC time, #predictions, and TOTAL time
 *     spent in the backend forward (the [ModelTelemetry] the CycleRunner updates + persists).
 *  3. ON-DEVICE REALIZED ACCURACY — RMSE/MAE/MARD (+ central-90 coverage) at 30/60/120 min, computed
 *     by the golden-gated Rust aggregator over stored `prediction` rows vs the realized `cgm_reading`
 *     at each horizon. A horizon with too little matured history says so PLAINLY (never a noisy stat).
 *  4. REFERENCE (held-out validation) — the model's own train.py validation metrics from the
 *     descriptor `model_card`, clearly labelled as reference, NOT the on-device realized numbers.
 *
 * Everything is advisory: the accuracy of a FORECAST, never a dosing claim (safety-posture.md).
 */
@Composable
fun ModelDetailScreen(
    state: InferenceState,
    modelId: String,
    accuracy: AccuracyReport?,
    accuracyLoading: Boolean,
    onRecomputeAccuracy: () -> Unit,
    catalog: List<BackendAvailability>,
    requestedBackend: BackendId?,
    comparison: BackendComparison?,
    onSelectBackend: (BackendId?) -> Unit,
    onRunComparison: () -> Unit,
) {
    val meta = state.metaOf(modelId)
    val telemetry = state.telemetryOf(modelId)
    val running = state.runningOf(modelId)

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
                Note("No descriptor metadata for this model.")
            } else {
                KeyVal("parameters", meta.paramCount?.let { "${fmtParams(it)}  (${"%,d".format(it)})" } ?: "n/a")
                KeyVal("artifact size", meta.diskBytes?.let { fmtBytes(it) } ?: "n/a (StubBackend — no .pte on disk)")
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
                Note("No forecasts recorded yet — the counter fills once a cycle runs.")
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
                        "Compute-backend switching and the agreement probe apply to the SELECTED model. " +
                            "This model runs on ${running.backend.displayName()} · ${running.precision.name}; " +
                            "select it on the Models screen (the radio) to view and change its backend.",
                    )
                }
            }
        }

        // ── 3. On-device realized accuracy ──
        section("On-device realized accuracy") {
            Note("Forecast median vs the realized sensor BG at each horizon (advisory — a forecast-accuracy statement, not a dosing claim).")
            // Keep the prior horizon rows on screen through a recompute (issue 6): only collapse to the
            // one-line "Computing…" when there is NO prior accuracy; otherwise the rows stay put and a
            // subtle inline hint (in the button row, so section height is unchanged) marks the refresh.
            val horizons = accuracy?.horizons.orEmpty()
            when {
                horizons.isNotEmpty() -> horizons.forEach { HorizonRow(it) }
                accuracyLoading -> Note("Computing…")
                else -> Note("Insufficient history — no matured forecasts have been paired with a realized reading yet.")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRecomputeAccuracy) { Text("Recompute") }
                if (accuracyLoading && horizons.isNotEmpty()) {
                    Note("Recomputing…")
                }
            }
        }

        // ── 4. Reference (held-out validation) ──
        meta?.reference?.let { ref ->
            section("Reference — held-out validation (train.py)") {
                Note("The model's own validation metrics at export${meta.valStep?.let { " (step ${"%,d".format(it)})" } ?: ""} — reference only, not on-device.")
                ReferenceRows(ref)
                ref.clarkeAbPct?.let { KeyVal("Clarke A+B", "%.1f%%".format(it)) }
                if (ref.todMaeH != null) {
                    KeyVal("time-head hour error", "${"%.2f".format(ref.todMaeH)} h" + (ref.todMaeHiconfH?.let { " (hi-conf ${"%.2f".format(it)} h)" } ?: ""))
                }
            }
        }
    }
}

@Composable
private fun HorizonRow(h: HorizonAccuracy) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("${h.horizonMin} min", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (!h.sufficient) {
            Note("insufficient history (${h.n} matured — need more)")
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("RMSE", "%.1f".format(h.rmse))
                Metric("MAE", "%.1f".format(h.mae))
                Metric("MARD", "%.1f%%".format(h.mard))
                h.coverage90?.let { Metric("cov90", "%.0f%%".format(it * 100)) }
                Metric("n", h.n.toString())
            }
        }
    }
}

@Composable
private fun ReferenceRows(ref: ReferenceMetrics) {
    // Shared aligned grid (issues 10/11/15) — fixed-weight columns, right-aligned tabular numerics.
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("horizon", 0.9f),
            com.t1dm.core.design.TableColumn("RMSE", 1f, numeric = true),
            com.t1dm.core.design.TableColumn("MARD", 1f, numeric = true),
            com.t1dm.core.design.TableColumn("Clarke-A", 1.1f, numeric = true),
            com.t1dm.core.design.TableColumn("cov90", 1f, numeric = true),
        ),
        rows = ref.horizonsMin.mapIndexed { i, h ->
            listOf(
                "${h}m",
                ref.rmseMgdl.getOrNull(i)?.let { "%.1f".format(it) } ?: "—",
                ref.mardPct.getOrNull(i)?.let { "%.1f%%".format(it) } ?: "—",
                ref.clarkeAPct.getOrNull(i)?.let { "%.0f%%".format(it) } ?: "—",
                ref.coverage90.getOrNull(i)?.let { "%.0f%%".format(it * 100) } ?: "—",
            )
        },
    )
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

    Note(
        "Choose which compute unit runs this model's display forecast — dose advice always stays on the " +
            "fp32 XNNPACK CPU authority unless another backend passes the agreement probe below.",
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
        subtitle = "Always the authoritative XNNPACK CPU path — trusted for dose advice.",
        available = true,
        selected = requestedBackend == null,
        onClick = { refusal = null; onSelectBackend(null) },
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
            onClick = {
                if (b.available) { refusal = null; onSelectBackend(b.backend) }
                else refusal = "${b.backend.displayName()} is unavailable: ${b.reason ?: "no artifact on device"}"
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
        "Runs the selected backend and the CPU authority on the same fixed input and compares speed and " +
            "numerics — a PASS on the decoded-mg/dL agreement is what lets that backend feed dose advice.",
    )
    Button(onClick = { refusal = null; onRunComparison() }) { Text("Run agreement probe & measure") }
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
                if (pass) "AGREEMENT: PASS — this backend may feed dose advice."
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
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
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
