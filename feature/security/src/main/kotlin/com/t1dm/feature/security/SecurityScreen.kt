package com.t1dm.feature.security

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.drawEsp32Watch
import com.t1dm.core.design.panelCardColors
import com.t1dm.core.design.rememberT1dmHaptics
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The Security/Crypto panel (a dedicated section showing BOTH low- and high-level
 * crypto state). Pure/stateless: it renders the mapped [SecurityPanelState] and surfaces the manual
 * pair / confirm-SAS / rotate / unpair actions (manual key rotation + session reset
 * MUST be exposed). The state carries NO raw key bytes — only a truncated fingerprint, counters, and
 * the SAS to compare on both screens.
 */
@Composable
fun SecurityScreen(
    state: SecurityPanelState = SecurityPanelState(),
    onPair: () -> Unit = {},
    onConfirmSas: () -> Unit = {},
    onRotate: () -> Unit = {},
    onUnpair: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The panel leads with a large, still ESP32 wrist device — the accessory this whole screen
        // pairs and secures. A round, merely-lit device; every hue is drawn from the active theme.
        val cs = MaterialTheme.colorScheme
        Canvas(
            Modifier.fillMaxWidth(0.62f).aspectRatio(1f).align(Alignment.CenterHorizontally),
        ) {
            drawEsp32Watch(cs.primary, cs.onSurface)
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = panelCardColors(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Kv("Link", state.phase)
                state.deviceName?.let { Kv("Device", it) }
                Kv("Session", state.sessionState)
                Kv("Epoch", state.epoch.toString())
                Kv("Key fingerprint", state.keyFingerprint ?: "—")
                Kv("Send nonce (seq)", state.sendSeq.toString())
                Kv("Recv nonce (seq)", state.recvSeq.toString())
                state.lastAckSeq?.let { Kv("Last PUSH_ACK", it.toString()) }
                state.lastPush?.let { Kv("Last push", it) }
                Kv("Signal (RSSI)", state.rssiDbm?.let { "$it dBm" } ?: "no signal")
                if (state.lowPowerSuspended) Kv("Low-power", "pusher suspended")
            }
        }

        val haptics = rememberT1dmHaptics()
        // The SAS appearing is the one moment on this page that DEMANDS the user look: the short
        // authentication string is the whole defence against a man-in-the-middle, and it arrives
        // asynchronously, mid-handshake, while they are looking at the watch rather than the phone.
        LaunchedEffect(state.sas) { if (state.sas != null) haptics.perform(HapticEvent.Warn) }
        LaunchedEffect(state.lastError) { if (state.lastError != null) haptics.perform(HapticEvent.Warn) }

        // The SAS to compare on BOTH screens during pairing/rotation.
        if (state.sas != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = panelCardColors(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Compare on your watch", style = MaterialTheme.typography.labelLarge)
                    Text(
                        state.sas,
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    state.sasWords?.let { Text(it, fontFamily = FontFamily.Monospace) }
                }
            }
        }

        state.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.canPair) {
                Button(onClick = { haptics.perform(HapticEvent.Tap); onPair() }) { Text("Pair watch") }
            }
            // Confirming the SAS is the human ASSERTING that two screens agree — the act the whole
            // key exchange is authenticated by. Commit, not Confirm: it cannot be walked back without
            // a rotation.
            if (state.canConfirmSas) {
                Button(
                    onClick = { haptics.perform(HapticEvent.Commit); onConfirmSas() },
                ) { Text("Codes match — confirm") }
            }
            if (state.canRotate) {
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Confirm); onRotate() },
                ) { Text("Rotate keys") }
            }
            // Unpairing throws away the keys — a teardown, hence Reject rather than a neutral Tap.
            if (state.canReset) {
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Reject); onUnpair() },
                ) { Text("Unpair") }
            }
        }
    }
}

@Composable
private fun Kv(key: String, value: String) {
    com.t1dm.core.design.KeyValueRow(
        key, value, numeric = false,
        labelStyle = MaterialTheme.typography.bodyMedium,
        valueStyle = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * A feature-local projection of the watch security state, so `:feature:security` stays free of a
 * `:watch` dependency (the removable seam). `:app` maps `WatchSecurityState` onto this.
 */
data class SecurityPanelState(
    val phase: String = "Unpaired",
    val deviceName: String? = null,
    val sessionState: String = "Unpaired",
    val epoch: Int = 0,
    val keyFingerprint: String? = null,
    val sendSeq: Long = 0,
    val recvSeq: Long = 0,
    val sas: String? = null,
    val sasWords: String? = null,
    val lastPush: String? = null,
    val lastAckSeq: Long? = null,
    val lowPowerSuspended: Boolean = false,
    /** Connected-peripheral signal strength in dBm (periodic readRemoteRssi), or null. */
    val rssiDbm: Int? = null,
    val lastError: String? = null,
    val canPair: Boolean = true,
    val canConfirmSas: Boolean = false,
    val canRotate: Boolean = false,
    val canReset: Boolean = false,
)
