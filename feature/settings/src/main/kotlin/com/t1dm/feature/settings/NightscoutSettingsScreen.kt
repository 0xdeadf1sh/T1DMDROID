package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.KeyValueRow
import com.t1dm.core.design.rememberT1dmHaptics

/** One-way mirror into a host speaking the Nightscout `/api/v1` subset. The secret field is
 *  write-only: blank on entry, and a blank value on save keeps the stored one. */
@Composable
fun NightscoutSettingsScreen(
    initialUrl: String,
    hasSecret: Boolean,
    initialEnabled: Boolean,
    busy: Boolean,
    status: String?,
    onSave: (url: String, secret: String, enabled: Boolean) -> Unit,
    onTest: () -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var secret by remember { mutableStateOf("") }
    var enabled by remember(initialEnabled) { mutableStateOf(initialEnabled) }
    val haptics = rememberT1dmHaptics()

    SettingsScaffold(SettingsScreenKey.NIGHTSCOUT) {
        SettingsAnchor(nsEnabled) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Mirror BG, carbs & bolus", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            SettingsNote("One-way; basal and exercise are not sent")
        }

        SettingsAnchor(nsUrl) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SettingsAnchor(nsSecret) {
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(if (hasSecret) "API secret (stored — blank keeps)" else "API secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsNote("Token or its SHA-1 — either works")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAnchor(nsSave, modifier = Modifier) {
                Button(
                    onClick = {
                        haptics.perform(HapticEvent.Confirm)
                        onSave(url.trim(), secret.trim(), enabled)
                    },
                    enabled = !busy && url.isNotBlank(),
                ) { Text("Save") }
            }
            SettingsAnchor(nsTest, modifier = Modifier) {
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onTest() },
                    enabled = !busy,
                ) { Text(if (busy) "testing…" else "Test") }
            }
        }

        if (status != null) {
            KeyValueRow(
                label = "status",
                value = status,
                labelStyle = MaterialTheme.typography.bodyMedium,
                valueStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private const val NS_SECTION = "Nightscout bridge"

private val nsEnabled = SettingsKnob(
    id = "nightscout.enabled",
    screen = SettingsScreenKey.NIGHTSCOUT,
    section = NS_SECTION,
    label = "Mirror BG, carbs & bolus",
    subtitle = "Upload readings and logged events to a Nightscout-compatible host",
    synonyms = listOf(
        "nightscout", "gluroo", "bridge", "mirror", "upload", "share", "xdrip", "sync",
        "third party", "logbook", "export",
    ),
)

private val nsUrl = SettingsKnob(
    id = "nightscout.url",
    screen = SettingsScreenKey.NIGHTSCOUT,
    section = NS_SECTION,
    label = "URL",
    subtitle = "The Nightscout URL the host gave you",
    synonyms = listOf("url", "site", "address", "host", "endpoint", "nightscout", "gluroo"),
)

private val nsSecret = SettingsKnob(
    id = "nightscout.secret",
    screen = SettingsScreenKey.NIGHTSCOUT,
    section = NS_SECTION,
    label = "API secret",
    subtitle = "Stored in the Keystore and never shown again",
    synonyms = listOf(
        "secret", "api secret", "api-secret", "token", "credential", "password", "key", "sha1",
        "sha-1", "auth", "keystore",
    ),
)

private val nsSave = SettingsKnob(
    id = "nightscout.save",
    screen = SettingsScreenKey.NIGHTSCOUT,
    section = NS_SECTION,
    label = "Save",
    subtitle = "Persist the URL and secret and apply the switch",
    synonyms = listOf("save", "apply", "commit", "store"),
)

private val nsTest = SettingsKnob(
    id = "nightscout.test",
    screen = SettingsScreenKey.NIGHTSCOUT,
    section = NS_SECTION,
    label = "Test",
    subtitle = "Probe the host and report what came back",
    synonyms = listOf("test", "probe", "check", "ping", "status", "reachable", "connection", "diagnose"),
)

internal val settingsNightscoutKnobs = listOf(nsEnabled, nsUrl, nsSecret, nsSave, nsTest)
