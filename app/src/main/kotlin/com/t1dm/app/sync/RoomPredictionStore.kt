package com.t1dm.app.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.T1dmRepository
import com.t1dm.inference.PredictionStore
import com.t1dm.sync.PredictionWriteDto
import com.t1dm.sync.StreamClient
import com.t1dm.sync.toWrite
import timber.log.Timber

/** No queue/retry: unsent is lost, never stale; table first; finite line+fan only (§3.6-B). */
class RoomPredictionStore(
    private val repository: T1dmRepository,
    private val stream: StreamClient,
    private val status: SyncStatusStore,
) : PredictionStore {

    override suspend fun persist(cycleTsMs: Long, predictions: List<ModelPrediction>) {
        val now = System.currentTimeMillis()
        repository.upsertPredictions(predictions, now)

        val finite = predictions.filter { p ->
            p.medianBg.all(Double::isFinite) && p.bandsMgdl.all(Double::isFinite)
        }
        for (p in finite) {
            runCatching {
                val dto = p.toWrite(cycleTsMs, now)
                val frame = stream.sendPrediction(dto)
                status.onForecastFrame(p.modelId, frame.bytes, frame.delivered)
                // Held even undelivered; a re-send offers an older frame only on DELIVERED.
                latest = dto
                latestAtMs = now
            }.onFailure { Timber.tag(TAG).w(it, "forecast frame failed (table already updated)") }
        }
    }

    /** Called on every reconnect; older than [MAX_RESEND_AGE_MS] drops — can't mark a fan old. */
    suspend fun resendLatest() {
        val dto = latest ?: return
        if (System.currentTimeMillis() - latestAtMs > MAX_RESEND_AGE_MS) return
        runCatching {
            val frame = stream.sendPrediction(dto)
            status.onForecastFrame(dto.model_id, frame.bytes, frame.delivered)
        }.onFailure { Timber.tag(TAG).w(it, "forecast re-send failed") }
    }

    override suspend fun loadLast(): List<ModelPrediction>? = repository.latestCyclePredictions()

    /** Last frame offered to the socket, for reconnect re-send. Process-local, never persisted. */
    @Volatile
    private var latest: PredictionWriteDto? = null

    @Volatile
    private var latestAtMs: Long = 0L

    private companion object {
        const val TAG = "ForecastStream"

        /** One cycle plus slack. */
        const val MAX_RESEND_AGE_MS = 6 * 60_000L
    }
}
