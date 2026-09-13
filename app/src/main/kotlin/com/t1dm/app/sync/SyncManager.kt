package com.t1dm.app.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.T1dmRepository
import com.t1dm.sync.QueueDrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** 2 collectors on io; drainNow serialised with grid tick; never blocks alarms (§2.3, §3.6-A). */
class SyncManager(
    private val drainer: QueueDrainer,
    private val repository: T1dmRepository,
    private val status: SyncStatusStore,
    private val dispatchers: T1dmDispatchers,
    private val drainIntervalMs: Long = 60_000L,
) {
    fun launch(scope: CoroutineScope) {
        scope.launch(dispatchers.io) {
            repository.observeOutboxDepth().collect { depth ->
                status.onDepth(depth, repository.oldestOutboxCreatedAt())
            }
        }

        scope.launch(dispatchers.io) {
            while (isActive) {
                drainNow()
                delay(drainIntervalMs)
            }
        }
    }

    /** Safe to call concurrently; the drainer holds a Mutex. */
    suspend fun drainNow() {
        val result = runCatching { drainer.drainOnce() }
            .onFailure { Timber.tag(TAG).w(it, "drain pass failed") }
            .getOrNull() ?: return
        status.onDrain(result, repository.oldestOutboxCreatedAt())
        if (result.sent > 0 || result.dropped > 0 || result.evicted > 0) {
            Timber.tag(TAG).i(
                "drain sent=%d retried=%d dropped=%d evicted=%d remaining=%d",
                result.sent, result.retried, result.dropped, result.evicted, result.remaining,
            )
        }
    }

    private companion object {
        const val TAG = "QueueDrainer"
    }
}
