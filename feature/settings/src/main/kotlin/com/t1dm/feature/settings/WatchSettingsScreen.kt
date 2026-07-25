package com.t1dm.feature.settings

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics

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
    SettingsScaffold(SettingsScreenKey.WATCH) {
        Text(
            "Optional ESP32-C3 glance, pushed every 5 min, encrypted",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Status: $linkStatus", style = MaterialTheme.typography.bodyLarge)
        deviceName?.let { Text("Paired to: $it", style = MaterialTheme.typography.bodyMedium) }

        val haptics = rememberT1dmHaptics()
        SettingsAnchor(watchPairing) {
            Button(
                onClick = { haptics.perform(HapticEvent.NavSwitch); onOpenSecurity() },
            ) { Text("Pairing & keys (Security panel) →") }
        }
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private val watchPairing = SettingsKnob(
    id = "watch.pairing",
    screen = SettingsScreenKey.WATCH,
    section = "Watch",
    label = "Pairing & keys",
    subtitle = "The ESP32-C3 wrist glance: pair, compare the SAS, rotate keys, unpair (Security panel)",
    synonyms = listOf(
        "watch", "wrist", "esp32", "esp32-c3", "accessory", "pair", "pairing", "unpair", "bond",
        "keys", "key rotation", "sas", "encryption", "x25519", "aes", "glance", "wearable",
        "security", "crypto",
    ),
)

internal val settingsWatchKnobs = listOf(watchPairing)
