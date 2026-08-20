package com.t1dm.app.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.T1dmRepository
import com.t1dm.inference.PredictionStore
import com.t1dm.sync.PredictionWriteDto
import com.t1dm.sync.StreamClient
import com.t1dm.sync.toWrite
import timber.log.Timber

/**
 * The [PredictionStore]: the dedicated `prediction` table is the source of truth for the dashboard
 * overlay, the hindsight sweep, the accuracy suite and the calculator, and every persisted cycle
 * additionally offers its forecasts to the open stream.
 *
 * **Only the push changed.** At contract 0.5.0 a forecast has no REST route and nothing stores one
 * server-side: it goes up the WebSocket as an inbound frame, the server fans it out and draws it,
 * and it is gone when the socket closes. There is no queue behind it and no retry — a forecast that
 * finds no socket is lost, and the next cycle is at most five minutes away. Replaying a stale one
 * would have the operator console draw a twenty-minute-old fan as current, which is worse than
 * drawing none.
 *
 * Persist order is table-first: the local store must reflect a cycle even if nothing is sent. Only
 * forecasts whose line + fan are finite are offered — the JSON encoder rejects NaN/Inf, and a
 * degenerate (`NON_FINITE`) forecast is ineligible to drive anything anyway (§3.6-B).
 *
 * One frame per model rather than a batch, so a full outgoing buffer costs one model's forecast
 * rather than every model's.
 */
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
                // Held whether or not it reached the socket. Keeping only DELIVERED frames means
                // that after an outage the re-send offers the last frame from BEFORE it, in
                // preference to the current fan sitting right here — which is the stale draw this
                // class's own contract calls worse than none.
                latest = dto
                latestAtMs = now
            }.onFailure { Timber.tag(TAG).w(it, "forecast frame failed (table already updated)") }
        }
    }

    /**
     * Re-send the most recent forecast — the contract's obligation on every stream (re)connect.
     *
     * Without it a receiver draws nothing for up to a whole cycle after any restart, reconnect or
     * wedged socket, and cannot tell that from a model that withheld a degenerate forecast. One
     * frame closes the window. Nothing held means nothing sent.
     *
     * A frame older than [MAX_RESEND_AGE_MS] is dropped rather than re-sent. The console draws what
     * arrives as the current fan and has no way to mark one as old, so past about a cycle a re-send
     * stops closing a window and starts asserting something false.
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

    /** The last frame OFFERED to the socket, kept for the reconnect re-send. Process-local: a
     *  forecast is ephemeral, so there is nothing to persist and nothing to persist it for. */
    @Volatile
    private var latest: PredictionWriteDto? = null

    @Volatile
    private var latestAtMs: Long = 0L

    private companion object {
        const val TAG = "ForecastStream"

        /** One cycle plus slack. Past this the held frame is not a window being closed. */
        const val MAX_RESEND_AGE_MS = 6 * 60_000L
    }
}
