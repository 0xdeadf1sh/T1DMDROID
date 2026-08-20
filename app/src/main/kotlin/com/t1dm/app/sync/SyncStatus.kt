package com.t1dm.app.sync

import com.t1dm.sync.DrainResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** WebSocket connection lifecycle as the panel sees it. */
enum class WsConnState { DISCONNECTED, CONNECTED, RECONNECTING }

/**
 * Forecast-frame liveness for one `model_id`, this process lifetime.
 *
 * Not a push count. A cumulative count described durable outbox rows the queue was accountable for;
 * nothing is accountable for a forecast frame at contract 0.5.0 — there is no queue behind it and
 * no retry — so what can honestly be reported is whether the last cycle's forecast reached the
 * socket and how long ago. [lastSentMs] is rendered as an AGE for that reason.
 */
data class ForecastStreamStat(
    val sent: Long,
    val dropped: Long,
    val lastSentMs: Long?,
    val lastBytes: Int,
)

/**
 * The live sync telemetry the Network panel renders (Phase 3 deliverable 6). Purely
 * observational and process-scoped — it is rebuilt from the outbox/stream on each launch, never
 * persisted — so a restart simply zeroes the counters while the durable outbox itself survives.
 */
data class SyncStatus(
    val outboxDepth: Int = 0,
    /** Enqueue time of the oldest queued row (null = empty); the panel renders it as an age. */
    val oldestCreatedAtMs: Long? = null,
    val lastDrain: DrainResult? = null,
    val wsState: WsConnState = WsConnState.DISCONNECTED,
    val wsCursor: Long? = null,
    val lastAlert: String? = null,
    val alertCount: Long = 0,
    val forecastStream: Map<String, ForecastStreamStat> = emptyMap(),
)

/**
 * Single-writer holder the [SyncManager], the [RoomPredictionStore], and the stream collector fold
 * their observations into; the Network panel observes [state]. Every mutation is a copy-on-write
 * `update`, so it is safe to touch from the several IO coroutines that drive it.
 */
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

    /** Record one forecast frame's fate. [delivered] false means no live socket, or a full
     *  outgoing buffer — the whole failure model, with nothing behind it. */
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
