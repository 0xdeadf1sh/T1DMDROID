package com.t1dm.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.AccuracyReport
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

        // ── 3. On-device realized accuracy ──
        section("On-device realized accuracy") {
            Note("Forecast median vs the realized sensor BG at each horizon (advisory — a forecast-accuracy statement, not a dosing claim).")
            when {
                accuracyLoading -> Note("Computing…")
                accuracy == null || accuracy.horizons.isEmpty() ->
                    Note("Insufficient history — no matured forecasts have been paired with a realized reading yet.")
                else -> accuracy.horizons.forEach { HorizonRow(it) }
            }
            TextButton(onClick = onRecomputeAccuracy) { Text("Recompute") }
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
