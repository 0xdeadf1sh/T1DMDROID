package com.t1dm.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.OutboxSink
import com.t1dm.data.db.OutboxKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Higher survives: over the size bound the lowest-rank, oldest rows go first. SERIES and PREDICTIONS
 * are retired but must still DECODE — `OutboxKind` persists as its enum name and `valueOf` throws on
 * an unknown one, wedging every drain. Only the order is load-bearing; nothing persists a rank.
 */
internal val OutboxKind.priority: Int
    get() = when (this) {
        OutboxKind.ALERT -> 9
        OutboxKind.DOSE -> 8
        OutboxKind.MEAL -> 7
        OutboxKind.INGEST -> 6
        // Below INGEST: a reading must never wait behind a descriptor. The server accepts a label
        // for a source it has not been told about, so arriving late is harmless.
        OutboxKind.CGM_SOURCE -> 5
        OutboxKind.STATS -> 4
        OutboxKind.PREDICTIONS -> 3
        OutboxKind.SERIES -> 2
        // A bridged row MIRRORS a record already queued for the phone's own server; dropping one
        // loses a third party's copy, not the event.
        OutboxKind.NIGHTSCOUT -> 1
        OutboxKind.PHOTO -> 0
    }

/**
 * ALERT/DOSE/MEAL never age out — only the hard size cap can evict them. NIGHTSCOUT does despite
 * mirroring a clinical event: it carries a COPY, and the event itself is in `logged_meal`/
 * `logged_dose` either way.
 */
internal val OutboxKind.ageEvictable: Boolean
    get() = when (this) {
        OutboxKind.ALERT, OutboxKind.DOSE, OutboxKind.MEAL -> false
        else -> true
    }

/**
 * The `/v1` [method]/[path]/[body] captured at enqueue time, replayed verbatim by the drainer.
 * INGEST is the exception: an EMPTY payload as a dirty-marker keyed `ingest:sample:<ts>`, resolved
 * at drain time so repeated writes to one grid slot coalesce (LWW).
 */
@Serializable
data class OutboxRequest(val method: String, val path: String, val body: String) {
    fun toSyncRequest() = SyncRequest(method, path, body.toByteArray(Charsets.UTF_8))
}

/** `ingest:sample:<ts>`; the drainer parses the ts back out. */
internal const val INGEST_DEDUP_PREFIX = "ingest:sample:"

/** Deterministic in the event's phone-minted `client_id` (§3.2), so `:app`'s undo can name the exact
 *  row after the enqueue rowid is forgotten. */
fun mealDedupKey(clientId: String): String = "meal:$clientId"

fun doseDedupKey(clientId: String): String = "dose:$clientId"

fun cgmSourceDedupKey(id: String): String = "cgmsrc:$id"

/**
 * Dedup is enforced by the unique `dedupKey` index — one row per key, so a repeated write of the
 * same event cannot queue twice. A forecast does not come through here at all: it is an unstored
 * stream frame with no queue behind it and no retry.
 */
class OutboxEnqueuer(private val repo: OutboxSink) {

    /** Mark a grid slot dirty; the drainer resolves and posts the current `sample` at drain time. */
    suspend fun enqueueIngest(gridTsMs: Long, nowMs: Long): Long = repo.enqueue(
        kind = OutboxKind.INGEST,
        dedupKey = "$INGEST_DEDUP_PREFIX$gridTsMs",
        payload = ByteArray(0),
        nowMs = nowMs,
    )

    /**
     * [holdMs] postpones the FIRST send attempt, keeping the row withdrawable. A persisted floor on
     * `nextAttemptMs`, not a timer: nothing counts it down and it survives process death. `0` means
     * eligible at once, which is what the §3.8 re-mirror walk must use.
     */
    suspend fun enqueueMeal(ev: MealEventDto, nowMs: Long, holdMs: Long = 0L): Long = repo.enqueueSuperseding(
        kind = OutboxKind.MEAL,
        dedupKey = mealDedupKey(ev.client_id),
        payload = OutboxRequest("PUT", "/v1/meals", SyncJson.encodeToString(listOf(ev))).encode(),
        nowMs = nowMs,
        notBeforeMs = if (holdMs > 0L) nowMs + holdMs else 0L,
    )

    /**
     * Same kind and same dedupKey as the create, deliberately: under its own key the size cap could
     * evict the deletion while the create it retires survived, and the event would come back. The
     * body carries no curve fields — required on a live meal, ignored on a deletion.
     */
    suspend fun enqueueMealTombstone(ev: MealTombstoneDto, nowMs: Long): Long = repo.enqueueSuperseding(
        kind = OutboxKind.MEAL,
        dedupKey = mealDedupKey(ev.client_id),
        payload = OutboxRequest("PUT", "/v1/meals", SyncJson.encodeToString(listOf(ev))).encode(),
        nowMs = nowMs,
    )

    /** Deduped on the source id, not on a body: a descriptor describes one physical object, and the
     *  newest one the phone has is the only one worth sending. */
    suspend fun enqueueCgmSource(src: CgmSourceDto, nowMs: Long): Long = repo.enqueue(
        kind = OutboxKind.CGM_SOURCE,
        dedupKey = cgmSourceDedupKey(src.id),
        payload = OutboxRequest("PUT", "/v1/cgm-sources", SyncJson.encodeToString(listOf(src))).encode(),
        nowMs = nowMs,
    )

    /** [holdMs] is the withdrawal window, exactly as on [enqueueMeal]. */
    suspend fun enqueueDose(ev: DoseEventDto, nowMs: Long, holdMs: Long = 0L): Long = repo.enqueueSuperseding(
        kind = OutboxKind.DOSE,
        dedupKey = doseDedupKey(ev.client_id),
        payload = OutboxRequest("PUT", "/v1/doses", SyncJson.encodeToString(listOf(ev))).encode(),
        nowMs = nowMs,
        notBeforeMs = if (holdMs > 0L) nowMs + holdMs else 0L,
    )

    /** The dose twin of [enqueueMealTombstone]. */
    suspend fun enqueueDoseTombstone(ev: DoseTombstoneDto, nowMs: Long): Long = repo.enqueueSuperseding(
        kind = OutboxKind.DOSE,
        dedupKey = doseDedupKey(ev.client_id),
        payload = OutboxRequest("PUT", "/v1/doses", SyncJson.encodeToString(listOf(ev))).encode(),
        nowMs = nowMs,
    )

    /**
     * There is no basal [OutboxKind], so this rides DOSE for its priority and never-age-evict;
     * routing is by the envelope path, not the kind. Deduped on the newest slot `updated_at`. No
     * hold: a template is not a logged event and nothing can withdraw it.
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

    /** Deduped to at most one per window per day. */
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
