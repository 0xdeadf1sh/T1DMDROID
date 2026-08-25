package com.t1dm.sync.nightscout

import com.t1dm.data.OutboxSink
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.NS_ENTRY_DEDUP_PREFIX
import com.t1dm.data.db.NS_TREATMENT_DEDUP_PREFIX
import com.t1dm.data.db.OutboxKind
import com.t1dm.sync.OutboxRequest
import com.t1dm.sync.SyncJson
import kotlinx.serialization.encodeToString

/** Deterministic in the phone-minted `client_id`, so an undo can name the exact row after the
 *  enqueue rowid is forgotten. */
fun nsTreatmentDedupKey(clientId: String): String = "$NS_TREATMENT_DEDUP_PREFIX$clientId"

/**
 * Files `NIGHTSCOUT` rows into the same durable outbox as `OutboxEnqueuer`, sharing its eviction,
 * backoff, bounds, crash recovery and withdrawal hold. Enqueueing does not check that the bridge is
 * configured — the caller does; a row for a bridge later switched off is dropped at drain time.
 */
class NightscoutEnqueuer(private val repo: OutboxSink) {

    /** Like `INGEST`, an EMPTY payload: the drainer resolves the current `sample` and its trend at
     *  drain time, so repeated writes into one slot coalesce into a single upload. */
    suspend fun enqueueEntry(gridTsMs: Long, nowMs: Long): Long = repo.enqueue(
        kind = OutboxKind.NIGHTSCOUT,
        dedupKey = "$NS_ENTRY_DEDUP_PREFIX$gridTsMs",
        payload = ByteArray(0),
        nowMs = nowMs,
    )

    /** [holdMs] is the withdrawal window, as on the T1DMSERVER push, so an undo takes back both
     *  copies. */
    suspend fun enqueueMeal(meal: LoggedMealEntity, nowMs: Long, holdMs: Long = 0L): Long =
        enqueueTreatment(meal.clientId, listOf(meal.toNsTreatment()), nowMs, holdMs)

    /** Returns -1 for a BASAL dose, which this bridge does not carry. */
    suspend fun enqueueDose(dose: LoggedDoseEntity, nowMs: Long, holdMs: Long = 0L): Long {
        val treatment = dose.toNsTreatment() ?: return -1L
        return enqueueTreatment(dose.clientId, listOf(treatment), nowMs, holdMs)
    }

    private suspend fun enqueueTreatment(
        clientId: String,
        treatments: List<NsTreatmentDto>,
        nowMs: Long,
        holdMs: Long,
    ): Long = repo.enqueue(
        kind = OutboxKind.NIGHTSCOUT,
        dedupKey = nsTreatmentDedupKey(clientId),
        payload = SyncJson.encodeToString(
            OutboxRequest("POST", "/api/v1/treatments", NsJson.encodeToString(treatments)),
        ).toByteArray(Charsets.UTF_8),
        nowMs = nowMs,
        notBeforeMs = if (holdMs > 0L) nowMs + holdMs else 0L,
    )
}
