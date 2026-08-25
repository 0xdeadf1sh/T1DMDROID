package com.t1dm.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.db.OutboxDao
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
import com.t1dm.data.db.NS_ENTRY_DEDUP_PREFIX
import com.t1dm.data.db.SampleEntity
import com.t1dm.sync.nightscout.NightscoutClient
import com.t1dm.sync.nightscout.NightscoutDisabledException
import com.t1dm.sync.nightscout.NsJson
import com.t1dm.sync.nightscout.toNsEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import timber.log.Timber

data class DrainResult(
    val sent: Int = 0,
    val dropped: Int = 0,
    val retried: Int = 0,
    val evicted: Int = 0,
    val standDown: StandDown? = null,
    val remaining: Int = 0,
    /** The bridge never stands the queue down, so without this its failures are invisible. */
    val nightscoutError: String? = null,
) {
    enum class StandDown { NO_PROFILE, AUTH }
}

/**
 * One [drainOnce] pass: evict by size and age, then replay due `PENDING` rows FIFO, deleting on
 * success, dropping malformed 4xx and rescheduling transient failures with [Backoff]. A `NIGHTSCOUT`
 * row goes out through [nightscout] and must NEVER stand the queue down; only T1DMSERVER may.
 */
class QueueDrainer(
    private val dao: OutboxDao,
    private val http: SyncHttpClient,
    private val sampleAt: suspend (Long) -> SampleEntity?,
    private val dispatchers: T1dmDispatchers,
    private val config: DrainConfig = DrainConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
    /** Null means no bridge, and a `NIGHTSCOUT` row is dropped rather than retried. */
    private val nightscout: NightscoutClient? = null,
    /** Tenths of mg/dL per minute. */
    private val trendAt: suspend (Long) -> Int? = { null },
) {
    private val mutex = Mutex()

    /** Both bounds; returns rows evicted. Age-eviction is gated on [OutboxKind.ageEvictable]. */
    suspend fun evict(nowMs: Long = clock()): Int = withContext(dispatchers.io) {
        val rows = dao.evictionRows()
        val ageCut = nowMs - config.maxAgeMs
        val expired = rows.filter { it.kind.ageEvictable && it.createdAtMs < ageCut }.map { it.id }
        val expiredIds = expired.toHashSet()
        val survivors = rows.filterNot { it.id in expiredIds }
        val overflow = survivors.size - config.maxQueueSize
        val trimmed = if (overflow > 0) {
            survivors.sortedWith(compareBy({ it.kind.priority }, { it.createdAtMs }, { it.id }))
                .take(overflow).map { it.id }
        } else {
            emptyList()
        }
        val toDelete = expired + trimmed
        if (toDelete.isNotEmpty()) dao.deleteAll(toDelete)
        toDelete.size
    }

    suspend fun drainOnce(): DrainResult = mutex.withLock {
        withContext(dispatchers.io) {
            // An empty queue is the steady state and the four table operations below all no-op over
            // it. This is the pass that runs most and does least.
            if (dao.count() == 0) return@withContext DrainResult()
            // WHICH rows the reclaim moves, captured before it moves them. `resetState` does not
            // advance `attempts`, so a row that was mid-POST when the process died then looks like
            // one that never reached the wire — the exact case `alreadyPosted` exists for.
            val reclaimed = dao.idsInState(OutboxState.INFLIGHT).toHashSet()
            dao.resetState(OutboxState.INFLIGHT, OutboxState.PENDING)
            val evicted = evict(clock())
            val now = clock()
            val batch = dao.dueBatch(OutboxState.PENDING, now, config.batchLimit)
            var sent = 0
            var dropped = 0
            var retried = 0
            var standDown: DrainResult.StandDown? = null
            var nightscoutError: String? = null
            // One unreachable bridge must not cost the pass a connect timeout PER bridged row; the
            // batch is FIFO, so a third party's outage would delay the patient's own pushes.
            var bridgeUnreachable = false

            loop@ for (row in batch) {
                // Claim BEFORE anything reaches the wire. A row near the tail stays PENDING for as
                // long as the whole pass takes, comfortably inside the undo window; the conditional
                // claim is what makes `withdrawPush`'s WITHDRAWN receipt true.
                if (dao.claim(row.id, OutboxState.PENDING, OutboxState.INFLIGHT) == 0) continue@loop
                val request = resolve(row)
                if (request == null) { dao.delete(row.id); dropped++; continue } // INGEST slot vanished
                val bridged = row.kind == OutboxKind.NIGHTSCOUT
                if (bridged && bridgeUnreachable) { reschedule(row, now); retried++; continue@loop }

                // A REPLAY of a bridged treatment, not a first attempt. `/api/v1` has no idempotency
                // key, so a POST whose ack was lost has already committed and would commit again — a
                // double-counted bolus. A false covers "never arrived" AND "not recognisable".
                if (bridged && (row.attempts > 0 || row.id in reclaimed) && nightscout != null) {
                    val already = runCatching { nightscout.alreadyPosted(request) }.getOrDefault(false)
                    if (already) {
                        Timber.tag(TAG).i("bridge row %d already present; not re-posting", row.id)
                        dao.delete(row.id); sent++; continue@loop
                    }
                }

                val response = try {
                    if (bridged) {
                        nightscout?.execute(request) ?: throw NightscoutDisabledException()
                    } else {
                        http.execute(request)
                    }
                } catch (e: NightscoutDisabledException) {
                    // Switched off while the row sat queued; nothing will ever send it.
                    dao.delete(row.id); dropped++; continue@loop
                } catch (e: NoActiveProfileException) {
                    revert(row); standDown = DrainResult.StandDown.NO_PROFILE; break@loop
                } catch (e: Exception) {
                    reschedule(row, now); retried++
                    if (bridged) { nightscoutError = e.javaClass.simpleName; bridgeUnreachable = true }
                    continue@loop
                }
                when {
                    response.ok -> { dao.delete(row.id); sent++ }
                    // A third party rejecting its own credential is no reason to stop syncing the
                    // patient's record; only T1DMSERVER may stand the whole queue down.
                    response.authError && bridged -> {
                        reschedule(row, now); retried++
                        nightscoutError = "HTTP ${response.code} — secret rejected"
                    }
                    response.authError -> { revert(row); standDown = DrainResult.StandDown.AUTH; break@loop }
                    response.permanentClientError -> {
                        Timber.tag(TAG).w("dropping %s row %d: HTTP %d", row.kind, row.id, response.code)
                        dao.delete(row.id); dropped++
                        if (bridged) nightscoutError = "HTTP ${response.code}"
                    }
                    else -> {
                        reschedule(row, now); retried++
                        if (bridged) nightscoutError = "HTTP ${response.code}"
                    }
                }
            }
            DrainResult(sent, dropped, retried, evicted, standDown, remaining = dao.count(), nightscoutError = nightscoutError)
        }
    }

    /** Restore a row to PENDING without advancing its backoff (auth / no-profile stand-down). */
    private suspend fun revert(row: OutboxEntity) =
        dao.reschedule(row.id, OutboxState.PENDING, row.attempts, row.nextAttemptMs)

    private suspend fun reschedule(row: OutboxEntity, now: Long) {
        val next = now + Backoff.delayMs(config, row.attempts, random())
        dao.reschedule(row.id, OutboxState.PENDING, row.attempts + 1, next)
    }

    private suspend fun resolve(row: OutboxEntity): SyncRequest? = when {
        row.kind == OutboxKind.INGEST -> {
            val ts = row.dedupKey.removePrefix(INGEST_DEDUP_PREFIX).toLongOrNull()
            val sample = ts?.let { sampleAt(it) }
            sample?.let { SyncRequest("POST", "/v1/ingest", SyncJson.encodeToString(it.toIngest()).toByteArray()) }
        }
        // A dirty-marker like INGEST — resolved now, so a slot rewritten while the row waited
        // uploads once, current. A slot with no BG yields null and the row is dropped.
        row.kind == OutboxKind.NIGHTSCOUT && row.dedupKey.startsWith(NS_ENTRY_DEDUP_PREFIX) -> {
            val ts = row.dedupKey.removePrefix(NS_ENTRY_DEDUP_PREFIX).toLongOrNull()
            val entry = ts?.let { sampleAt(it)?.toNsEntry(trendAt(it)) }
            entry?.let {
                SyncRequest("POST", "/api/v1/entries", NsJson.encodeToString(listOf(it)).toByteArray())
            }
        }
        else -> runCatching {
            SyncJson.decodeFromString<OutboxRequest>(String(row.payload, Charsets.UTF_8)).toSyncRequest()
        }.getOrNull()
    }

    private companion object {
        const val TAG = "QueueDrainer"
    }
}
