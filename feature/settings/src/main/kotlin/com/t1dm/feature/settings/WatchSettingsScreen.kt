package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings → Watch (SPEC.private.md § Settings — CGM / Server / Watch are the three primary
 * sub-screens; the Watch one first surfaces in Phase 5). The optional ESP32-C3 accessory: the app
 * fully works without it (watch-link.md). Pairing, the SAS comparison, key rotation, and unpair all
 * live in the dedicated Security/Crypto panel; this screen states the link status in plain language
 * and routes there. Pure/stateless.
 */
@Composable
fun WatchSettingsScreen(
    linkStatus: String,
    deviceName: String?,
    onOpenSecurity: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Watch", style = MaterialTheme.typography.headlineSmall)
        Text(
            "An optional ESP32-C3 wrist glance. Every 5 minutes the phone pushes your current BG, " +
                "trend, a one-line forecast, the alert band, and status — encrypted end-to-end. " +
                "The app works fully without it, and the push pauses in battery-saver mode.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Status: $linkStatus", style = MaterialTheme.typography.bodyLarge)
        deviceName?.let { Text("Paired to: $it", style = MaterialTheme.typography.bodyMedium) }

        Button(onClick = onOpenSecurity) { Text("Pairing & keys (Security panel) →") }
    }
}
