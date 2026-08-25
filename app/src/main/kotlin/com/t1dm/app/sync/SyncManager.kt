package com.t1dm.app.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.T1dmRepository
import com.t1dm.sync.CatchUpCoordinator
import com.t1dm.sync.QueueDrainer
import com.t1dm.sync.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Three long-lived collectors, all on [T1dmDispatchers.io]. [drainNow] is also called from the
 *  5-min grid tick; the drainer serialises passes. Never blocks the alarm path (§2.3, §3.6-A). */
class SyncManager(
    private val drainer: QueueDrainer,
    private val catchUp: CatchUpCoordinator,
    private val repository: T1dmRepository,
    private val status: SyncStatusStore,
    private val dispatchers: T1dmDispatchers,
    /** Nothing stores a forecast, so a receiver that came up after the last cycle has none. */
    private val resendForecast: suspend () -> Unit = {},
    private val drainIntervalMs: Long = 60_000L,
) {
    fun launch(scope: CoroutineScope) {
        scope.launch(dispatchers.io) {
            repository.observeOutboxDepth().collect { depth ->
                status.onDepth(depth, repository.oldestOutboxCreatedAt())
            }
        }

        scope.launch(dispatchers.io) {
            catchUp.events().collect { ev ->
                when (ev) {
                    is StreamEvent.Connected -> {
                        status.onWs(WsConnState.CONNECTED, null)
                        resendForecast()
                        drainNow()
                    }
                    is StreamEvent.Reconnected -> {
                        status.onWs(WsConnState.CONNECTED, ev.cursor)
                        resendForecast()
                        drainNow()
                    }
                    is StreamEvent.Disconnected -> status.onWs(WsConnState.RECONNECTING, null)
                    is StreamEvent.Alert -> {
                        status.onAlert("${ev.kind} @ ${ev.ts}")
                        Timber.tag(TAG).i("stream alert kind=%s ts=%d", ev.kind, ev.ts)
                    }
                    is StreamEvent.Sample -> Unit // folded into `sample` by the coordinator
                }
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
        if (result.sent > 0 || result.dropped > 0 || result.evicted > 0 || result.standDown != null) {
            Timber.tag(TAG).i(
                "drain sent=%d retried=%d dropped=%d evicted=%d remaining=%d standDown=%s",
                result.sent, result.retried, result.dropped, result.evicted, result.remaining, result.standDown,
            )
        }
    }

    private companion object {
        const val TAG = "QueueDrainer"
    }
}
