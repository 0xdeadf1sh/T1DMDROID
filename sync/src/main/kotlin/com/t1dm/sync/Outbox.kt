package com.t1dm.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.OutboxSink
import com.t1dm.data.db.OutboxKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Higher survives the size bound; SERIES/PREDICTIONS retired but decode, else valueOf throws. */
internal val OutboxKind.priority: Int
    get() = when (this) {
        OutboxKind.ALERT -> 9
        OutboxKind.DOSE -> 8
        OutboxKind.MEAL -> 7
        OutboxKind.INGEST -> 6
        // Below INGEST: a reading never waits behind a descriptor; server accepts an untold label.
        OutboxKind.CGM_SOURCE -> 5
        OutboxKind.STATS -> 4
        OutboxKind.PREDICTIONS -> 3
        OutboxKind.SERIES -> 2
        // A bridged row MIRRORS a queued record; dropping loses a copy, not the event itself.
        OutboxKind.NIGHTSCOUT -> 1
        OutboxKind.PHOTO -> 0
    }

/** ALERT/DOSE/MEAL never age out, only size cap evicts; NIGHTSCOUT does, it only mirrors a copy. */
internal val OutboxKind.ageEvictable: Boolean
    get() = when (this) {
        OutboxKind.ALERT, OutboxKind.DOSE, OutboxKind.MEAL -> false
        else -> true
    }

/** /v1 method/path/body captured at enqueue, replayed verbatim; INGEST is an empty dirty-marker. */
@Serializable
data class OutboxRequest(val method: String, val path: String, val body: String) {
    fun toSyncRequest() = SyncRequest(method, path, body.toByteArray(Charsets.UTF_8))
}

/** `ingest:sample:<ts>`; the drainer parses the ts back out. */
internal const val INGEST_DEDUP_PREFIX = "ingest:sample:"

/** Deterministic in the phone-minted client_id (§3.2); undo names the row after rowid is gone. */
fun mealDedupKey(clientId: String): String = "meal:$clientId"

fun doseDedupKey(clientId: String): String = "dose:$clientId"

fun cgmSourceDedupKey(id: String): String = "cgmsrc:$id"

/** Dedup via unique dedupKey index; a forecast never comes here: unstored, unretried frame. */
class OutboxEnqueuer(private val repo: OutboxSink) {

    /** Mark a grid slot dirty; the drainer resolves and posts the current sample at drain. */
    suspend fun enqueueIngest(gridTsMs: Long, nowMs: Long): Long = repo.enqueue(
        kind = OutboxKind.INGEST,
        dedupKey = "$INGEST_DEDUP_PREFIX$gridTsMs",
        payload = ByteArray(0),
        nowMs = nowMs,
    )

    /** holdMs postpones the first send, row stays withdrawable; a persisted floor, not a timer. */
    suspend fun enqueueMeal(ev: MealEventDto, nowMs: Long, holdMs: Long = 0L): Long = repo.enqueueSuperseding(
        kind = OutboxKind.MEAL,
        dedupKey = mealDedupKey(ev.client_id),
        payload = OutboxRequest("PUT", "/v1/meals", SyncJson.encodeToString(listOf(ev))).encode(),
        nowMs = nowMs,
        notBeforeMs = if (holdMs > 0L) nowMs + holdMs else 0L,
    )

    /** Same kind/dedupKey as create: separate key risks the cap evicting the tombstone, not it. */
    suspend fun enqueueMealTombstone(ev: MealTombstoneDto, nowMs: Long): Long = repo.enqueueSuperseding(
        kind = OutboxKind.MEAL,
        dedupKey = mealDedupKey(ev.client_id),
        payload = OutboxRequest("PUT", "/v1/meals", SyncJson.encodeToString(listOf(ev))).encode(),
        nowMs = nowMs,
    )

    /** Deduped on source id not body: newest descriptor of one object is worth sending. */
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

    /** No basal OutboxKind: rides DOSE for priority/never-evict; routed by path, dedup on time. */
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
