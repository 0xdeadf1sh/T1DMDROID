package com.t1dm.feature.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.panelCardColors
import com.t1dm.core.design.rememberT1dmHaptics

/** One archive already sitting in the chosen folder. */
class BackupEntry(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)

/** Everything the panel renders. Assembled in `:app`; this module sees no repository, no SAF and
 *  no WorkManager. */
class BackupPanelState(
    val folderLabel: String? = null,
    val cadenceHours: Int = 0,
    val cadenceStops: List<Int> = emptyList(),
    val keep: Int = 7,
    val keepStops: List<Int> = emptyList(),
    val lastOkAtMs: Long? = null,
    val lastOkBytes: Long = 0,
    val lastOkRows: Int = 0,
    val lastErrorAtMs: Long? = null,
    val lastError: String? = null,
    val backups: List<BackupEntry> = emptyList(),
    /** Non-null while an operation runs, and what it is. Disables every action. */
    val busy: String? = null,
    /** The outcome of the last thing the user asked for. */
    val status: String? = null,
    val nowMs: Long = 0L,
)

/**
 * The Backup panel: the whole local record to a file, on a schedule, to a folder that survives
 * uninstall.
 *
 * A restore MERGES and never overwrites, which is why the affordance carries no typed confirmation
 * the way the reset does — the worst a mistaken restore can do is add rows the phone was missing.
 * Deleting a stored archive is the one irreversible act here and is the only one behind a dialog.
 *
 * Pure and stateless in the house mould: state in, callbacks out.
 */
@Composable
fun BackupScreen(
    state: BackupPanelState,
    onPickFolder: () -> Unit = {},
    onSetCadence: (Int) -> Unit = {},
    onSetKeep: (Int) -> Unit = {},
    onBackupNow: () -> Unit = {},
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onRestore: (BackupEntry) -> Unit = {},
    onDelete: (BackupEntry) -> Unit = {},
) {
    val haptics = rememberT1dmHaptics()
    var pendingDelete by remember { mutableStateOf<BackupEntry?>(null) }
    val enabled = state.busy == null

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item { SectionHeader("Automatic") }

        item {
            Card(colors = panelCardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.folderLabel ?: "No folder chosen",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        OutlinedButton(
                            onClick = { haptics.perform(HapticEvent.Tap); onPickFolder() },
                            enabled = enabled,
                        ) { Text(if (state.folderLabel == null) "Choose…" else "Change…") }
                    }
                    // The one fact the control cannot carry: why the folder must be outside the app.
                    Note("Outside app storage, so it survives uninstall")

                    ChipRow(
                        label = "Every",
                        options = state.cadenceStops,
                        selected = state.cadenceHours,
                        enabled = enabled && state.folderLabel != null,
                        render = ::cadenceLabel,
                        onSelect = { haptics.perform(HapticEvent.Tap); onSetCadence(it) },
                    )
                    ChipRow(
                        label = "Keep",
                        options = state.keepStops,
                        selected = state.keep,
                        enabled = enabled && state.folderLabel != null,
                        render = { it.toString() },
                        onSelect = { haptics.perform(HapticEvent.Tap); onSetKeep(it) },
                    )

                    Button(
                        onClick = { haptics.perform(HapticEvent.Commit); onBackupNow() },
                        enabled = enabled && state.folderLabel != null,
                    ) { Text("Back up now") }

                    if (state.lastOkAtMs != null) {
                        Note(
                            "Last ${relativeAge(state.nowMs, state.lastOkAtMs)} · " +
                                "${formatBytes(state.lastOkBytes)} · ${formatRows(state.lastOkRows)} rows",
                        )
                    }
                    // Shown ALONGSIDE the last success, not instead of it: a destination that worked
                    // last week and has failed every night since is the state worth surfacing, and
                    // one overwritten status line would hide exactly that.
                    if (state.lastError != null && state.lastErrorAtMs != null) {
                        Text(
                            "Failed ${relativeAge(state.nowMs, state.lastErrorAtMs)} — ${state.lastError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item { SectionHeader("Manual") }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { haptics.perform(HapticEvent.Tap); onExport() },
                    enabled = enabled,
                ) { Text("Export…") }
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onImport() },
                    enabled = enabled,
                ) { Text("Import…") }
            }
        }

        item { Note("Restore merges — it never overwrites what is already here") }

        if (state.busy != null) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.padding(2.dp))
                    Text(state.busy, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (state.status != null) {
            item {
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (state.backups.isNotEmpty()) {
            item { SectionHeader("In ${state.folderLabel ?: "folder"}") }
            items(state.backups, key = { it.id }) { entry ->
                BackupRow(
                    entry = entry,
                    nowMs = state.nowMs,
                    enabled = enabled,
                    onRestore = { haptics.perform(HapticEvent.Commit); onRestore(entry) },
                    onDelete = { haptics.perform(HapticEvent.Warn); pendingDelete = entry },
                )
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete backup?") },
            text = { Text(entry.name) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.perform(HapticEvent.Commit)
                    onDelete(entry)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    haptics.perform(HapticEvent.Reject)
                    pendingDelete = null
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BackupRow(
    entry: BackupEntry,
    nowMs: Long,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = panelCardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${relativeAge(nowMs, entry.modifiedAtMs)} · ${formatBytes(entry.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRestore, enabled = enabled) { Text("Restore") }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = enabled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    options: List<Int>,
    selected: Int,
    enabled: Boolean,
    render: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) {
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(render(option)) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun cadenceLabel(hours: Int): String = when (hours) {
    0 -> "Off"
    24 -> "Day"
    24 * 7 -> "Week"
    else -> "${hours}h"
}

/** Bytes at one decimal past a megabyte, none below — a backup's size is a sanity check, not a
 *  measurement, and "1.2 MB" answers it where "1,234,567 B" does not. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "${bytes / 1024} kB"
    else -> "$bytes B"
}

internal fun formatRows(rows: Int): String =
    if (rows >= 10_000) "${rows / 1000}k" else rows.toString()

/**
 * Coarse relative age. A backup is judged in hours and days, so minutes are the finest unit worth
 * printing and anything older than a fortnight is simply "3w". A future timestamp — a provider with
 * a skewed clock, or a file copied from another device — reads as "just now" rather than as a
 * negative age.
 */
internal fun relativeAge(nowMs: Long, thenMs: Long): String {
    val delta = (nowMs - thenMs).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 14 -> "${minutes / (60 * 24)}d ago"
        else -> "${minutes / (60 * 24 * 7)}w ago"
    }
}
