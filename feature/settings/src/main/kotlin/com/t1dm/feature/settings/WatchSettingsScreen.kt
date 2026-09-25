package com.t1dm.feature.settings

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics

/** Pairing, SAS, key rotation and unpair live in the Security panel; this only routes there. */
@Composable
fun WatchSettingsScreen(
    /** (advertised name, link phase), one per pairing. */
    devices: List<Pair<String, String>>,
    onOpenSecurity: () -> Unit = {},
) {
    SettingsScaffold(SettingsScreenKey.WATCH) {
        Text("Glance every 5 min, encrypted", style = MaterialTheme.typography.bodyMedium)
        if (devices.isEmpty()) Text("Nothing paired", style = MaterialTheme.typography.bodyLarge)
        devices.forEach { (name, phase) -> Text("$name: $phase", style = MaterialTheme.typography.bodyLarge) }

        val haptics = rememberT1dmHaptics()
        SettingsAnchor(watchPairing) {
            Button(
                onClick = { haptics.perform(HapticEvent.NavSwitch); onOpenSecurity() },
            ) { Text("Pairing & keys (Security panel) →") }
        }
    }
}

private val watchPairing = SettingsKnob(
    id = "watch.pairing",
    screen = SettingsScreenKey.WATCH,
    section = "Watch",
    label = "Pairing & keys",
    subtitle = "Paired devices: pair, compare the SAS, rotate keys, unpair (Security panel)",
    synonyms = listOf(
        "watch", "wrist", "esp32", "esp32-c3", "accessory", "pair", "pairing", "unpair", "bond",
        "keys", "key rotation", "sas", "encryption", "x25519", "aes", "glance", "wearable",
        "security", "crypto", "desktop", "wallpaper", "kde", "peripheral",
    ),
)

internal val settingsWatchKnobs = listOf(watchPairing)
