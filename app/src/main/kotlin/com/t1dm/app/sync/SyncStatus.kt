package com.t1dm.app.sync

import com.t1dm.sync.DrainResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-scoped and never persisted; a restart zeroes it, the durable outbox survives. */
data class SyncStatus(
    val outboxDepth: Int = 0,
    /** Enqueue time of the oldest queued row; null when the outbox is empty. */
    val oldestCreatedAtMs: Long? = null,
    val lastDrain: DrainResult? = null,
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
}
