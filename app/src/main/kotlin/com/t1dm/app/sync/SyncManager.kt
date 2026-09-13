package com.t1dm.app.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.sync.DrainResult
import com.t1dm.sync.QueueDrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Drain loop on io; drainNow serialised with grid tick; never blocks alarms (§2.3, §3.6-A). */
class SyncManager(
    private val drainer: QueueDrainer,
    private val dispatchers: T1dmDispatchers,
    private val drainIntervalMs: Long = 60_000L,
) {
    private val _nightscoutError = MutableStateFlow<String?>(null)

    /** Process-scoped, never persisted. */
    val nightscoutError: StateFlow<String?> = _nightscoutError.asStateFlow()

    fun launch(scope: CoroutineScope) {
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
        _nightscoutError.update { nextNightscoutError(it, result) }
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

/** Held until a row lands: backed-off rows leave most passes with nothing due and no error. */
internal fun nextNightscoutError(previous: String?, result: DrainResult): String? = when {
    result.nightscoutError != null -> result.nightscoutError
    result.sent > 0 -> null
    else -> previous
}
