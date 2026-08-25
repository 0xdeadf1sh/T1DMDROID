package com.t1dm.app.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.T1dmRepository
import com.t1dm.inference.PredictionStore
import com.t1dm.sync.PredictionWriteDto
import com.t1dm.sync.StreamClient
import com.t1dm.sync.toWrite
import timber.log.Timber

/** No queue and no retry behind a forecast: lost if no socket takes it, never replayed stale. Table
 *  first, so the local store reflects a cycle even when nothing is sent; only a finite line + fan is
 *  offered (§3.6-B). */
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
                // Held even when undelivered: on DELIVERED only, a re-send after an outage would
                // offer a frame older than this one.
                latest = dto
                latestAtMs = now
            }.onFailure { Timber.tag(TAG).w(it, "forecast frame failed (table already updated)") }
        }
    }

    /**
     * Called on every stream (re)connect. A frame older than [MAX_RESEND_AGE_MS] is dropped: the
     * receiver draws what arrives as the current fan and cannot mark one as old.
     */
    suspend fun resendLatest() {
        val dto = latest ?: return
        if (System.currentTimeMillis() - latestAtMs > MAX_RESEND_AGE_MS) return
        runCatching {
            val frame = stream.sendPrediction(dto)
            status.onForecastFrame(dto.model_id, frame.bytes, frame.delivered)
        }.onFailure { Timber.tag(TAG).w(it, "forecast re-send failed") }
    }

    override suspend fun loadLast(): List<ModelPrediction>? = repository.latestCyclePredictions()

    /** Last frame offered to the socket, for the reconnect re-send. Process-local, never persisted. */
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
