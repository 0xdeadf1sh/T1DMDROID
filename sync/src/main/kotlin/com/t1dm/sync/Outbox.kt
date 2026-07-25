package com.t1dm.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.OutboxSink
import com.t1dm.data.db.OutboxKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Eviction priority: irreplaceable clinical records outrank regenerable forecasts —
 * `ALERT > DOSE > MEAL > NOTE > INGEST > STATS > PREDICTIONS > SERIES > PHOTO`. Higher rank survives;
 * when the queue is over its size bound the lowest-rank, oldest rows are dropped first. An ALERT (a
 * safety signal) is the last thing ever evicted; a stale display PREDICTION or a PHOTO is the first.
 * SERIES is a retired tombstone (the app DB is not wiped, so a pending pre-upgrade row must still
 * decode and drain to completion).
 */
internal val OutboxKind.priority: Int
    get() = when (this) {
        OutboxKind.ALERT -> 8
        OutboxKind.DOSE -> 7
        OutboxKind.MEAL -> 6
        OutboxKind.NOTE -> 5
        OutboxKind.INGEST -> 4
        OutboxKind.STATS -> 3
        OutboxKind.PREDICTIONS -> 2
        OutboxKind.SERIES -> 1
        OutboxKind.PHOTO -> 0
    }

/**
 * Whether an over-age outbox row may be dropped by the age-eviction sweep (gated in [QueueDrainer]).
 * Irreplaceable clinical kinds (ALERT/DOSE/MEAL/NOTE) never age out — only the hard size cap can
 * ever evict them; regenerable kinds (INGEST/STATS/PREDICTIONS/SERIES/PHOTO) do.
 */
internal val OutboxKind.ageEvictable: Boolean
    get() = when (this) {
        OutboxKind.ALERT, OutboxKind.DOSE, OutboxKind.MEAL, OutboxKind.NOTE -> false
        else -> true
    }

/**
 * The self-describing envelope stored in an outbox row's `payload` for **event** kinds
 * (PREDICTIONS/MEAL/DOSE/STATS/NOTE/ALERT/PHOTO, plus the retired SERIES tombstone): the exact `/v1`
 * [method]/[path] and JSON [body] captured at enqueue time, so the drainer replays it verbatim.
 * INGEST is the one exception — it stores an EMPTY
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
 * The dedupKey a logged meal / dose push is filed under — deterministic in the event's phone-minted
 * `client_id` (§3.2), so the row stays addressable after the enqueue rowid is forgotten (process
 * death) and cross-checkable against a rowid SQLite may have recycled. Public because the undo path
 * in `:app` must name the exact key [OutboxEnqueuer.enqueueMeal]/[OutboxEnqueuer.enqueueDose] wrote,
 * and a second copy of the string here would be free to drift from the one that matters.
 */
fun mealDedupKey(clientId: String): String = "meal:$clientId"

fun doseDedupKey(clientId: String): String = "dose:$clientId"

/**
 * Enqueue-on-write producer API (Phase 3). The integrate agent calls these from the
 * CycleRunner / event writers; each serializes the wire body into an [OutboxRequest] envelope and
 * appends a deduped outbox row. Dedup is enforced by the unique `dedupKey` index — this is the
 * "dedup BEFORE send" that makes the non-idempotent `PUT /v1/predictions` safe: the same
 * cycle+models batch can be enqueued only once.
 */
class OutboxEnqueuer(private val repo: OutboxSink) {

    /** All running models' forecasts for one cycle, as a single `PUT /v1/predictions` batch. */
    suspend fun enqueuePredictions(cycleTsMs: Long, preds: List<ModelPrediction>, nowMs: Long): Long {
        val body = SyncJson.encodeToString(preds.map { it.toWrite(cycleTsMs, nowMs) })
        return repo.enqueue(
            kind = OutboxKind.PREDICTIONS,
            dedupKey = "pred:$cycleTsMs",
            payload = OutboxRequest("PUT", "/v1/predictions", body).encode(),
            nowMs = nowMs,
        )
    }

    /**
     * Per-`model_id` serialized wire size (bytes) of one cycle's batch, for the Network panel's
     * per-model push accounting. Computed off the same [toWrite] shape — same [cycleTsMs]/[nowMs]
     * `made_at`/`updated_at` — the batch is sent as, so the sum matches the pushed
     * `PUT /v1/predictions` body (bar the JSON array framing). Callers must pass only finite
     * forecasts — the JSON encoder rejects NaN/Inf.
     */
    fun predictionWireSizes(preds: List<ModelPrediction>, cycleTsMs: Long, nowMs: Long): Map<String, Int> =
        preds.associate {
            it.modelId to SyncJson.encodeToString(it.toWrite(cycleTsMs, nowMs)).toByteArray(Charsets.UTF_8).size
        }

    /** Mark a grid slot dirty; the drainer resolves and posts the current `sample` at drain time. */
    suspend fun enqueueIngest(gridTsMs: Long, nowMs: Long): Long = repo.enqueue(
        kind = OutboxKind.INGEST,
        dedupKey = "$INGEST_DEDUP_PREFIX$gridTsMs",
        payload = ByteArray(0),
        nowMs = nowMs,
    )

    /** A logged meal as a self-describing appearance curve — `PUT /v1/meals` (1-element batch). */
    suspend fun enqueueMeal(ev: MealEventDto, nowMs: Long): Long = repo.enqueue(
        kind = OutboxKind.MEAL,
        dedupKey = mealDedupKey(ev.client_id),
        payload = OutboxRequest("PUT", "/v1/meals", SyncJson.encodeToString(listOf(ev))).encode(),
        nowMs = nowMs,
    )

    /** A logged dose (bolus gamma / basal Bateman) as a PK action curve — `PUT /v1/doses`. */
    suspend fun enqueueDose(ev: DoseEventDto, nowMs: Long): Long = repo.enqueue(
        kind = OutboxKind.DOSE,
        dedupKey = doseDedupKey(ev.client_id),
        payload = OutboxRequest("PUT", "/v1/doses", SyncJson.encodeToString(listOf(ev))).encode(),
        nowMs = nowMs,
    )

    /**
     * The full active basal template as a `PUT /v1/basal-schedule` full-replace. There is no
     * dedicated basal [OutboxKind], so it rides DOSE — inheriting DOSE's priority and its
     * never-age-evict guarantee (a schedule is an irreplaceable clinical record); routing is by the
     * envelope path, not the kind. Deduped on the schedule's version — the newest slot `updated_at`
     * — so a genuine edit re-enqueues while an exact retry coalesces to a single no-op push.
     */
    suspend fun enqueueBasalSchedule(schedule: BasalScheduleDto, nowMs: Long): Long {
        val version = schedule.slots.maxOfOrNull { it.updated_at } ?: nowMs
        return repo.enqueue(
            kind = OutboxKind.DOSE,
            dedupKey = "basal:${schedule.schedule_id}:$version",
            payload = OutboxRequest("PUT", "/v1/basal-schedule", SyncJson.encodeToString(schedule)).encode(),
            nowMs = nowMs,
        )
    }

    /** One phone-computed window block — `PUT /v1/stats`; deduped to at most one per window per day. */
    suspend fun enqueueStats(stats: StatsPushDto, nowMs: Long): Long = repo.enqueue(
        kind = OutboxKind.STATS,
        dedupKey = "stats:${stats.window}:${nowMs / 86_400_000}",
        payload = OutboxRequest("PUT", "/v1/stats", SyncJson.encodeToString(stats)).encode(),
        nowMs = nowMs,
    )

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
