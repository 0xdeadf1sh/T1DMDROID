package com.t1dm.feature.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.RunningModel
import com.t1dm.core.model.displayName

/**
 * The Models panel (Phase 7C — item 7): the loaded running set, each row now carrying
 * the size-reasoning META (parameter count, on-disk `.pte` size, and the key arch dims from the
 * descriptor) so a model's footprint is legible at a glance, plus this cycle's forecast status. Tapping
 * a row opens its PERFORMANCE drill-down ([ModelDetailScreen], item 24). Selection is on a long-press-
 * free single tap in the pre-7D layout via the caret; the fp32-authoritative pick is [onSelect].
 */
@Composable
fun ModelsScreen(
    state: InferenceState,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            // N1 — the "Models" title lives in the breadcrumb; no duplicate in-view header.
            Text(
                "${state.running.size} loaded · tap a row for performance · tap the radio to select",
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
                ModelRow(
                    model = model,
                    prediction = state.predictions.firstOrNull { it.modelId == model.modelId },
                    meta = state.metaOf(model.modelId),
                    onSelect = onSelect,
                    onOpen = onOpen,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: RunningModel,
    prediction: ModelPrediction?,
    meta: ModelMeta?,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    // N3 — tapping ANYWHERE on the row (name included) opens the detail; "select this model" is now an
    // explicit RadioButton, never an invisible tap target on the title. Previously the name carried its
    // own `onSelect` clickable that consumed the tap, so only the description below opened the detail.
    Column(
        Modifier.fillMaxWidth().clickable { onOpen(model.modelId) }.padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = model.selected,
                onClick = { onSelect(model.modelId) },
                modifier = Modifier.size(28.dp),
            )
            Text(
                model.modelId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (model.selected) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Text(statusLabel(prediction), style = MaterialTheme.typography.labelMedium, color = statusColor(prediction))
        }
        Text(
            "${model.backend.displayName()} · ${model.precision.name}" +
                (prediction?.let { " · anchor ${it.lastBg.toInt()} mg/dL" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Size-reasoning meta line (item 7): real param count + on-disk size + arch dims.
        meta?.let { m ->
            Text(
                metaLine(m),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun metaLine(m: ModelMeta): String {
    val parts = buildList {
        m.paramCount?.let { add("${fmtParams(it)} params") }
        m.diskBytes?.let { add(fmtBytes(it)) }
        val dims = listOfNotNull(
            m.dModel?.let { "d$it" },
            m.nLayers?.let { "L$it" },
            m.nHeads?.let { "H$it" },
        )
        if (dims.isNotEmpty()) add(dims.joinToString("·"))
    }
    return if (parts.isEmpty()) "meta n/a (no descriptor model_card)" else parts.joinToString(" · ")
}

internal fun fmtParams(n: Long): String = when {
    n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

internal fun fmtBytes(b: Long): String = when {
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.1f KB".format(b / (1L shl 10).toDouble())
    else -> "$b B"
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
