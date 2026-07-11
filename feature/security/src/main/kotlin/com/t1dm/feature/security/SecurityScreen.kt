package com.t1dm.feature.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The Security/Crypto panel (ux-decisions.md — a dedicated section showing BOTH low- and high-level
 * crypto state). Pure/stateless: it renders the mapped [SecurityPanelState] and surfaces the manual
 * pair / confirm-SAS / rotate / unpair actions (watch-link.md — manual key rotation + session reset
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
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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

        // The SAS to compare on BOTH screens during pairing/rotation.
        if (state.sas != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Compare this code on your watch", style = MaterialTheme.typography.labelLarge)
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
            if (state.canPair) Button(onClick = onPair) { Text("Pair watch") }
            if (state.canConfirmSas) Button(onClick = onConfirmSas) { Text("Codes match — confirm") }
            if (state.canRotate) OutlinedButton(onClick = onRotate) { Text("Rotate keys") }
            if (state.canReset) OutlinedButton(onClick = onUnpair) { Text("Unpair") }
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
