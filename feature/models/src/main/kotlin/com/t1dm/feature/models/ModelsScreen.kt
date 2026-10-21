package com.t1dm.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.hapticClickable
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelComparison
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.RunningModel
import com.t1dm.core.model.displayName
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(
    state: InferenceState,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit = {},
    onOpenAdapters: (String) -> Unit = {},
    importStatus: String? = null,
    onImport: () -> Unit = {},
    /** SPEC/zoo.md models: id to family; display and backtest only, never selectable. */
    zooModels: List<Pair<String, String>> = emptyList(),
    zooPredictions: List<ModelPrediction> = emptyList(),
    comparison: ModelComparison? = null,
    onCompare: (days: Int?) -> Unit = {},
    onCancelCompare: () -> Unit = {},
) {
    // Hoisted to the screen: a row recycling out of the viewport would drop a per-row confirmation.
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val haptics = rememberT1dmHaptics()
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp).fadingEdges(listState),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "${state.running.size} loaded · tap a row for detail",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = { haptics.perform(HapticEvent.Tap); onImport() }) { Text("Import zip") }
                importStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }

            item { CompareSection(comparison, onCompare, onCancelCompare) }

            items(zooModels, key = { "zoo:" + it.first }) { (id, family) ->
                ZooRow(
                    id = id,
                    family = family,
                    prediction = zooPredictions.firstOrNull { it.modelId == id },
                    onOpen = onOpen,
                    onRequestDelete = { confirmDelete = it },
                )
                HorizontalDivider()
            }

            if (state.running.isEmpty() && zooModels.isEmpty()) {
                item {
                    Text(
                        "No models loaded",
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
                        onRequestDelete = { confirmDelete = it },
                        onOpenAdapters = onOpenAdapters,
                    )
                    HorizontalDivider()
                }
            }
        }
        confirmDelete?.let { id ->
            LaunchedEffect(id) { haptics.perform(HapticEvent.Warn) }
            AlertDialog(
                onDismissRequest = { haptics.perform(HapticEvent.Reject); confirmDelete = null },
                title = { Text("Remove model?") },
                text = {
                    Text("Delete \"$id\" and its artifact? Removing the selected model stops forecast and dose advice.")
                },
                confirmButton = {
                    TextButton(
                        onClick = { haptics.perform(HapticEvent.Commit); onDelete(id); confirmDelete = null },
                    ) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { haptics.perform(HapticEvent.Reject); confirmDelete = null },
                    ) { Text("Cancel") }
                },
            )
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
    onRequestDelete: (String) -> Unit,
    onOpenAdapters: (String) -> Unit = {},
) {
    val haptics = rememberT1dmHaptics()
    Column(
        Modifier
            .fillMaxWidth()
            .hapticClickable(HapticEvent.NavSwitch) { onOpen(model.modelId) }
            .padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // §3.6-E — the dosing model picker, not a nav affordance.
            RadioButton(
                selected = model.selected,
                onClick = { haptics.perform(HapticEvent.SegmentTick); onSelect(model.modelId) },
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
            IconButton(
                onClick = { haptics.perform(HapticEvent.Tap); onRequestDelete(model.modelId) },
                modifier = Modifier.size(40.dp),
            ) {
                Text("✕", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            // displayName() already ends in the precision; appending the enum repeats it.
            model.backend.displayName() +
                (prediction?.let { " · anchor ${it.lastBg.toInt()} mg/dL" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        meta?.let { m ->
            Text(
                metaLine(m),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = { haptics.perform(HapticEvent.Tap); onOpenAdapters(model.modelId) },
        ) { Text("Adapters") }
    }
}

@Composable
private fun ZooRow(
    id: String,
    family: String,
    prediction: ModelPrediction?,
    onOpen: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    Column(
        Modifier
            .fillMaxWidth()
            .hapticClickable(HapticEvent.NavSwitch) { onOpen(id) }
            .padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                id,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).padding(start = 36.dp),
            )
            Text(statusLabel(prediction), style = MaterialTheme.typography.labelMedium, color = statusColor(prediction))
            IconButton(
                onClick = { haptics.perform(HapticEvent.Tap); onRequestDelete(id) },
                modifier = Modifier.size(40.dp),
            ) {
                Text("✕", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            "zoo · $family · display only" + (prediction?.medianBg?.getOrNull(5)?.let { " · 30 min ${it.toInt()} mg/dL" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 36.dp),
        )
    }
}

@Composable
private fun CompareSection(comparison: ModelComparison?, onCompare: (Int?) -> Unit, onCancel: () -> Unit) {
    val haptics = rememberT1dmHaptics()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Compare", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (comparison is ModelComparison.Running) {
                TextButton(onClick = { haptics.perform(HapticEvent.Reject); onCancel() }) { Text("Stop") }
            } else {
                for (d in COMPARE_DAYS) {
                    OutlinedButton(onClick = { haptics.perform(HapticEvent.Tap); onCompare(d) }) { Text("$d d") }
                }
            }
        }
        when (comparison) {
            null -> {}
            is ModelComparison.Running -> Text(
                "${comparison.model}/${comparison.models} · ${comparison.modelId}",
                style = MaterialTheme.typography.bodySmall,
            )
            is ModelComparison.Refused -> Text(
                comparison.refusal.name.lowercase().replace('_', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            is ModelComparison.Done -> CompareTable(comparison)
        }
    }
}

@Composable
private fun CompareTable(done: ModelComparison.Done) {
    Text(
        "${done.nShared} shared windows · RMSE mg/dL, Clarke A %",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    fun at(r: ModelComparison.Row, min: Int) = r.metrics.suite.horizons.firstOrNull { it.horizonMin == min }
    val rows = done.rows.sortedBy { r -> at(r, 30)?.medianLine?.rmsePoint?.takeIf { it.isFinite() } ?: Double.MAX_VALUE }
    val header = listOf("model") + COMPARE_HORIZONS.map { "$it" } + listOf("A 30")
    CompareLine(header, bold = true)
    for (r in rows) {
        val cells = COMPARE_HORIZONS.map { h -> at(r, h)?.medianLine?.rmsePoint.fmt1() }
        CompareLine(listOf(r.modelId + if (r.zoo) "" else " *") + cells + at(r, 30)?.medianLine?.clarkeA.fmt1(), bold = false)
    }
    Text("* transformer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CompareLine(cells: List<String>, bold: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { i, c ->
            Text(
                c,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = if (i == 0) Modifier.weight(2.4f) else Modifier.weight(1f),
            )
        }
    }
}

private fun Double?.fmt1(): String = if (this == null || !isFinite()) "—" else "%.1f".format(this)

private val COMPARE_DAYS = listOf(7, 30)
private val COMPARE_HORIZONS = listOf(30, 60, 120)

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
