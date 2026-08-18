package com.t1dm.sync.nightscout

import com.t1dm.data.OutboxSink
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.NS_ENTRY_DEDUP_PREFIX
import com.t1dm.data.db.OutboxKind
import com.t1dm.sync.OutboxRequest
import com.t1dm.sync.SyncJson
import kotlinx.serialization.encodeToString

/** The key a bridged meal/dose is filed under — deterministic in the phone-minted `client_id`, so an
 *  undo can name the exact row to withdraw after the enqueue rowid is forgotten. */
fun nsTreatmentDedupKey(clientId: String): String = "ns:treat:$clientId"

/**
 * Enqueue-on-write producer for the Nightscout bridge — the mirror of `OutboxEnqueuer`, filing
 * `NIGHTSCOUT` rows into the same durable outbox.
 *
 * Sharing the queue is the point: eviction, backoff, the size and age bounds, process-death recovery
 * and the withdrawal hold all already exist and are already tested, and a second queue would be a
 * second copy of every one of those decisions.
 *
 * Enqueueing is unconditional on the bridge being reachable but NOT on it being configured — the
 * caller checks that. A row enqueued for a bridge later switched off is dropped at drain time rather
 * than retried forever.
 */
class NightscoutEnqueuer(private val repo: OutboxSink) {

    /**
     * Mark a grid slot for upload.
     *
     * Like `INGEST`, the row carries an EMPTY payload and the drainer resolves the CURRENT `sample`
     * and its trend at drain time, so repeated writes into one five-minute slot coalesce into a single
     * up-to-date upload rather than a queue of superseded snapshots.
     */
    suspend fun enqueueEntry(gridTsMs: Long, nowMs: Long): Long = repo.enqueue(
        kind = OutboxKind.NIGHTSCOUT,
        dedupKey = "$NS_ENTRY_DEDUP_PREFIX$gridTsMs",
        payload = ByteArray(0),
        nowMs = nowMs,
    )

    /** A logged meal as a `Carb Correction`. [holdMs] is the withdrawal window, exactly as on the
     *  T1DMSERVER push — so an undo inside it takes back BOTH copies and nothing was ever sent. */
    suspend fun enqueueMeal(meal: LoggedMealEntity, nowMs: Long, holdMs: Long = 0L): Long =
        enqueueTreatment(meal.clientId, listOf(meal.toNsTreatment()), nowMs, holdMs)

    /** A logged bolus as a `Correction Bolus`. Returns -1 for a BASAL dose, which this bridge does not
     *  carry — see `LoggedDoseEntity.toNsTreatment`. */
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
