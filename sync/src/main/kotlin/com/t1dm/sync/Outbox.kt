package com.t1dm.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.OutboxSink
import com.t1dm.data.db.OutboxKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Eviction priority: irreplaceable clinical records outrank regenerable forecasts —
 * `ALERT > DOSE > MEAL > INGEST > STATS > PREDICTIONS > SERIES > PHOTO`. Higher rank survives;
 * when the queue is over its size bound the lowest-rank, oldest rows are dropped first. An ALERT (a
 * safety signal) is the last thing ever evicted; a stale display PREDICTION or a PHOTO is the first.
 * SERIES is a retired tombstone (the app DB is not wiped, so a pending pre-upgrade row must still
 * decode and drain to completion). Only the ORDER is load-bearing — nothing persists a rank, so the
 * numbers are free to close up when a kind leaves.
 */
internal val OutboxKind.priority: Int
    get() = when (this) {
        OutboxKind.ALERT -> 7
        OutboxKind.DOSE -> 6
        OutboxKind.MEAL -> 5
        OutboxKind.INGEST -> 4
        OutboxKind.STATS -> 3
        OutboxKind.PREDICTIONS -> 2
        OutboxKind.SERIES -> 1
        OutboxKind.PHOTO -> 0
    }

/**
 * Whether an over-age outbox row may be dropped by the age-eviction sweep (gated in [QueueDrainer]).
 * Irreplaceable clinical kinds (ALERT/DOSE/MEAL) never age out — only the hard size cap can
 * ever evict them; regenerable kinds (INGEST/STATS/PREDICTIONS/SERIES/PHOTO) do.
 */
internal val OutboxKind.ageEvictable: Boolean
    get() = when (this) {
        OutboxKind.ALERT, OutboxKind.DOSE, OutboxKind.MEAL -> false
        else -> true
    }

/**
 * The self-describing envelope stored in an outbox row's `payload` for **event** kinds
 * (PREDICTIONS/MEAL/DOSE/STATS/ALERT/PHOTO, plus the retired SERIES tombstone): the exact `/v1`
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

    /**
     * All running models' forecasts for one cycle, as a single `PUT /v1/predictions` batch.
     *
     * The ONE producer that REPLACES rather than appends. `pred:<cycleTsMs>` keys a cycle, not a
     * body, and a cycle can be run more than once inside its own 5-min slot — the grid tick fires
     * one, and a logged meal or dose fires another off the same slot with the carb/insulin channels
     * it just changed. Under the plain append the second batch collided with the unique key and was
     * IGNORED, silently: the phone drew the post-log forecast while the server kept the pre-log one
     * until the next slot. [com.t1dm.data.OutboxSink.enqueueReplacingPending] drops the superseded
     * row first, so the queue still holds at most one prediction push per cycle and it is always the
     * newest. Returns -1 when a drain already owns the key — see that method for the race.
     */
    suspend fun enqueuePredictions(cycleTsMs: Long, preds: List<ModelPrediction>, nowMs: Long): Long {
        val body = SyncJson.encodeToString(preds.map { it.toWrite(cycleTsMs, nowMs) })
        return repo.enqueueReplacingPending(
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

    /**
     * A logged meal as a self-describing appearance curve — `PUT /v1/meals` (1-element batch).
     *
     * [holdMs] postpones the FIRST send attempt by that much, so the row stays withdrawable from the
     * Logs panel for a window the user chooses. It is a persisted floor on `nextAttemptMs`, not a timer:
     * nothing in the app counts it down, it survives process death, and an offline phone simply keeps
     * the row past the hold as it would any other undrained push.
     *
     * `0` — the default, and what the §3.8 re-mirror walk must use — means eligible at once. A replay of
     * last year's meals has nothing left to reconsider, and dating a hold from `nowMs` there would stall
     * the walk behind a delay that buys nobody anything.
     */
    suspend fun enqueueMeal(ev: MealEventDto, nowMs: Long, holdMs: Long = 0L): Long = repo.enqueue(
        kind = OutboxKind.MEAL,
        dedupKey = mealDedupKey(ev.client_id),
        payload = OutboxRequest("PUT", "/v1/meals", SyncJson.encodeToString(listOf(ev))).encode(),
        nowMs = nowMs,
        notBeforeMs = if (holdMs > 0L) nowMs + holdMs else 0L,
    )

    /** A logged dose (bolus gamma / basal Bateman) as a PK action curve — `PUT /v1/doses`. [holdMs] is
     *  the withdrawal window, exactly as on [enqueueMeal]. */
    suspend fun enqueueDose(ev: DoseEventDto, nowMs: Long, holdMs: Long = 0L): Long = repo.enqueue(
        kind = OutboxKind.DOSE,
        dedupKey = doseDedupKey(ev.client_id),
        payload = OutboxRequest("PUT", "/v1/doses", SyncJson.encodeToString(listOf(ev))).encode(),
        nowMs = nowMs,
        notBeforeMs = if (holdMs > 0L) nowMs + holdMs else 0L,
    )

    /**
     * The full active basal template as a `PUT /v1/basal-schedule` full-replace. There is no
     * dedicated basal [OutboxKind], so it rides DOSE — inheriting DOSE's priority and its
     * never-age-evict guarantee (a schedule is an irreplaceable clinical record); routing is by the
     * envelope path, not the kind. Deduped on the schedule's version — the newest slot `updated_at`
     * — so a genuine edit re-enqueues while an exact retry coalesces to a single no-op push.
     *
     * Takes NO hold, despite riding the DOSE kind: the hold exists to keep a row withdrawable, and a
     * template is not a logged event — the Logs panel lists `logged_meal`/`logged_dose` and has no
     * affordance that could withdraw this. A later edit supersedes it by version instead.
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
