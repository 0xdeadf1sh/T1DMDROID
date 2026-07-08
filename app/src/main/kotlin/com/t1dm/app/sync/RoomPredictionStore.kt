package com.t1dm.app.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.T1dmRepository
import com.t1dm.inference.PredictionStore
import com.t1dm.sync.OutboxEnqueuer
import timber.log.Timber

/**
 * The Phase-3 [PredictionStore] that retires the Phase-2 `kv`-blob store: the dedicated `prediction`
 * table is now the source of truth for the dashboard overlay + rehydrate, and every persisted cycle
 * additionally enqueues a single deduped `PUT /v1/predictions` batch (all running models) into the
 * durable outbox (PLAN.private.md Phase 3 deliverable 3).
 *
 * Persist order is table-first: the local store must reflect a cycle even if the enqueue is skipped.
 * Only forecasts whose line + fan are finite are pushed — the JSON encoder rejects NaN/Inf, and a
 * degenerate (`NON_FINITE`) forecast is ineligible to drive anything server-side anyway (§3.6-B).
 */
class RoomPredictionStore(
    private val repository: T1dmRepository,
    private val outbox: OutboxEnqueuer,
    private val status: SyncStatusStore,
) : PredictionStore {

    override suspend fun persist(cycleTsMs: Long, predictions: List<ModelPrediction>) {
        val now = System.currentTimeMillis()
        repository.upsertPredictions(predictions, now)

        val finite = predictions.filter { p ->
            p.medianBg.all(Double::isFinite) && p.bandsMgdl.all(Double::isFinite)
        }
        if (finite.isEmpty()) return
        runCatching {
            outbox.enqueuePredictions(cycleTsMs, finite, now)
            status.onPredictionPush(outbox.predictionWireSizes(finite))
        }.onFailure { Timber.tag(TAG).w(it, "predictions enqueue failed (table already updated)") }
    }

    override suspend fun loadLast(): List<ModelPrediction>? = repository.latestCyclePredictions()

    private companion object {
        const val TAG = "QueueDrainer"
    }
}
