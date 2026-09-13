package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics

@Composable
fun DataSettingsScreen(
    resetting: Boolean,
    onOpenBackup: () -> Unit,
    onReset: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    SettingsScaffold(SettingsScreenKey.DATA) {
        SettingsSectionHeader("Backup")
        SettingsAnchor(dataBackup, modifier = Modifier) {
            Button(
                onClick = { haptics.perform(HapticEvent.Tap); onOpenBackup() },
            ) { Text("Open Backup") }
        }
        SettingsNote("Back up before resetting — this screen cannot undo")

        SettingsSectionHeader("Danger zone")
        // The confirm flow's state is local, so a search hit always lands here disarmed.
        SettingsAnchor(dataReset) {
            ResetSection(resetting = resetting, onReset = onReset)
        }
    }
}

@Composable
private fun ResetSection(resetting: Boolean, onReset: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val haptics = rememberT1dmHaptics()

    SettingsNote("Erases everything — irreversible")

    if (!confirming) {
        OutlinedButton(
            onClick = { haptics.perform(HapticEvent.Warn); typed = ""; confirming = true },
            enabled = !resetting,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Reset app / erase all data") }
        return
    }

    DangerBanner(
        "This ERASES EVERYTHING, irreversibly:\n" +
            "• all glucose readings, the wide series, carb/bolus/basal logs, meals, forecasts\n" +
            "• every drawing on the glucose graph\n" +
            "• model performance and accuracy history (the model files themselves are kept)\n" +
            "• every setting, threshold, target, curve, theme and font — back to defaults\n" +
            "• the watch pairing and its keys — re-pair the watch\n\n" +
            "Restarts",
    )
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        singleLine = true,
        enabled = !resetting,
        label = { Text("Type ERASE to confirm") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { haptics.perform(HapticEvent.Reject); confirming = false; typed = "" },
            enabled = !resetting,
        ) { Text("Cancel") }
        Button(
            onClick = { haptics.perform(HapticEvent.Commit); onReset() },
            enabled = !resetting && typed.trim() == CONFIRM_WORD,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text("Erase everything") }
    }
    if (resetting) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.padding(2.dp))
            Text("Erasing and restarting…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val CONFIRM_WORD = "ERASE"

private val dataBackup = SettingsKnob(
    id = "data.backup",
    screen = SettingsScreenKey.DATA,
    section = "Backup",
    label = "Open Backup",
    subtitle = "The backup panel — archive, restore, and automatic backups",
    synonyms = listOf("backup", "restore", "archive", "save", "copy"),
)

private val backupExport = SettingsKnob(
    id = "backup.export",
    screen = SettingsScreenKey.BACKUP,
    section = "Manual",
    label = "Export…",
    subtitle = "Write the whole record — readings, meals, doses, drawings, settings — to one file",
    synonyms = listOf(
        "export", "backup", "save", "file", "copy", "transfer", "dump", "download", "archive", "share",
    ),
)

private val backupImport = SettingsKnob(
    id = "backup.import",
    screen = SettingsScreenKey.BACKUP,
    section = "Manual",
    label = "Import…",
    subtitle = "Merge a backup file back in; never overwrites what is already here",
    synonyms = listOf("import", "restore", "load", "file", "recover", "migrate", "transfer", "merge"),
)

private val backupAuto = SettingsKnob(
    id = "backup.auto",
    screen = SettingsScreenKey.BACKUP,
    section = "Automatic",
    label = "Automatic backup",
    subtitle = "Cadence, retention, and the folder they are written to",
    synonyms = listOf(
        "automatic", "auto", "schedule", "daily", "weekly", "periodic", "folder", "retention", "keep",
    ),
)

private val dataReset = SettingsKnob(
    id = "data.reset",
    screen = SettingsScreenKey.DATA,
    section = "Danger zone",
    label = "Reset app / erase all data",
    subtitle = "Permanent and irreversible; sits behind a typed ERASE confirmation",
    synonyms = listOf(
        "reset", "erase", "wipe", "delete", "clear", "factory reset", "start over", "nuke",
        "remove everything", "danger", "destroy", "first run",
    ),
)

internal val settingsDataKnobs = listOf(dataBackup, dataReset)

/** Declared here, not in `:feature:backup`: the index is this module's. */
internal val settingsBackupKnobs = listOf(backupExport, backupImport, backupAuto)
