package com.t1dm.app.sync

import com.t1dm.feature.network.ForecastStreamRow
import com.t1dm.feature.network.NetworkPanelState
import com.t1dm.sync.DrainResult
import com.t1dm.sync.ServerProfile

fun SyncStatus.toPanelState(
    active: ServerProfile?,
    maxSize: Int,
    maxAgeMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    nightscoutEnabled: Boolean = false,
    nightscoutUrl: String? = null,
): NetworkPanelState = NetworkPanelState(
    hasProfile = active != null,
    profileLabel = active?.label,
    baseUrl = active?.baseUrl,
    outboxDepth = outboxDepth,
    outboxMaxSize = maxSize,
    oldestAgeMs = oldestCreatedAtMs?.let { (nowMs - it).coerceAtLeast(0L) },
    maxAgeMs = maxAgeMs,
    wsState = wsLabel(active),
    wsCursor = wsCursor,
    lastDrain = drainLabel(lastDrain),
    backoff = backoffLabel(lastDrain),
    lastAlert = lastAlert,
    alertCount = alertCount,
    forecastStream = forecastStream.entries
        .sortedBy { it.key }
        .map { (id, s) ->
            ForecastStreamRow(
                modelId = id,
                sent = s.sent,
                dropped = s.dropped,
                lastSentAgeMs = s.lastSentMs?.let { (nowMs - it).coerceAtLeast(0L) },
                lastBytes = s.lastBytes,
            )
        },
    nightscoutEnabled = nightscoutEnabled,
    nightscoutUrl = nightscoutUrl,
    nightscoutError = lastDrain?.nightscoutError,
)

private fun SyncStatus.wsLabel(active: ServerProfile?): String = when {
    active == null -> "no server profile"
    wsState == WsConnState.CONNECTED -> "connected"
    wsState == WsConnState.RECONNECTING -> "reconnecting…"
    else -> "connecting…"
}

private fun drainLabel(r: DrainResult?): String = when (r) {
    null -> "no drain yet"
    else -> "sent ${r.sent}, retried ${r.retried}, dropped ${r.dropped}, evicted ${r.evicted}"
}

private fun backoffLabel(r: DrainResult?): String = when {
    r == null -> "idle"
    r.standDown == DrainResult.StandDown.NO_PROFILE -> "stood down — no active profile"
    r.standDown == DrainResult.StandDown.AUTH -> "stood down — auth failed (check token)"
    r.retried > 0 -> "backing off (${r.retried} row(s) retrying)"
    else -> "idle"
}
