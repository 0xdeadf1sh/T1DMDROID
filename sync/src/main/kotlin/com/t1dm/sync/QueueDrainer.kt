package com.t1dm.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.db.OutboxDao
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxEvictRow
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

/** drainOnce: evict, then one FIFO lane per destination; neither can stand the other down. */
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
        val (bridge, server) = survivors.partition { it.kind == OutboxKind.NIGHTSCOUT }
        // Per-lane budgets: one shared cap evicts every bridged row first, they rank below all.
        val reserve = config.maxQueueSize / BRIDGE_RESERVE_DIVISOR
        // Each borrows what the other leaves unused, down to its own floor.
        val bridgeCap = maxOf(config.maxQueueSize - server.size, reserve)
        val serverCap = config.maxQueueSize - minOf(bridge.size, bridgeCap)
        val toDelete = expired + trim(bridge, bridgeCap) + trim(server, serverCap)
        if (toDelete.isNotEmpty()) dao.deleteAll(toDelete)
        toDelete.size
    }

    private fun trim(lane: List<OutboxEvictRow>, cap: Int): List<Long> {
        val overflow = lane.size - cap
        if (overflow <= 0) return emptyList()
        return lane.sortedWith(compareBy({ it.kind.priority }, { it.createdAtMs }, { it.id }))
            .take(overflow).map { it.id }
    }

    suspend fun drainOnce(): DrainResult = mutex.withLock {
        withContext(dispatchers.io) {
            // Empty queue is steady state; the four table ops below all no-op over it.
            if (dao.count() == 0) return@withContext DrainResult()
            // Reclaimed ids captured before resetState (no attempts bump); alreadyPosted covers it.
            val reclaimed = dao.idsInState(OutboxState.INFLIGHT).toHashSet()
            dao.resetState(OutboxState.INFLIGHT, OutboxState.PENDING)
            val evicted = evict(clock())
            val now = clock()
            val bridgeKind = OutboxKind.NIGHTSCOUT
            // Own batch each: one FIFO lets the lane holding the older rows take every slot.
            val server = drainLane(
                dao.dueBatchExcludingKind(OutboxState.PENDING, now, bridgeKind, config.batchLimit),
                now, reclaimed, bridged = false,
            )
            val bridge = drainLane(
                dao.dueBatchOfKind(OutboxState.PENDING, now, bridgeKind, config.batchLimit),
                now, reclaimed, bridged = true,
            )
            DrainResult(
                sent = server.sent + bridge.sent,
                dropped = server.dropped + bridge.dropped,
                retried = server.retried + bridge.retried,
                evicted = evicted,
                standDown = server.standDown,
                remaining = dao.count(),
                nightscoutError = bridge.error,
            )
        }
    }

    private data class LaneResult(
        val sent: Int = 0,
        val dropped: Int = 0,
        val retried: Int = 0,
        val standDown: DrainResult.StandDown? = null,
        val error: String? = null,
    )

    /** One destination's turn. A stand-down or an unreachable host ends THIS lane only. */
    private suspend fun drainLane(
        batch: List<OutboxEntity>,
        now: Long,
        reclaimed: Set<Long>,
        bridged: Boolean,
    ): LaneResult {
        var sent = 0
        var dropped = 0
        var retried = 0
        var standDown: DrainResult.StandDown? = null
        var error: String? = null
        // One unreachable host must not cost a connect timeout per row; batch is FIFO.
        var unreachable = false
        val bridge = nightscout

        loop@ for (row in batch) {
            // Claim before the wire: a tail row stays PENDING within the undo window.
            if (dao.claim(row.id, OutboxState.PENDING, OutboxState.INFLIGHT) == 0) continue@loop
            val request = resolve(row)
            if (request == null) { dao.delete(row.id); dropped++; continue } // slot vanished
            if (unreachable) { reschedule(row, now); retried++; continue@loop }

            // Replay of bridged send: /api/v1 has no idempotency key, a lost ack double-counts.
            if (bridged && (row.attempts > 0 || row.id in reclaimed) && bridge != null) {
                val already = runCatching { bridge.alreadyPosted(request) }.getOrDefault(false)
                if (already) {
                    Timber.tag(TAG).i("bridge row %d already present; not re-posting", row.id)
                    dao.delete(row.id); sent++; continue@loop
                }
            }

            val response = try {
                if (bridged) {
                    bridge?.execute(request) ?: throw NightscoutDisabledException()
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
                error = e.javaClass.simpleName
                unreachable = true
                continue@loop
            }
            when {
                response.ok -> { dao.delete(row.id); sent++ }
                // A rejected bridge secret backs off one row; only T1DMSERVER stands a lane down.
                response.authError && bridged -> {
                    reschedule(row, now); retried++
                    error = "HTTP ${response.code} — secret rejected"
                }
                response.authError -> { revert(row); standDown = DrainResult.StandDown.AUTH; break@loop }
                response.permanentClientError -> {
                    Timber.tag(TAG).w("dropping %s row %d: HTTP %d", row.kind, row.id, response.code)
                    dao.delete(row.id); dropped++
                    error = "HTTP ${response.code}"
                }
                else -> {
                    reschedule(row, now); retried++
                    error = "HTTP ${response.code}"
                }
            }
        }
        return LaneResult(sent, dropped, retried, standDown, error)
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
        // Dirty-marker like INGEST, resolved now: a slot rewritten mid-wait uploads once, current.
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

        /** Bridge floor as a fraction of maxQueueSize; ~7 d of five-minute rows at 20 000. */
        const val BRIDGE_RESERVE_DIVISOR = 10
    }
}
