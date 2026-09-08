package com.t1dm.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import org.json.JSONObject

/** Token write-only: blank on save keeps stored. Scanned tokens go to TokenStore, never the DB. */
@Composable
fun ServerSettingsScreen(
    initialLabel: String,
    initialBaseUrl: String,
    hasToken: Boolean,
    isActive: Boolean,
    busy: Boolean,
    healthStatus: String?,
    syncStatus: String?,
    onSave: (label: String, baseUrl: String, token: String) -> Unit,
    onHealthCheck: () -> Unit,
    onSyncModels: () -> Unit,
) {
    var label by remember(initialLabel) { mutableStateOf(initialLabel) }
    var baseUrl by remember(initialBaseUrl) { mutableStateOf(initialBaseUrl) }
    var token by remember { mutableStateOf("") }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    val haptics = rememberT1dmHaptics()

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw == null) {
            haptics.perform(HapticEvent.Reject)
            scanMessage = "Scan cancelled — type the token instead."
            return@rememberLauncherForActivityResult
        }
        when (val parsed = parseServerQr(raw)) {
            is ServerQrPayload.Invalid -> {
                haptics.perform(HapticEvent.Reject)
                scanMessage = "No usable token — ${parsed.reason}"
            }
            is ServerQrPayload.Valid -> {
                haptics.perform(HapticEvent.Confirm)
                token = parsed.token
                if (!parsed.baseUrl.isNullOrBlank()) baseUrl = parsed.baseUrl
                scanMessage = "Scanned a token${if (!parsed.baseUrl.isNullOrBlank()) " and base URL" else ""} — Save + activate."
            }
        }
    }

    SettingsScaffold(SettingsScreenKey.SERVER) {
        Text(
            if (isActive) "active profile" else "no active profile",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasToken) {
            SettingsNote("Enter your rw token; saving re-downloads history")
        }

        SettingsAnchor(serverLabel) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SettingsAnchor(serverBaseUrl) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL (e.g. http://127.0.0.1:8443)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SettingsAnchor(serverToken) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(if (hasToken) "rw token (stored — blank keeps it)" else "rw token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SettingsAnchor(serverScanQr) {
            OutlinedButton(
                onClick = {
                    haptics.perform(HapticEvent.Tap)
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
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAnchor(serverSave, modifier = Modifier) {
                // Confirm, not Commit: the heaviest pattern is reserved for the clinical record.
                Button(
                    onClick = {
                        haptics.perform(HapticEvent.Confirm)
                        onSave(label.trim(), baseUrl.trim(), token.trim())
                    },
                    enabled = !busy && baseUrl.isNotBlank(),
                ) { Text("Save + activate") }
            }
            SettingsAnchor(serverHealthCheck, modifier = Modifier) {
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onHealthCheck() },
                    enabled = !busy,
                ) {
                    Text(if (busy) "checking…" else "Health check")
                }
            }
        }

        if (healthStatus != null) {
            Text("Status", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            Text(healthStatus, style = MaterialTheme.typography.bodyMedium)
        }

        // A new model is adopted at the next discovery; an update to the running one is staged.
        SettingsAnchor(serverSyncModels) {
            OutlinedButton(
                onClick = { haptics.perform(HapticEvent.Tap); onSyncModels() },
                enabled = !busy && isActive,
            ) {
                Text(if (busy) "syncing…" else "Sync models from server")
            }
            if (syncStatus != null) {
                Text("Models", style = MaterialTheme.typography.labelMedium)
                Text(syncStatus, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

sealed interface ServerQrPayload {
    data class Valid(val token: String, val baseUrl: String?) : ServerQrPayload
    data class Invalid(val reason: String) : ServerQrPayload
}

/** JSON {token}+baseUrl/url/base_url, or QR addr+port; else bare token, empty ⇒ Invalid. */
fun parseServerQr(raw: String): ServerQrPayload {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ServerQrPayload.Invalid("it was empty")
    if (trimmed.startsWith("{")) {
        val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return ServerQrPayload.Invalid("not valid JSON")
        val token = obj.optString("token").trim()
        if (token.isEmpty()) return ServerQrPayload.Invalid("no \"token\" field")
        val baseUrl = sequenceOf("baseUrl", "url", "base_url")
            .map { obj.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: obj.optString("addr").trim().takeIf { it.isNotEmpty() }?.let { addr ->
                // Tailscale makes transport TLS moot ⇒ plain http.
                val port = obj.optInt("port", 0)
                if (port in 1..65535) "http://$addr:$port" else "http://$addr"
            }
        return ServerQrPayload.Valid(token, baseUrl)
    }
    return ServerQrPayload.Valid(trimmed, null)
}

private const val SERVER_SECTION = "Server profile"

private val serverLabel = SettingsKnob(
    id = "server.label",
    screen = SettingsScreenKey.SERVER,
    section = SERVER_SECTION,
    label = "Label",
    subtitle = "A name for this server profile",
    synonyms = listOf("label", "name", "profile", "title", "server"),
)

private val serverBaseUrl = SettingsKnob(
    id = "server.base_url",
    screen = SettingsScreenKey.SERVER,
    section = SERVER_SECTION,
    label = "Base URL",
    subtitle = "Where T1DMSERVER lives, e.g. http://127.0.0.1:8443",
    synonyms = listOf(
        "url", "base url", "address", "host", "hostname", "ip", "port", "endpoint", "server",
        "sync", "tailscale", "http", "backend",
    ),
)

private val serverToken = SettingsKnob(
    id = "server.token",
    screen = SettingsScreenKey.SERVER,
    section = SERVER_SECTION,
    label = "rw token",
    subtitle = "The read-write credential; stored in the Keystore and never shown again",
    synonyms = listOf(
        "token", "rw token", "credential", "password", "secret", "key", "auth", "authentication",
        "login", "api key", "keystore",
    ),
)

private val serverScanQr = SettingsKnob(
    id = "server.scan_qr",
    screen = SettingsScreenKey.SERVER,
    section = SERVER_SECTION,
    label = "Scan QR",
    subtitle = "Read the token (and base URL) off the server's login QR with the camera",
    synonyms = listOf("qr", "qr code", "scan", "camera", "barcode", "zxing", "pair", "token"),
)

private val serverSave = SettingsKnob(
    id = "server.save",
    screen = SettingsScreenKey.SERVER,
    section = SERVER_SECTION,
    label = "Save + activate",
    subtitle = "Make this the active profile; a saved token re-downloads history",
    synonyms = listOf("save", "activate", "apply", "connect", "commit", "enable", "profile"),
)

private val serverHealthCheck = SettingsKnob(
    id = "server.health_check",
    screen = SettingsScreenKey.SERVER,
    section = SERVER_SECTION,
    label = "Health check",
    subtitle = "Probe the configured server and report what came back",
    synonyms = listOf("health", "check", "ping", "test", "probe", "reachable", "connection", "status", "diagnose"),
)

private val serverSyncModels = SettingsKnob(
    id = "server.sync_models",
    screen = SettingsScreenKey.SERVER,
    section = SERVER_SECTION,
    label = "Sync models from server",
    subtitle = "Fetch the served model registry into the app's models directory",
    synonyms = listOf(
        "sync", "download", "fetch", "models", "registry", "update model", "pull", "refresh",
        "new model", "weights",
    ),
)

internal val settingsServerKnobs = listOf(
    serverLabel,
    serverBaseUrl,
    serverToken,
    serverScanQr,
    serverSave,
    serverHealthCheck,
    serverSyncModels,
)
