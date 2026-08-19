package com.t1dm.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import kotlin.math.roundToInt

/** What a fit is about to be given. */
data class LoraFitSpec(
    val name: String,
    val rank: Int,
    val epochs: Int,
    val windows: Int,
)

/**
 * How far a running fit has got. The two phases cost very differently — the replay is one graph
 * forward per window, the training loop is one pass over every window per epoch — so they are
 * counted separately rather than folded into one bar that stalls.
 */
data class LoraFitProgress(val phase: Phase, val done: Int, val total: Int) {
    enum class Phase { Replay, Train }

    /** Null while the total is unknown, which renders as an indeterminate bar. */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    val label: String get() = when (phase) {
        Phase.Replay -> "Replay $done/$total"
        Phase.Train -> "Epoch $done/$total"
    }
}

/** The adapter panel's state for one model. */
data class LoraPanelState(
    val modelId: String,
    val adapters: List<LabAdapter> = emptyList(),
    /** Why this model can take no adapter, or null when it can. */
    val unavailable: String? = null,
    val progress: LoraFitProgress? = null,
    val error: String? = null,
    val lastReport: String? = null,
) {
    val busy: Boolean get() = progress != null
}

/**
 * The adapters of one model: fit, attach, rename, delete, back up.
 *
 * The base weights are frozen inside the artifact and are never written. What is listed here is a
 * few thousand numbers fitted from this patient's own matured windows, and the held-out pair beside
 * each row is the whole basis for attaching one — an adapter that did not beat the frozen head on
 * windows it never saw has learnt the patient's past, not their physiology.
 */
@Composable
fun LoraPanel(
    state: LoraPanelState,
    onFit: (LoraFitSpec) -> Unit,
    onAttach: (Long) -> Unit,
    onDetach: () -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onExport: (Long) -> Unit,
    onImport: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    var fitting by remember { mutableStateOf(false) }
    // One pulse when a fit lands, not one per progress tick: the bar is continuous and the
    // completion is the only event.
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) {
        if (wasBusy && !state.busy) {
            haptics.perform(if (state.error != null) HapticEvent.Warn else HapticEvent.Confirm)
        }
        wasBusy = state.busy
    }
    var renaming by remember { mutableStateOf<LabAdapter?>(null) }
    var deleting by remember { mutableStateOf<LabAdapter?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.unavailable?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        state.progress?.let { p ->
            Text(p.label, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            val f = p.fraction
            if (f == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            }
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        state.lastReport?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { haptics.perform(HapticEvent.Tap); fitting = true },
                enabled = state.unavailable == null && !state.busy,
            ) { Text("Fit") }
            OutlinedButton(
                onClick = { haptics.perform(HapticEvent.Tap); onImport() },
                enabled = !state.busy,
            ) { Text("Import") }
            if (state.adapters.any { it.attached }) {
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Commit); onDetach() },
                    enabled = !state.busy,
                ) { Text("Detach") }
            }
        }
        HorizontalDivider()

        if (state.adapters.isEmpty()) {
            Text("No adapters", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.adapters, key = { it.id }) { a ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (a.attached) "● ${a.name}" else a.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer()
                        if (!a.attached) {
                            TextButton(onClick = { haptics.perform(HapticEvent.Commit); onAttach(a.id) }) {
                                Text("Attach")
                            }
                        }
                    }
                    Text(
                        "r${a.rank} · ${a.nParams} params · ${a.nTrain}+${a.nHoldout} windows",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "held out ${"%.4f".format(a.holdoutBefore)} → ${"%.4f".format(a.holdoutAfter)}" +
                            if (a.improved) "" else " · no gain",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (a.improved) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { haptics.perform(HapticEvent.Tap); renaming = a },
                        ) { Text("Rename") }
                        TextButton(
                            onClick = { haptics.perform(HapticEvent.Confirm); onExport(a.id) },
                        ) { Text("Export") }
                        TextButton(onClick = { haptics.perform(HapticEvent.Tap); deleting = a }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (fitting) {
        FitDialog(
            defaultName = "fit ${state.adapters.size + 1}",
            onDismiss = { haptics.perform(HapticEvent.Reject); fitting = false },
            onFit = { haptics.perform(HapticEvent.Commit); fitting = false; onFit(it) },
        )
    }
    renaming?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.name) }
        AlertDialog(
            onDismissRequest = { haptics.perform(HapticEvent.Reject); renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticEvent.Commit)
                        onRename(a.id, name.trim().ifBlank { a.name })
                        renaming = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Reject); renaming = null },
                ) { Text("Cancel") }
            },
        )
    }
    deleting?.let { a ->
        LaunchedEffect(a.id) { haptics.perform(HapticEvent.Warn) }
        AlertDialog(
            onDismissRequest = { haptics.perform(HapticEvent.Reject); deleting = null },
            title = { Text("Delete adapter?") },
            text = {
                Text(
                    if (a.attached) {
                        "\"${a.name}\" is attached — the forecast goes back to the frozen model."
                    } else {
                        "Delete \"${a.name}\"? A fit cannot be recovered without a backup."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Commit); onDelete(a.id); deleting = null },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { haptics.perform(HapticEvent.Reject); deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Spacer() = androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 6.dp))

@Composable
private fun FitDialog(defaultName: String, onDismiss: () -> Unit, onFit: (LoraFitSpec) -> Unit) {
    // One detent per slider: each is stepped, and a tick should mark the step the user landed on
    // rather than every pointer sample the drag produced.
    val rankDetent = rememberHapticDetent(HapticEvent.SegmentTick)
    val epochDetent = rememberHapticDetent(HapticEvent.SegmentTick)
    val windowDetent = rememberHapticDetent(HapticEvent.SegmentTick)
    var name by remember { mutableStateOf(defaultName) }
    var rank by remember { mutableStateOf(4f) }
    var epochs by remember { mutableStateOf(20f) }
    var windows by remember { mutableStateOf(200f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fit adapter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
                Text("Rank ${rank.roundToInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = rank,
                    onValueChange = { rankDetent.at(it.roundToInt()); rank = it },
                    valueRange = 1f..16f,
                    steps = 14,
                )
                Text("Epochs ${epochs.roundToInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = epochs,
                    onValueChange = { epochDetent.at(it.roundToInt()); epochs = it },
                    valueRange = 5f..60f,
                    steps = 10,
                )
                Text("Windows ${windows.roundToInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = windows,
                    onValueChange = { windowDetent.at(it.roundToInt()); windows = it },
                    valueRange = 40f..400f,
                    steps = 8,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onFit(
                        LoraFitSpec(
                            name = name.trim().ifBlank { defaultName },
                            rank = rank.roundToInt(),
                            epochs = epochs.roundToInt(),
                            windows = windows.roundToInt(),
                        ),
                    )
                },
            ) { Text("Fit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
