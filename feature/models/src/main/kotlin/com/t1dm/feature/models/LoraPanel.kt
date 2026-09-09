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
import androidx.compose.material3.FilterChip
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

data class LoraFitSpec(
    val name: String,
    val rank: Int,
    val epochs: Int,
    val windows: Int,
    val targetHidden: Boolean = false,
    val targetL0: Boolean = false,
    val targetL1: Boolean = true,
    val targetL2: Boolean = true,
)

data class LoraFitProgress(val phase: Phase, val done: Int, val total: Int) {
    enum class Phase { Replay, Train }

    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    val label: String get() = when (phase) {
        Phase.Replay -> "Replay $done/$total"
        Phase.Train -> "Epoch $done/$total"
    }
}

data class LoraAdapter(
    val id: Long,
    val modelId: String,
    val name: String,
    val rank: Int,
    val nParams: Int,
    val nTrain: Int,
    val nHoldout: Int,
    val holdoutBefore: Double,
    val holdoutAfter: Double,
    val improved: Boolean,
    val attached: Boolean,
    val updatedAtMs: Long,
    /** Null when it may attach; same predicate as LabController.attach so button/gate agree. */
    val attachRefusal: String? = null,
    /** Marginal dose response kept, as a ratio in risk space; 1.0 is preservation. */
    val guardRetention: Double = 0.0,
    val guardWindows: Int = 0,
    /** mg/dL per unit at the horizon, frozen model and adapted. */
    val guardFrozenMgdl: Double = 0.0,
    val guardAdaptedMgdl: Double = 0.0,
    val guardOverridden: Boolean = false,
)

data class LoraPanelState(
    val modelId: String,
    val adapters: List<LoraAdapter> = emptyList(),
    /** Why no adapter can be taken; null when one can. */
    val unavailable: String? = null,
    val progress: LoraFitProgress? = null,
    val error: String? = null,
    val lastReport: String? = null,
) {
    val busy: Boolean get() = progress != null
}

@Composable
fun LoraPanel(
    state: LoraPanelState,
    onFit: (LoraFitSpec) -> Unit,
    onAttach: (Long) -> Unit,
    /** The route out of `ABSENT` for an imported or restored adapter, short of the override. */
    onProbe: (Long) -> Unit = {},
    /** Clears a guard refusal; the controller, not this dialog, compares the typed name. */
    onOverride: (Long, String) -> Unit = { _, _ -> },
    onDetach: () -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onExport: (Long) -> Unit,
    onImport: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    var fitting by remember { mutableStateOf(false) }
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) {
        if (wasBusy && !state.busy) {
            haptics.perform(if (state.error != null) HapticEvent.Warn else HapticEvent.Confirm)
        }
        wasBusy = state.busy
    }
    var renaming by remember { mutableStateOf<LoraAdapter?>(null) }
    var deleting by remember { mutableStateOf<LoraAdapter?>(null) }
    var overriding by remember { mutableStateOf<LoraAdapter?>(null) }

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
                            // `attach` enforces the same predicate; this is affordance, not gate.
                            TextButton(
                                enabled = a.attachRefusal == null,
                                onClick = { haptics.perform(HapticEvent.Commit); onAttach(a.id) },
                            ) { Text("Attach") }
                            if (a.attachRefusal != null && !a.guardOverridden) {
                                TextButton(
                                    enabled = !state.busy,
                                    onClick = { haptics.perform(HapticEvent.Tap); onProbe(a.id) },
                                ) { Text("Probe") }
                                TextButton(onClick = { haptics.perform(HapticEvent.Warn); overriding = a }) {
                                    Text("Override")
                                }
                            }
                        }
                    }
                    a.attachRefusal?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // INCONCLUSIVE can carry non-finite retention (frozen, /0); NaN% misleads.
                    if (a.guardWindows > 0 && a.guardRetention.isFinite()) {
                        Text(
                            "dose response ${"%.0f".format(a.guardRetention * 100)}% over " +
                                "${a.guardWindows} windows " +
                                "(${"%.1f".format(a.guardAdaptedMgdl)} vs " +
                                "${"%.1f".format(a.guardFrozenMgdl)} mg/dL/U)",
                            style = MaterialTheme.typography.bodySmall,
                        )
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
    overriding?.let { a ->
        var typed by remember(a.id) { mutableStateOf("") }
        LaunchedEffect(a.id) { haptics.perform(HapticEvent.Warn) }
        AlertDialog(
            onDismissRequest = { haptics.perform(HapticEvent.Reject); overriding = null },
            title = { Text("Override the dose-response check?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.attachRefusal.orEmpty())
                    Text("Type ${a.name} to confirm")
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed.trim() == a.name,
                    onClick = {
                        haptics.perform(HapticEvent.Commit)
                        onOverride(a.id, typed)
                        overriding = null
                    },
                ) { Text("Override") }
            },
            dismissButton = {
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Reject); overriding = null },
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
    // One detent per slider, so a tick marks the step rather than every pointer sample.
    val rankDetent = rememberHapticDetent(HapticEvent.SegmentTick)
    val epochDetent = rememberHapticDetent(HapticEvent.SegmentTick)
    val windowDetent = rememberHapticDetent(HapticEvent.SegmentTick)
    var name by remember { mutableStateOf(defaultName) }
    var rank by remember { mutableStateOf(4f) }
    var epochs by remember { mutableStateOf(20f) }
    var windows by remember { mutableStateOf(200f) }
    var tHidden by remember { mutableStateOf(false) }
    var tL0 by remember { mutableStateOf(false) }
    var tL1 by remember { mutableStateOf(true) }
    var tL2 by remember { mutableStateOf(true) }
    val noSite = !tHidden && !tL0 && !tL1 && !tL2
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
                Text("Sites", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(tHidden, { tHidden = !tHidden }, { Text("hidden") })
                    FilterChip(tL0, { tL0 = !tL0 }, { Text("l0") })
                    FilterChip(tL1, { tL1 = !tL1 }, { Text("l1") })
                    FilterChip(tL2, { tL2 = !tL2 }, { Text("l2") })
                }
            }
        },
        confirmButton = {
            TextButton(
                // No site trains nothing; the crate refuses it too.
                enabled = !noSite,
                onClick = {
                    onFit(
                        LoraFitSpec(
                            name = name.trim().ifBlank { defaultName },
                            rank = rank.roundToInt(),
                            epochs = epochs.roundToInt(),
                            windows = windows.roundToInt(),
                            targetHidden = tHidden,
                            targetL0 = tL0,
                            targetL1 = tL1,
                            targetL2 = tL2,
                        ),
                    )
                },
            ) { Text("Fit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
