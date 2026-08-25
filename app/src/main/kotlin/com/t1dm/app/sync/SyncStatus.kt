package com.t1dm.app.sync

import com.t1dm.sync.DrainResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class WsConnState { DISCONNECTED, CONNECTED, RECONNECTING }

/** One `model_id`'s frame fates, this process lifetime. [lastSentMs] is rendered as an age. */
data class ForecastStreamStat(
    val sent: Long,
    val dropped: Long,
    val lastSentMs: Long?,
    val lastBytes: Int,
)

/** Process-scoped and never persisted; a restart zeroes it, the durable outbox survives. */
data class SyncStatus(
    val outboxDepth: Int = 0,
    /** Enqueue time of the oldest queued row; null when the outbox is empty. */
    val oldestCreatedAtMs: Long? = null,
    val lastDrain: DrainResult? = null,
    val wsState: WsConnState = WsConnState.DISCONNECTED,
    val wsCursor: Long? = null,
    val lastAlert: String? = null,
    val alertCount: Long = 0,
    val forecastStream: Map<String, ForecastStreamStat> = emptyMap(),
)

/** Copy-on-write updates; safe to touch from the several IO coroutines that drive it. */
class SyncStatusStore {
    private val _state = MutableStateFlow(SyncStatus())
    val state: StateFlow<SyncStatus> = _state.asStateFlow()

    fun onDrain(result: DrainResult, oldestCreatedAtMs: Long?) = _state.update {
        it.copy(lastDrain = result, outboxDepth = result.remaining, oldestCreatedAtMs = oldestCreatedAtMs)
    }

    fun onDepth(depth: Int, oldestCreatedAtMs: Long?) = _state.update {
        it.copy(outboxDepth = depth, oldestCreatedAtMs = oldestCreatedAtMs)
    }

    fun onWs(wsState: WsConnState, cursor: Long?) = _state.update {
        it.copy(wsState = wsState, wsCursor = cursor ?: it.wsCursor)
    }

    fun onAlert(label: String) = _state.update {
        it.copy(lastAlert = label, alertCount = it.alertCount + 1)
    }

    /** [delivered] false means no live socket, or a full outgoing buffer. */
    fun onForecastFrame(modelId: String, bytes: Int, delivered: Boolean) = _state.update { s ->
        val merged = s.forecastStream.toMutableMap()
        val cur = merged[modelId] ?: ForecastStreamStat(0, 0, null, 0)
        merged[modelId] = if (delivered) {
            cur.copy(sent = cur.sent + 1, lastSentMs = System.currentTimeMillis(), lastBytes = bytes)
        } else {
            cur.copy(dropped = cur.dropped + 1, lastBytes = bytes)
        }
        s.copy(forecastStream = merged)
    }
}
