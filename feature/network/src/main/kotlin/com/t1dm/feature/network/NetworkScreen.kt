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

/** One `model_id`'s cumulative `PUT /v1/predictions` push accounting (this process lifetime). */
data class ModelPushRow(val modelId: String, val count: Long, val bytes: Long)

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
)

@Composable
fun NetworkScreen(state: NetworkPanelState = NetworkPanelState()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Network", style = MaterialTheme.typography.headlineSmall)

        Section("Server")
        if (!state.hasProfile) {
            Field("profile", "none — configure in Settings → Server")
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

        Section("Predictions pushed (per model)")
        if (state.modelPushes.isEmpty()) {
            Field("—", "no prediction batch pushed yet")
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

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
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
