package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Settings → Backup & restore config (PLAN.private.md Phase 7C item 17). Exports every setting to a
 * user-chosen JSON file via the Storage Access Framework, and imports one back. Only configuration
 * keys are written to the file — never secrets (the rw token lives in the Keystore) and never runtime
 * state such as the watch nonce ceilings, so a restore can't cause a security regression. The actual
 * file dialogs are owned by the caller (SAF launchers); this screen only offers the affordances and
 * shows the last result in plain language. Pure/stateless.
 */
@Composable
fun DataSettingsScreen(
    status: String?,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    SettingsScaffold("Backup & restore config") {
        SettingsNote(
            "Save every app setting to a JSON file you can keep or move to another device, and restore " +
                "it later. Your server token and watch keys are never written to the file.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onExport) { Text("Export…") }
            OutlinedButton(onClick = onImport) { Text("Import…") }
        }
        if (status != null) {
            SettingsSectionHeader("Result")
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
