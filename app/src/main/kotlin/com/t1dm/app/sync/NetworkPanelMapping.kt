package com.t1dm.app.sync

import com.t1dm.feature.network.NetworkPanelState
import com.t1dm.sync.DrainResult

fun SyncStatus.toPanelState(
    maxSize: Int,
    maxAgeMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    nightscoutEnabled: Boolean = false,
    nightscoutUrl: String? = null,
): NetworkPanelState = NetworkPanelState(
    outboxDepth = outboxDepth,
    outboxMaxSize = maxSize,
    oldestAgeMs = oldestCreatedAtMs?.let { (nowMs - it).coerceAtLeast(0L) },
    maxAgeMs = maxAgeMs,
    lastDrain = drainLabel(lastDrain),
    backoff = backoffLabel(lastDrain),
    nightscoutEnabled = nightscoutEnabled,
    nightscoutUrl = nightscoutUrl,
    nightscoutError = lastDrain?.nightscoutError,
)

private fun drainLabel(r: DrainResult?): String = when (r) {
    null -> "no drain yet"
    else -> "sent ${r.sent}, retried ${r.retried}, dropped ${r.dropped}, evicted ${r.evicted}"
}

private fun backoffLabel(r: DrainResult?): String = when {
    r == null -> "idle"
    r.retried > 0 -> "backing off (${r.retried} row(s) retrying)"
    else -> "idle"
}
