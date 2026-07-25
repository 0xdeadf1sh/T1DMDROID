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
 * Settings → Data: backup/restore of the CONFIG and the BG panel's DRAWINGS (one SAF JSON file) plus
 * the DESTRUCTIVE full app reset (Phase 7C item 17 + issue 5). The backup writes only configuration
 * keys and the freehand annotation layer — never secrets (the rw token lives in the Keystore) and never
 * runtime state such as the watch nonce ceilings — so a restore cannot cause a security regression. The
 * drawings qualify for the same reason they qualify for nothing else: they are inert decoration that no
 * calculator, model channel, alarm or §3.6 rail reads. The file dialogs are owned by the caller.
 *
 * The reset is IRREVERSIBLE, so it sits behind an explicit two-step, typed confirmation that spells out
 * exactly what is destroyed. Pure/stateless apart from the confirm-flow's local UI state.
 */
@Composable
fun DataSettingsScreen(
    status: String?,
    resetting: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    SettingsScaffold(SettingsScreenKey.DATA) {
        SettingsSectionHeader("Backup & restore")
        SettingsNote("Settings and drawings to JSON; tokens never written")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // A Tap, not a Commit: both merely raise the system file picker — the write happens later,
            // in `:app`, and lands as the `status` line below.
            SettingsAnchor(dataExport, modifier = Modifier) {
                Button(
                    onClick = { haptics.perform(HapticEvent.Tap); onExport() },
                    enabled = !resetting,
                ) { Text("Export…") }
            }
            SettingsAnchor(dataImport, modifier = Modifier) {
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onImport() },
                    enabled = !resetting,
                ) { Text("Import…") }
            }
        }
        if (status != null) {
            SettingsSectionHeader("Result")
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

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
            "• all glucose readings, the wide series, carb/bolus/basal logs, meals, journal, forecasts\n" +
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

private val dataExport = SettingsKnob(
    id = "data.export",
    screen = SettingsScreenKey.DATA,
    section = "Backup & restore",
    label = "Export…",
    subtitle = "Write every setting and the graph's drawings to a JSON file (never secrets)",
    synonyms = listOf(
        "export", "backup", "save", "json", "file", "copy", "transfer", "dump", "download",
        "settings backup", "share",
    ),
)

private val dataImport = SettingsKnob(
    id = "data.import",
    screen = SettingsScreenKey.DATA,
    section = "Backup & restore",
    label = "Import…",
    subtitle = "Restore settings and drawings from a previously exported JSON file",
    synonyms = listOf("import", "restore", "load", "json", "file", "recover", "migrate", "transfer", "settings restore"),
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

internal val settingsDataKnobs = listOf(dataExport, dataImport, dataReset)
