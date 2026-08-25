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

/** One `model_id`'s forecast-frame liveness, this process lifetime. */
data class ForecastStreamRow(
    val modelId: String,
    val sent: Long,
    val dropped: Long,
    val lastSentAgeMs: Long?,
    val lastBytes: Int,
)

/** An up, non-loopback interface and its non-link-local addresses. */
data class NetIface(val name: String, val addresses: List<String>)

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
    val forecastStream: List<ForecastStreamRow> = emptyList(),
    val net: NetworkDiagnostics? = null,
    val nightscoutEnabled: Boolean = false,
    val nightscoutUrl: String? = null,
    /** The bridge never stands the queue down, so this is the only sign it is failing. */
    val nightscoutError: String? = null,
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

        Section("Nightscout")
        Field("bridge", if (state.nightscoutEnabled) "on" else "off")
        if (state.nightscoutEnabled) {
            Field("URL", state.nightscoutUrl ?: "—")
            Field("last error", state.nightscoutError ?: "none")
        }

        Section("Outbox")
        Field("depth", "${state.outboxDepth} / ${state.outboxMaxSize}")
        Field("oldest", "${age(state.oldestAgeMs)} (bound ${duration(state.maxAgeMs)})")
        Field("last drain", state.lastDrain)
        Field("retry", state.backoff)

        Section("WebSocket")
        Field("state", state.wsState)
        Field("catch-up cursor", state.wsCursor?.toString() ?: "—")

        Section("Forecast stream")
        if (state.forecastStream.isEmpty()) {
            Field("—", "none sent yet")
        } else {
            state.forecastStream.forEach { m ->
                val age = m.lastSentAgeMs?.let { "${it / 60_000L} min ago" } ?: "never"
                Field(m.modelId, "$age • ${m.sent} sent, ${m.dropped} dropped • ${bytes(m.lastBytes.toLong())}")
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
