package com.t1dm.feature.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelLatency
import com.t1dm.core.model.RunningModel

/**
 * The Hardware panel — per-model inference rows (PLAN.private.md Phase 2 §8 "Hardware panel — per-
 * model rows"): each `model_id`'s backend, precision, and p50/p95 latency, plus the aggregate cycle
 * duration/cause. The fp16 agreement Δ column is stubbed pending the deferred NPU shadow (§3.6-E);
 * the per-model split is already keyed so it drops in without a layout change.
 */
@Composable
fun HardwareScreen(state: InferenceState) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Inference hardware", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val cadence = state.lastCycleDurationMs?.let { "$it ms" } ?: "—"
            val cause = state.lastCause?.name ?: "—"
            Text(
                "last cycle: $cadence · cause $cause" +
                    (if (!state.realBackendAvailable) " · STUB backend (real path blocked)" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            state.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }

        if (state.running.isEmpty()) {
            item { Text("No models loaded.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(state.running, key = { it.modelId }) { model ->
                ModelRow(model, state.latencyOf(model.modelId))
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ModelRow(model: RunningModel, latency: ModelLatency?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                (if (model.selected) "● " else "○ ") + model.modelId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (model.selected) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
            )
            Text("${model.backend.name} · ${model.precision.name}", style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("p50", latency?.let { fmt(it.p50Ms) } ?: "—")
            Stat("p95", latency?.let { fmt(it.p95Ms) } ?: "—")
            Stat("last", latency?.let { fmt(it.lastMs) } ?: "—")
            Stat("runs", latency?.runs?.toString() ?: "0")
            Stat("agree Δ", "—") // fp16 shadow deferred (§3.6-E)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private fun fmt(ms: Double): String = if (ms >= 100) "${ms.toInt()} ms" else "%.1f ms".format(ms)
