package com.t1dm.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.db.OutboxDao
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
import com.t1dm.data.db.SampleEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import timber.log.Timber

/** Outcome of one [QueueDrainer.drainOnce], surfaced to the Network panel + logs. */
data class DrainResult(
    val sent: Int = 0,
    val dropped: Int = 0,
    val retried: Int = 0,
    val evicted: Int = 0,
    val standDown: StandDown? = null,
    val remaining: Int = 0,
) {
    enum class StandDown { NO_PROFILE, AUTH }
}

/**
 * The durable outbox drainer (PLAN.private.md Phase 3). One [drainOnce] pass, called by the
 * foreground service tick and a WorkManager fallback: it evicts by size/age, then replays due
 * `PENDING` rows FIFO through the [SyncHttpClient], deleting on success, dropping malformed 4xx,
 * and rescheduling transient failures (5xx / IO / 429) with [Backoff]. A single-process [Mutex]
 * serialises passes, and any `INFLIGHT` row left by a crash is reclaimed to `PENDING` at the top of
 * each pass. All DB + network work is on [T1dmDispatchers.io].
 *
 * INGEST rows are dirty-markers — the current `sample` is resolved via [sampleAt] at drain time so
 * repeated writes to a grid slot coalesce into one up-to-date push; a slot whose row has since
 * vanished is simply dropped. Every other kind carries a self-describing [OutboxRequest] payload.
 */
class QueueDrainer(
    private val dao: OutboxDao,
    private val http: SyncHttpClient,
    private val sampleAt: suspend (Long) -> SampleEntity?,
    private val dispatchers: T1dmDispatchers,
    private val config: DrainConfig = DrainConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
) {
    private val mutex = Mutex()

    /** Enforce the size AND age bounds; returns the number of rows evicted. Public for the panel. */
    suspend fun evict(nowMs: Long = clock()): Int = withContext(dispatchers.io) {
        val rows = dao.evictionRows()
        val ageCut = nowMs - config.maxAgeMs
        val expired = rows.filter { it.createdAtMs < ageCut }.map { it.id }
        val survivors = rows.filterNot { it.id in expired.toHashSet() }
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
            dao.resetState(OutboxState.INFLIGHT, OutboxState.PENDING) // reclaim crash-wedged rows
            val evicted = evict(clock())
            val now = clock()
            val batch = dao.dueBatch(OutboxState.PENDING, now, config.batchLimit)
            var sent = 0
            var dropped = 0
            var retried = 0
            var standDown: DrainResult.StandDown? = null

            loop@ for (row in batch) {
                val request = resolve(row)
                if (request == null) { dao.delete(row.id); dropped++; continue } // INGEST slot vanished
                dao.reschedule(row.id, OutboxState.INFLIGHT, row.attempts, row.nextAttemptMs)
                val response = try {
                    http.execute(request)
                } catch (e: NoActiveProfileException) {
                    revert(row); standDown = DrainResult.StandDown.NO_PROFILE; break@loop
                } catch (e: Exception) {
                    reschedule(row, now); retried++; continue@loop
                }
                when {
                    response.ok -> { dao.delete(row.id); sent++ }
                    response.authError -> { revert(row); standDown = DrainResult.StandDown.AUTH; break@loop }
                    response.permanentClientError -> {
                        Timber.tag(TAG).w("dropping %s row %d: HTTP %d", row.kind, row.id, response.code)
                        dao.delete(row.id); dropped++
                    }
                    else -> { reschedule(row, now); retried++ }
                }
            }
            DrainResult(sent, dropped, retried, evicted, standDown, remaining = dao.count())
        }
    }

    /** Restore a row to PENDING without advancing its backoff (auth / no-profile stand-down). */
    private suspend fun revert(row: OutboxEntity) =
        dao.reschedule(row.id, OutboxState.PENDING, row.attempts, row.nextAttemptMs)

    private suspend fun reschedule(row: OutboxEntity, now: Long) {
        val next = now + Backoff.delayMs(config, row.attempts, random())
        dao.reschedule(row.id, OutboxState.PENDING, row.attempts + 1, next)
    }

    private suspend fun resolve(row: OutboxEntity): SyncRequest? = when (row.kind) {
        OutboxKind.INGEST -> {
            val ts = row.dedupKey.removePrefix(INGEST_DEDUP_PREFIX).toLongOrNull()
            val sample = ts?.let { sampleAt(it) }
            sample?.let { SyncRequest("POST", "/v1/ingest", SyncJson.encodeToString(it.toIngest()).toByteArray()) }
        }
        else -> runCatching {
            SyncJson.decodeFromString<OutboxRequest>(String(row.payload, Charsets.UTF_8)).toSyncRequest()
        }.getOrNull()
    }

    private companion object {
        const val TAG = "QueueDrainer"
    }
}
