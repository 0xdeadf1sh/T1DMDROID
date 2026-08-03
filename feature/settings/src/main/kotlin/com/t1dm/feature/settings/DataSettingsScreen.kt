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

/**
 * Settings → Reset: the DESTRUCTIVE full app reset (issue 5), and a way across to the Backup panel.
 *
 * Backup and restore used to live here as well, writing configuration and the BG panel's drawings to
 * a JSON file. That surface has moved WHOLE to `:feature:backup`, which archives the entire record —
 * every reading, every logged event, the catalogues — rather than settings alone. It is not
 * duplicated here: two backup surfaces would be two things to keep in step, and the one left behind
 * would be the one the user found first.
 *
 * The reset is IRREVERSIBLE, so it sits behind an explicit two-step, typed confirmation that spells
 * out exactly what is destroyed. Pure/stateless apart from the confirm-flow's local UI state.
 */
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
        // Worth stating once, here: this screen erases, and the thing that would have saved the user
        // from it is one panel away.
        SettingsNote("Back up before resetting — this screen cannot undo")

        SettingsSectionHeader("Danger zone")
        // A search hit always lands this section DISARMED — the confirm flow's state is local and
        // starts fresh on the composition the navigation creates — so the pulse can only ever mark
        // the button that summons the banner, never a live "Erase everything".
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
            // Arming the erase is where the Warn belongs — the press that summons the danger banner,
            // not the banner itself (which would then rumble on every page that carries one).
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
            "• the server profile and its saved token — re-enter it afterwards\n" +
            "• the watch pairing and its keys — re-pair the watch\n\n" +
            "Restarts; reconnect the server to re-download history",
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
            // The only irreversible act in Settings, so it gets the vocabulary's heaviest pattern —
            // the same Commit a written dose gets, because both are things that cannot be taken back.
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

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private val dataBackup = SettingsKnob(
    id = "data.backup",
    screen = SettingsScreenKey.DATA,
    section = "Backup",
    label = "Open Backup",
    subtitle = "The backup panel — archive, restore, and automatic backups",
    synonyms = listOf("backup", "restore", "archive", "save", "copy"),
)

// The export/import knobs now name the BACKUP screen, so a search for either lands on the panel
// that owns them rather than on the reset screen they used to sit beside.

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

/** The Backup panel's entries. They are declared here rather than in `:feature:backup` because the
 *  index is this module's, and a search hit resolves to a route in `:app` either way. */
internal val settingsBackupKnobs = listOf(backupExport, backupImport, backupAuto)
