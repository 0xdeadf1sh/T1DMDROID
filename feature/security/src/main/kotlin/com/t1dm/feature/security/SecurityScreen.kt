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
import androidx.compose.runtime.LaunchedEffect
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.T1dmHaptics
import com.t1dm.core.design.fadingEdges
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

/** No raw key bytes reach here: fingerprint, counters and SAS only. One card per pairing. */
@Composable
fun SecurityScreen(
    state: SecurityPanelState = SecurityPanelState(),
    onPair: () -> Unit = {},
    onCancelPairing: () -> Unit = {},
    /** Null for the pairing in progress, else the device whose rotation shows the code. */
    onConfirmSas: (String?) -> Unit = {},
    onRotate: (String) -> Unit = {},
    onUnpair: (String) -> Unit = {},
) {
    val scroll = rememberScrollState()
    val haptics = rememberT1dmHaptics()
    Column(
        Modifier.fillMaxSize().fadingEdges(scroll).verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.devices.forEach { d ->
            DeviceCard(
                d, haptics,
                onConfirmSas = { onConfirmSas(d.id) },
                onRotate = { onRotate(d.id) },
                onUnpair = { onUnpair(d.id) },
            )
        }

        state.pairing?.let { p ->
            DeviceCard(
                p, haptics,
                onConfirmSas = { onConfirmSas(null) },
                onRotate = null,
                onUnpair = null,
                onCancel = onCancelPairing,
            )
        }

        if (state.pairing == null) {
            Button(onClick = { haptics.perform(HapticEvent.Tap); onPair() }) { Text("Pair device") }
        }
    }
}

@Composable
private fun DeviceCard(
    d: WatchPanelDevice,
    haptics: T1dmHaptics,
    onConfirmSas: () -> Unit,
    onRotate: (() -> Unit)?,
    onUnpair: (() -> Unit)?,
    onCancel: (() -> Unit)? = null,
) {
    LaunchedEffect(d.sas) { if (d.sas != null) haptics.perform(HapticEvent.Warn) }
    LaunchedEffect(d.lastError) { if (d.lastError != null) haptics.perform(HapticEvent.Warn) }
    Card(Modifier.fillMaxWidth(), colors = panelCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Kv("Device", d.name ?: "—")
            Kv("Link", d.phase)
            Kv("Records", if (d.extended) "all" else "glance")
            Kv("Session", d.sessionState)
            Kv("Epoch", d.epoch.toString())
            Kv("Key fingerprint", d.keyFingerprint ?: "—")
            Kv("Send nonce (seq)", d.sendSeq.toString())
            Kv("Recv nonce (seq)", d.recvSeq.toString())
            d.lastAckSeq?.let { Kv("Last PUSH_ACK", it.toString()) }
            d.lastPush?.let { Kv("Last push", it) }
            Kv("Signal (RSSI)", d.rssiDbm?.let { "$it dBm" } ?: "no signal")
            if (d.lowPowerSuspended) Kv("Low-power", "pusher suspended")

            d.sas?.let { sas ->
                Text("Compare on the device", style = MaterialTheme.typography.labelLarge)
                Text(
                    sas,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                d.sasWords?.let { Text(it, fontFamily = FontFamily.Monospace) }
            }

            d.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Commit, not Confirm: cannot be walked back without a rotation.
                if (d.canConfirmSas) {
                    Button(onClick = { haptics.perform(HapticEvent.Commit); onConfirmSas() }) { Text("Codes match — confirm") }
                }
                if (d.canRotate && onRotate != null) {
                    OutlinedButton(onClick = { haptics.perform(HapticEvent.Confirm); onRotate() }) { Text("Rotate keys") }
                }
                if (d.canReset && onUnpair != null) {
                    OutlinedButton(onClick = { haptics.perform(HapticEvent.Reject); onUnpair() }) { Text("Unpair") }
                }
                onCancel?.let { cancel ->
                    OutlinedButton(onClick = { haptics.perform(HapticEvent.Reject); cancel() }) { Text("Cancel") }
                }
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

/** Keeps :feature:security free of a :watch dependency; :app maps each link's state onto it. */
data class WatchPanelDevice(
    /** STATUS device_id; empty on a pairing that has not read it yet. */
    val id: String = "",
    val name: String? = null,
    val phase: String = "unpaired",
    val extended: Boolean = false,
    val sessionState: String = "unpaired",
    val epoch: Int = 0,
    val keyFingerprint: String? = null,
    val sendSeq: Long = 0,
    val recvSeq: Long = 0,
    val sas: String? = null,
    val sasWords: String? = null,
    val lastPush: String? = null,
    val lastAckSeq: Long? = null,
    val lowPowerSuspended: Boolean = false,
    val rssiDbm: Int? = null,
    val lastError: String? = null,
    val canConfirmSas: Boolean = false,
    val canRotate: Boolean = false,
    val canReset: Boolean = false,
)

data class SecurityPanelState(
    val devices: List<WatchPanelDevice> = emptyList(),
    /** The pairing in progress; null when none. */
    val pairing: WatchPanelDevice? = null,
)
