package com.t1dm.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.OutboxSink
import com.t1dm.data.db.OutboxKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Eviction priority (PLAN.private.md Phase 3): `ALERT > NOTE > INGEST > PREDICTIONS > SERIES >
 * PHOTO`. Higher rank survives; when the queue is over its size bound the lowest-rank, oldest rows
 * are dropped first. An ALERT (a safety signal) is the last thing ever evicted; a stale display
 * PREDICTION or a PHOTO is the first.
 */
internal val OutboxKind.priority: Int
    get() = when (this) {
        OutboxKind.ALERT -> 5
        OutboxKind.NOTE -> 4
        OutboxKind.INGEST -> 3
        OutboxKind.PREDICTIONS -> 2
        OutboxKind.SERIES -> 1
        OutboxKind.PHOTO -> 0
    }

/**
 * The self-describing envelope stored in an outbox row's `payload` for **event** kinds
 * (PREDICTIONS/SERIES/NOTE/ALERT/PHOTO): the exact `/v1` [method]/[path] and JSON [body] captured at
 * enqueue time, so the drainer replays it verbatim. INGEST is the one exception — it stores an EMPTY
 * payload as a dirty-marker keyed `ingest:sample:<ts>`, and the drainer resolves the *current*
 * `sample` row at drain time, so repeated writes to one grid slot coalesce (LWW) into a single
 * up-to-date push instead of a stale snapshot.
 */
@Serializable
data class OutboxRequest(val method: String, val path: String, val body: String) {
    fun toSyncRequest() = SyncRequest(method, path, body.toByteArray(Charsets.UTF_8))
}

/** The `ingest:sample:<ts>` dedupKey convention; the drainer parses the ts back out. */
internal const val INGEST_DEDUP_PREFIX = "ingest:sample:"

/**
 * Enqueue-on-write producer API (PLAN.private.md Phase 3). The integrate agent calls these from the
 * CycleRunner / event writers; each serializes the wire body into an [OutboxRequest] envelope and
 * appends a deduped outbox row. Dedup is enforced by the unique `dedupKey` index — this is the
 * "dedup BEFORE send" that makes the non-idempotent `PUT /v1/predictions` safe: the same
 * cycle+models batch can be enqueued only once.
 */
class OutboxEnqueuer(private val repo: OutboxSink) {

    /** All running models' forecasts for one cycle, as a single `PUT /v1/predictions` batch. */
    suspend fun enqueuePredictions(cycleTsMs: Long, preds: List<ModelPrediction>, nowMs: Long): Long {
        val body = SyncJson.encodeToString(preds.map { it.toWrite() })
        return repo.enqueue(
            kind = OutboxKind.PREDICTIONS,
            dedupKey = "pred:$cycleTsMs",
            payload = OutboxRequest("PUT", "/v1/predictions", body).encode(),
            nowMs = nowMs,
        )
    }

    /**
     * Per-`model_id` serialized wire size (bytes) of one cycle's batch, for the Network panel's
     * per-model push accounting. Computed off the same [toWrite] shape the batch is sent as, so the
     * sum matches the pushed `PUT /v1/predictions` body (bar the JSON array framing). Callers must
     * pass only finite forecasts — the JSON encoder rejects NaN/Inf.
     */
    fun predictionWireSizes(preds: List<ModelPrediction>): Map<String, Int> =
        preds.associate { it.modelId to SyncJson.encodeToString(it.toWrite()).toByteArray(Charsets.UTF_8).size }

    /** Mark a grid slot dirty; the drainer resolves and posts the current `sample` at drain time. */
    suspend fun enqueueIngest(gridTsMs: Long, nowMs: Long): Long = repo.enqueue(
        kind = OutboxKind.INGEST,
        dedupKey = "$INGEST_DEDUP_PREFIX$gridTsMs",
        payload = ByteArray(0),
        nowMs = nowMs,
    )

    suspend fun enqueueSeries(name: String, points: List<SeriesPointDto>, nowMs: Long): Long {
        val body = SyncJson.encodeToString(SeriesPutDto(points))
        val minTs = points.minOfOrNull { it.ts } ?: nowMs
        val maxTs = points.maxOfOrNull { it.ts } ?: nowMs
        return repo.enqueue(
            kind = OutboxKind.SERIES,
            dedupKey = "series:$name:$minTs:$maxTs",
            payload = OutboxRequest("PUT", "/v1/series/$name", body).encode(),
            nowMs = nowMs,
        )
    }

    suspend fun enqueueNote(note: NoteWriteDto, nowMs: Long): Long {
        val body = SyncJson.encodeToString(note)
        return repo.enqueue(
            kind = OutboxKind.NOTE,
            dedupKey = "note:${note.ts}:${note.text.hashCode()}",
            payload = OutboxRequest("POST", "/v1/notes", body).encode(),
            nowMs = nowMs,
        )
    }

    suspend fun enqueueAlert(alert: AlertWriteDto, nowMs: Long): Long {
        val body = SyncJson.encodeToString(alert)
        return repo.enqueue(
            kind = OutboxKind.ALERT,
            dedupKey = "alert:${alert.ts}:${alert.kind}",
            payload = OutboxRequest("POST", "/v1/alerts", body).encode(),
            nowMs = nowMs,
        )
    }

    private fun OutboxRequest.encode(): ByteArray =
        SyncJson.encodeToString(this).toByteArray(Charsets.UTF_8)
}
