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

/**
 * Settings → Server sub-screen (Phase 3 deliverable 2 + Phase 7C item 12 QR scan).
 * Configures the single active profile — base URL + the `rw` token — with a health-check probe and a
 * status read-out. The token field is write-only: blank on entry (the secret lives in the Keystore,
 * never surfaced) and a blank value on save keeps the stored one.
 *
 * The "Scan QR" affordance opens the ZXing embedded scanner (no Play Services); it accepts either a
 * bare token string, a `{baseUrl|url, token}` JSON object, or the server's own login-QR shape
 * `{type, token, addr, port}` (from which the base URL is composed). The scanned token still flows through
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
        // The scanner returns with the app in the background-to-foreground transition and nothing on
        // screen has moved, so the outcome is easiest to feel: a legible QR confirms, an unusable one
        // is refused.
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
                // Confirm rather than Commit: this persists a profile and a token, but the heaviest
                // pattern stays reserved for writes to the clinical record and for the erase.
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

        // Fetch the served model registry into the app's models dir (product decision 1). A new model is
        // adopted on the next discovery; an update to the running model is staged and surfaced in Models.
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

/** The accepted QR payload shapes (item 12): a bare token, `{baseUrl|url, token}` JSON, or the
 *  server's login QR `{type, token, addr, port}` (base URL composed as `http://addr:port`). */
sealed interface ServerQrPayload {
    data class Valid(val token: String, val baseUrl: String?) : ServerQrPayload
    data class Invalid(val reason: String) : ServerQrPayload
}

/**
 * Parse a scanned QR string. A `{...}` JSON body may carry `token` plus, for the base URL, either an
 * explicit `baseUrl`/`url`/`base_url` or the server login-QR pair `addr`+`port` (composed as
 * `http://addr:port`); anything else is treated as a bare token. Fail-closed: an empty token yields
 * [ServerQrPayload.Invalid] with a plain reason. Pure — unit-testable without a camera.
 */
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
                // The server's login QR renders {type, token, addr, port} with no base URL, so
                // compose one from addr+port. Tailscale makes transport TLS moot ⇒ plain http.
                val port = obj.optInt("port", 0)
                if (port in 1..65535) "http://$addr:$port" else "http://$addr"
            }
        return ServerQrPayload.Valid(token, baseUrl)
    }
    return ServerQrPayload.Valid(trimmed, null)
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

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
