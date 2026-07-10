package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/**
 * Settings → Data: backup/restore of the CONFIG (SAF JSON) plus the DESTRUCTIVE full app reset
 * (Phase 7C item 17 + issue 5). Config export/import writes only configuration keys —
 * never secrets (the rw token lives in the Keystore) and never runtime state such as the watch nonce
 * ceilings — so a restore cannot cause a security regression; the file dialogs are owned by the caller.
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
    SettingsScaffold("Backup & reset") {
        SettingsSectionHeader("Backup & restore config")
        SettingsNote(
            "Save every app setting to a JSON file you can keep or move to another device, and restore " +
                "it later. Your server token and watch keys are never written to the file.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onExport, enabled = !resetting) { Text("Export…") }
            OutlinedButton(onClick = onImport, enabled = !resetting) { Text("Import…") }
        }
        if (status != null) {
            SettingsSectionHeader("Result")
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        SettingsSectionHeader("Danger zone")
        ResetSection(resetting = resetting, onReset = onReset)
    }
}

@Composable
private fun ResetSection(resetting: Boolean, onReset: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    SettingsNote(
        "Erase all data and reset the app to its first-run state. This is permanent and cannot be undone.",
    )

    if (!confirming) {
        OutlinedButton(
            onClick = { typed = ""; confirming = true },
            enabled = !resetting,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Reset app / erase all data") }
        return
    }

    DangerBanner(
        "This ERASES EVERYTHING and cannot be undone:\n" +
            "• all glucose readings, the wide series, carb/bolus/basal logs, meals, journal, and forecasts\n" +
            "• model performance history and accuracy (the downloaded model files themselves are kept)\n" +
            "• every setting, threshold, target range, curve, theme and font — back to defaults\n" +
            "• the server profile AND its saved token — you will need to re-enter your token afterwards\n" +
            "• the watch pairing and its keys — you will need to re-pair the watch\n\n" +
            "After erasing, the app restarts. Reconnect your server to re-download your history.",
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
        OutlinedButton(onClick = { confirming = false; typed = "" }, enabled = !resetting) { Text("Cancel") }
        Button(
            onClick = onReset,
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
            Text("Erasing all data and restarting…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val CONFIRM_WORD = "ERASE"
