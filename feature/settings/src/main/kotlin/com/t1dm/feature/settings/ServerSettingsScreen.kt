package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Settings → Server sub-screen (PLAN.private.md Phase 3 deliverable 2). Configures the single active
 * profile — base URL + the `rw` token — with a health-check probe and a status read-out. The token
 * field is write-only: it is blank on entry (the secret lives in the Keystore, never surfaced) and a
 * blank value on save keeps the stored one. For the adb-reverse local case the base URL is
 * `http://127.0.0.1:8443` (Tailscale ⇒ TLS is moot).
 *
 * State is hoisted: the screen holds only the in-progress form text; persistence, activation, and the
 * health probe are `:app` concerns passed as callbacks.
 */
@Composable
fun ServerSettingsScreen(
    initialLabel: String,
    initialBaseUrl: String,
    hasToken: Boolean,
    isActive: Boolean,
    busy: Boolean,
    healthStatus: String?,
    onSave: (label: String, baseUrl: String, token: String) -> Unit,
    onHealthCheck: () -> Unit,
) {
    var label by remember(initialLabel) { mutableStateOf(initialLabel) }
    var baseUrl by remember(initialBaseUrl) { mutableStateOf(initialBaseUrl) }
    var token by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Server", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (isActive) "active profile" else "no active profile",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL (e.g. http://127.0.0.1:8443)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(if (hasToken) "rw token (stored — blank keeps it)" else "rw token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onSave(label.trim(), baseUrl.trim(), token.trim()) },
                enabled = !busy && baseUrl.isNotBlank(),
            ) { Text("Save + activate") }
            OutlinedButton(onClick = onHealthCheck, enabled = !busy) {
                Text(if (busy) "checking…" else "Health check")
            }
        }

        if (healthStatus != null) {
            Text("Status", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            Text(healthStatus, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
