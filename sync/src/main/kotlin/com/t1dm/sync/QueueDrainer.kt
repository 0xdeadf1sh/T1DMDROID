package com.t1dm.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.db.OutboxDao
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxState
import com.t1dm.data.db.NS_ENTRY_DEDUP_PREFIX
import com.t1dm.data.db.SampleEntity
import com.t1dm.sync.nightscout.NightscoutClient
import com.t1dm.sync.nightscout.NightscoutDisabledException
import com.t1dm.sync.nightscout.NsEntryDto
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
    val remaining: Int = 0,
    /** The bridge never stands the queue down, so without this its failures are invisible. */
    val nightscoutError: String? = null,
)

/** drainOnce: evict, then one FIFO pass to the Nightscout bridge. */
class QueueDrainer(
    private val dao: OutboxDao,
    private val sampleAt: suspend (Long) -> SampleEntity?,
    private val dispatchers: T1dmDispatchers,
    private val config: DrainConfig = DrainConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
    /** Null means no bridge, and a queued row is dropped rather than retried. */
    private val nightscout: NightscoutClient? = null,
    /** Tenths of mg/dL per minute. */
    private val trendAt: suspend (Long) -> Int? = { null },
) {
    private val mutex = Mutex()

    /** Both bounds, oldest first; returns rows evicted. */
    suspend fun evict(nowMs: Long = clock()): Int = withContext(dispatchers.io) {
        val rows = dao.evictionRows()
        val ageCut = nowMs - config.maxAgeMs
        val (expired, survivors) = rows.partition { it.createdAtMs < ageCut }
        val overflow = (survivors.size - config.maxQueueSize).coerceAtLeast(0)
        val toDelete = expired.map { it.id } + survivors.take(overflow).map { it.id }
        if (toDelete.isNotEmpty()) dao.deleteAll(toDelete)
        toDelete.size
    }

    suspend fun drainOnce(): DrainResult = mutex.withLock {
        withContext(dispatchers.io) {
            // Empty queue is the steady state; the four table ops below all no-op over it.
            if (dao.count() == 0) return@withContext DrainResult()
            // reclaimed captured pre-resetState; alreadyPosted covers a mid-POST death row.
            val reclaimed = dao.idsInState(OutboxState.INFLIGHT).toHashSet()
            dao.resetState(OutboxState.INFLIGHT, OutboxState.PENDING)
            val evicted = evict(clock())
            val now = clock()
            drain(dao.dueBatch(OutboxState.PENDING, now, config.batchLimit), now, reclaimed)
                .copy(evicted = evicted, remaining = dao.count())
        }
    }

    /** An unreachable host ends the pass; the rest of the batch keeps its place. */
    private suspend fun drain(batch: List<OutboxEntity>, now: Long, reclaimed: Set<Long>): DrainResult {
        var sent = 0
        var dropped = 0
        var retried = 0
        var error: String? = null
        // One unreachable host must not cost a connect timeout per row; batch is FIFO.
        var unreachable = false
        var requests = 0
        val bridge = nightscout

        loop@ for (group in groups(batch)) {
            // A third party is owed no burst; the rest keeps its place for the next pass.
            if (requests >= REQUESTS_PER_PASS) break@loop
            // Claim before the wire: a tail row stays PENDING within the undo window.
            val claimed = group.filter { dao.claim(it.id, OutboxState.PENDING, OutboxState.INFLIGHT) == 1 }
            if (claimed.isEmpty()) continue@loop
            val (request, vanished) = resolveGroup(claimed)
            for (row in vanished) { dao.delete(row.id); dropped++ }
            val rows = claimed - vanished.toSet()
            if (request == null) continue@loop
            if (unreachable) { rows.forEach { reschedule(it, now); retried++ }; continue@loop }

            // Replay: /api/v1 has no idempotency key, so a lost ack would double-count.
            if (rows.any { it.attempts > 0 || it.id in reclaimed } && bridge != null) {
                val already = runCatching { bridge.alreadyPosted(request) }.getOrDefault(false)
                if (already) {
                    Timber.tag(TAG).i("bridge group of %d already present; not re-posting", rows.size)
                    rows.forEach { dao.delete(it.id); sent++ }
                    continue@loop
                }
            }

            requests++
            val response = try {
                bridge?.execute(request) ?: throw NightscoutDisabledException()
            } catch (e: NightscoutDisabledException) {
                // Switched off while the row sat queued; nothing will ever send it.
                rows.forEach { dao.delete(it.id); dropped++ }
                continue@loop
            } catch (e: Exception) {
                rows.forEach { reschedule(it, now); retried++ }
                error = e.javaClass.simpleName
                unreachable = true
                continue@loop
            }
            when {
                response.ok -> rows.forEach { dao.delete(it.id); sent++ }
                response.authError -> {
                    rows.forEach { reschedule(it, now); retried++ }
                    error = "HTTP ${response.code} — secret rejected"
                }
                response.permanentClientError -> {
                    Timber.tag(TAG).w("dropping %d row(s): HTTP %d", rows.size, response.code)
                    rows.forEach { dao.delete(it.id); dropped++ }
                    error = "HTTP ${response.code}"
                }
                else -> {
                    rows.forEach { reschedule(it, now); retried++ }
                    error = "HTTP ${response.code}"
                }
            }
        }
        return DrainResult(sent = sent, dropped = dropped, retried = retried, nightscoutError = error)
    }

    /** BG markers ride one entries array; a treatment stays alone, its guard is per-row. */
    private fun groups(batch: List<OutboxEntity>): List<List<OutboxEntity>> {
        val (entries, rest) = batch.partition { it.dedupKey.startsWith(NS_ENTRY_DEDUP_PREFIX) }
        return entries.chunked(ENTRY_CHUNK) + rest.map { listOf(it) }
    }

    /** The group's one request, plus the rows whose slot vanished and which the caller drops. */
    private suspend fun resolveGroup(rows: List<OutboxEntity>): Pair<SyncRequest?, List<OutboxEntity>> {
        if (!rows[0].dedupKey.startsWith(NS_ENTRY_DEDUP_PREFIX)) {
            val request = resolve(rows[0])
            return if (request == null) null to rows else request to emptyList()
        }
        val vanished = mutableListOf<OutboxEntity>()
        val entries = mutableListOf<NsEntryDto>()
        for (row in rows) {
            val entry = resolveEntry(row)
            if (entry == null) vanished += row else entries += entry
        }
        if (entries.isEmpty()) return null to vanished
        val body = NsJson.encodeToString(entries).toByteArray()
        return SyncRequest("POST", "/api/v1/entries", body) to vanished
    }

    private suspend fun reschedule(row: OutboxEntity, now: Long) {
        val next = now + Backoff.delayMs(config, row.attempts, random())
        dao.reschedule(row.id, OutboxState.PENDING, row.attempts + 1, next)
    }

    private fun resolve(row: OutboxEntity): SyncRequest? = runCatching {
        NsJson.decodeFromString<OutboxRequest>(String(row.payload, Charsets.UTF_8)).toSyncRequest()
    }.getOrNull()

    /** A dirty marker, resolved now; a slot with no BG yields null and the row drops. */
    private suspend fun resolveEntry(row: OutboxEntity): NsEntryDto? {
        val ts = row.dedupKey.removePrefix(NS_ENTRY_DEDUP_PREFIX).toLongOrNull() ?: return null
        return sampleAt(ts)?.toNsEntry(trendAt(ts))
    }

    private companion object {
        const val TAG = "QueueDrainer"

        /** BG readings per entries POST; 100 is ~8 h of the five-minute grid. */
        const val ENTRY_CHUNK = 100

        /** Requests per pass. At a 60 s pass that is 4/min against a third party. */
        const val REQUESTS_PER_PASS = 4
    }
}
