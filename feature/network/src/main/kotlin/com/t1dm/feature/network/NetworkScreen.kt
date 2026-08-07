package com.t1dm.feature.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.fadingEdges

/** One `model_id`'s cumulative `PUT /v1/predictions` push accounting (this process lifetime). */
data class ModelPushRow(val modelId: String, val count: Long, val bytes: Long)

/** One up, non-loopback local interface and its (non-link-local) addresses. Plain read model — the
 *  `:app` layer gathers these from `java.net.NetworkInterface`; this module stays Android-free. */
data class NetIface(val name: String, val addresses: List<String>)

/**
 * A snapshot of the DEVICE's own network posture (issue 2), gathered off-main in `:app` and attached
 * to [NetworkPanelState] by the navigation layer. Purely a read model — no Android types leak here.
 */
data class NetworkDiagnostics(
    val online: Boolean,
    val validated: Boolean,
    val transport: String,
    val metered: Boolean,
    val wifiSsid: String?,
    val wifiRssiDbm: Int?,
    val wifiLevel: Int?,
    val wifiLinkMbps: Int?,
    val wifiFreqMhz: Int?,
    val interfaces: List<NetIface>,
)

/**
 * Everything the Network panel renders (Phase 3 deliverable 6). Transport-typed
 * facts (drain outcome, WS lifecycle, backoff) arrive pre-formatted from `:app`; simple counters
 * arrive numeric so the panel can render them against their configured bounds. Purely a read model.
 */
data class NetworkPanelState(
    val hasProfile: Boolean = false,
    val profileLabel: String? = null,
    val baseUrl: String? = null,
    val outboxDepth: Int = 0,
    val outboxMaxSize: Int = 0,
    val oldestAgeMs: Long? = null,
    val maxAgeMs: Long = 0,
    val wsState: String = "disconnected",
    val wsCursor: Long? = null,
    val lastDrain: String = "no drain yet",
    val backoff: String = "idle",
    val lastAlert: String? = null,
    val alertCount: Long = 0,
    val modelPushes: List<ModelPushRow> = emptyList(),
    val net: NetworkDiagnostics? = null,
)

@Composable
fun NetworkScreen(state: NetworkPanelState = NetworkPanelState()) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .fadingEdges(scroll)
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // N1 — the "Network" title lives in the breadcrumb; no duplicate in-view header.
        // Issue 2 — the DEVICE's own network comes first (online/transport/Wi-Fi/interfaces), gathered
        // off-main by `:app`; before the first snapshot arrives (net == null) the sections read "gathering…".
        val net = state.net
        Section("Connectivity")
        if (net == null) {
            Field("status", "gathering…")
        } else {
            Field("online", yesNo(net.online))
            Field("internet validated", yesNo(net.validated))
            Field("transport", net.transport)
            Field("metered", yesNo(net.metered))
        }

        if (net != null) {
            Section("Wi-Fi")
            Field("signal", wifiSignal(net.wifiRssiDbm, net.wifiLevel))
            Field("link", net.wifiLinkMbps?.let { "$it Mbps" } ?: "—")
            Field("frequency", net.wifiFreqMhz?.let { "$it MHz" } ?: "—")
            Field("SSID", net.wifiSsid ?: "— (needs location)")

            Section("Interfaces")
            if (net.interfaces.isEmpty()) {
                Field("—", "none")
            } else {
                net.interfaces.forEach { iface ->
                    Field(iface.name, iface.addresses.joinToString(", ").ifBlank { "—" })
                }
            }
        }

        Section("Server")
        if (!state.hasProfile) {
            Field("profile", "none — Settings → Server")
        } else {
            Field("profile", state.profileLabel ?: "—")
            Field("base URL", state.baseUrl ?: "—")
        }

        Section("Outbox")
        Field("depth", "${state.outboxDepth} / ${state.outboxMaxSize}")
        Field("oldest", "${age(state.oldestAgeMs)} (bound ${duration(state.maxAgeMs)})")
        Field("last drain", state.lastDrain)
        Field("retry", state.backoff)

        Section("WebSocket")
        Field("state", state.wsState)
        Field("catch-up cursor", state.wsCursor?.toString() ?: "—")

        Section("Predictions pushed")
        if (state.modelPushes.isEmpty()) {
            Field("—", "none pushed yet")
        } else {
            state.modelPushes.forEach { m ->
                Field(m.modelId, "${m.count} push(es) • ${bytes(m.bytes)}")
            }
        }

        Section("Alerts (incoming)")
        Field("count", state.alertCount.toString())
        Field("last", state.lastAlert ?: "—")
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 14.dp),
    )
}

/** N6 — every field now goes through the shared aligned key/value table (fixed-weight columns,
 *  right-aligned tabular-figure values) so labels and values no longer smush together. */
@Composable
private fun Field(label: String, value: String) {
    com.t1dm.core.design.KeyValueRow(
        label = label,
        value = value,
        labelStyle = MaterialTheme.typography.bodyMedium,
        valueStyle = MaterialTheme.typography.bodyMedium,
    )
}

private fun yesNo(b: Boolean): String = if (b) "yes" else "no"

/** e.g. "-57 dBm (4/4)"; "—" when Wi-Fi is off / disconnected (no readable RSSI). */
private fun wifiSignal(rssiDbm: Int?, level: Int?): String = when {
    rssiDbm == null -> "—"
    level == null -> "$rssiDbm dBm"
    else -> "$rssiDbm dBm ($level/4)"
}

private fun age(ms: Long?): String = if (ms == null) "empty" else duration(ms)

private fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}

private fun bytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KiB"
    else -> "${b / (1024 * 1024)} MiB"
}
