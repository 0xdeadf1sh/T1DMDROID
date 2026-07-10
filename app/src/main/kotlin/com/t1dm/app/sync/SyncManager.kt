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

/**
 * The `:app` sync orchestrator (Phase 3 deliverables 3–4, 6–7). It binds the durable
 * outbox drainer, the WebSocket catch-up coordinator, and the Network-panel telemetry into the
 * always-on foreground service scope. Three long-lived collectors, all on [T1dmDispatchers.io]:
 *
 *  1. live outbox depth → panel (INGEST/PREDICTIONS enqueue-on-write is invisible to a drain);
 *  2. the merged `/v1/stream` — samples fold into the wide table via LWW inside the coordinator,
 *     `Reconnected` triggers the REST catch-up, alerts are surfaced, and a (re)connect kicks a drain;
 *  3. an opportunistic periodic drain, the responsive complement to the deferrable WorkManager job.
 *
 * [drainNow] is also invoked from the 5-min grid tick, so a fresh INGEST/PREDICTIONS row is flushed
 * promptly when the tailnet is up. The drainer serialises passes internally, so overlapping callers
 * are safe. Nothing here blocks the model-free alarm path or the inference cycle (§2.3, §3.6-A).
 */
class SyncManager(
    private val drainer: QueueDrainer,
    private val catchUp: CatchUpCoordinator,
    private val repository: T1dmRepository,
    private val status: SyncStatusStore,
    private val dispatchers: T1dmDispatchers,
    private val drainIntervalMs: Long = 60_000L,
) {
    fun launch(scope: CoroutineScope) {
        // 1) Live depth + oldest-age → panel, independent of drains.
        scope.launch(dispatchers.io) {
            repository.observeOutboxDepth().collect { depth ->
                status.onDepth(depth, repository.oldestOutboxCreatedAt())
            }
        }

        // 2) WS stream: LWW merge + catch-up (inside the coordinator) + panel state + drain on connect.
        scope.launch(dispatchers.io) {
            catchUp.events().collect { ev ->
                when (ev) {
                    is StreamEvent.Connected -> { status.onWs(WsConnState.CONNECTED, null); drainNow() }
                    is StreamEvent.Reconnected -> { status.onWs(WsConnState.CONNECTED, ev.cursor); drainNow() }
                    is StreamEvent.Disconnected -> status.onWs(WsConnState.RECONNECTING, null)
                    is StreamEvent.Alert -> {
                        status.onAlert("${ev.kind} @ ${ev.ts}")
                        Timber.tag(TAG).i("stream alert kind=%s ts=%d", ev.kind, ev.ts)
                    }
                    is StreamEvent.Sample -> Unit // folded into `sample` by the coordinator
                }
            }
        }

        // 3) Opportunistic periodic drain (WorkManager is the deferrable fallback).
        scope.launch(dispatchers.io) {
            while (isActive) {
                drainNow()
                delay(drainIntervalMs)
            }
        }
    }

    /** One drain pass; refreshes the panel with the outcome. Safe to call concurrently (drainer Mutex). */
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
