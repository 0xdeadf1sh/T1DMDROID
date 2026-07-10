package com.t1dm.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

/**
 * Settings → Server sub-screen (Phase 3 deliverable 2 + Phase 7C item 12 QR scan).
 * Configures the single active profile — base URL + the `rw` token — with a health-check probe and a
 * status read-out. The token field is write-only: blank on entry (the secret lives in the Keystore,
 * never surfaced) and a blank value on save keeps the stored one.
 *
 * The "Scan QR" affordance opens the ZXing embedded scanner (no Play Services); it accepts either a
 * bare token string or a `{baseUrl|url, token}` JSON object. The scanned token still flows through
 * [onSave] into the Keystore-backed TokenStore, never the DB. Camera-denied / cancelled / malformed
 * QR each produce a plain-language message.
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
    var scanMessage by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw == null) {
            scanMessage = "Scan cancelled (or camera permission denied). You can also type the token."
            return@rememberLauncherForActivityResult
        }
        when (val parsed = parseServerQr(raw)) {
            is ServerQrPayload.Invalid -> scanMessage = "That QR did not contain a usable token — ${parsed.reason}"
            is ServerQrPayload.Valid -> {
                token = parsed.token
                if (!parsed.baseUrl.isNullOrBlank()) baseUrl = parsed.baseUrl
                scanMessage = "Scanned a token${if (!parsed.baseUrl.isNullOrBlank()) " and base URL" else ""}. " +
                    "Review, then Save + activate."
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (isActive) "active profile" else "no active profile",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasToken) {
            Text(
                "Enter your rw token to connect. If you just reset the app, your previous token was " +
                    "erased and must be re-entered — saving it re-downloads your history from the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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

        OutlinedButton(
            onClick = {
                scanMessage = null
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("Scan the server token QR")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    },
                )
            },
        ) { Text("Scan QR") }

        if (scanMessage != null) {
            Text(scanMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

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

/** The two accepted QR payload shapes (item 12): a bare token, or `{baseUrl|url, token}` JSON. */
sealed interface ServerQrPayload {
    data class Valid(val token: String, val baseUrl: String?) : ServerQrPayload
    data class Invalid(val reason: String) : ServerQrPayload
}

/**
 * Parse a scanned QR string. A `{...}` JSON body may carry `token` plus an optional `baseUrl`/`url`;
 * anything else is treated as a bare token. Fail-closed: an empty token yields [ServerQrPayload.Invalid]
 * with a plain reason. Pure — unit-testable without a camera.
 */
fun parseServerQr(raw: String): ServerQrPayload {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ServerQrPayload.Invalid("it was empty.")
    if (trimmed.startsWith("{")) {
        val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return ServerQrPayload.Invalid("it looked like JSON but did not parse.")
        val token = obj.optString("token").trim()
        if (token.isEmpty()) return ServerQrPayload.Invalid("the JSON had no \"token\" field.")
        val baseUrl = sequenceOf("baseUrl", "url", "base_url")
            .map { obj.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }
        return ServerQrPayload.Valid(token, baseUrl)
    }
    return ServerQrPayload.Valid(trimmed, null)
}
