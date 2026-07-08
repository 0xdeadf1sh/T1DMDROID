package com.t1dm.app.sync

import com.t1dm.sync.DrainResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** WebSocket connection lifecycle as the panel sees it. */
enum class WsConnState { DISCONNECTED, CONNECTED, RECONNECTING }

/** Cumulative `PUT /v1/predictions` push accounting for one `model_id` (this process lifetime). */
data class ModelPushStat(val count: Long, val bytes: Long)

/**
 * The live sync telemetry the Network panel renders (PLAN.private.md Phase 3 deliverable 6). Purely
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
    val modelPushes: Map<String, ModelPushStat> = emptyMap(),
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

    /** Fold one cycle's per-model wire sizes into the running counters. */
    fun onPredictionPush(sizes: Map<String, Int>) = _state.update { s ->
        val merged = s.modelPushes.toMutableMap()
        for ((id, bytes) in sizes) {
            val cur = merged[id] ?: ModelPushStat(0, 0)
            merged[id] = ModelPushStat(cur.count + 1, cur.bytes + bytes)
        }
        s.copy(modelPushes = merged)
    }
}
