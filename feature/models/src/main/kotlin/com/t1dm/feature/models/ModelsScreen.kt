package com.t1dm.feature.models

import androidx.compose.foundation.clickable
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
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.RunningModel

/**
 * The Models panel (PLAN.private.md Phase 2 §3.5 / Models section): the loaded running set and which
 * model is selected (fp32-authoritative). Tapping a row selects it. Each row also surfaces this
 * cycle's forecast status (OK / DEGENERATE class / STALE) so the running set's health is legible at
 * a glance. The full auto-adopted registry + `X-SHA256` artifact download is Phase 3.5.
 */
@Composable
fun ModelsScreen(state: InferenceState, onSelect: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${state.running.size} loaded · tap to select the fp32-authoritative model",
                style = MaterialTheme.typography.bodySmall,
            )
            state.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }

        if (state.running.isEmpty()) {
            item {
                Text(
                    "No models loaded. Push a descriptor.json (+ .pte) to the app models dir.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(state.running, key = { it.modelId }) { model ->
                ModelRow(model, state.predictions.firstOrNull { it.modelId == model.modelId }, onSelect)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ModelRow(model: RunningModel, prediction: ModelPrediction?, onSelect: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onSelect(model.modelId) }.padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                (if (model.selected) "● " else "○ ") + model.modelId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (model.selected) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
            )
            Text(statusLabel(prediction), style = MaterialTheme.typography.labelMedium, color = statusColor(prediction))
        }
        Text(
            "${model.backend.name} · ${model.precision.name}" +
                (prediction?.let { " · anchor ${it.lastBg.toInt()} mg/dL" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun statusColor(p: ModelPrediction?) = when {
    p == null -> MaterialTheme.colorScheme.onSurfaceVariant
    p.stale || p.status != ForecastStatus.OK -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}

private fun statusLabel(p: ModelPrediction?): String = when {
    p == null -> "no forecast"
    p.stale -> "STALE"
    p.status != ForecastStatus.OK -> p.status.name
    else -> "OK"
}
