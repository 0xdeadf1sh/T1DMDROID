package com.t1dm.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.db.OutboxDao
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxEvictRow
import com.t1dm.data.db.OutboxState
import com.t1dm.sync.nightscout.NightscoutClient
import kotlinx.coroutines.Dispatchers

class TestDispatchers : T1dmDispatchers {
    override val main = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val inference = Dispatchers.Unconfined
    override val game = Dispatchers.Unconfined
}

/** Room contract the drainer leans on: unique dedupKey, FIFO dueBatch, ids never reused. */
class FakeOutboxDao : OutboxDao {
    private val rows = LinkedHashMap<Long, OutboxEntity>()
    private var seq = 0L

    override suspend fun enqueue(item: OutboxEntity): Long {
        if (rows.values.any { it.dedupKey == item.dedupKey }) return -1L
        val id = ++seq
        rows[id] = item.copy(id = id)
        return id
    }

    override suspend fun dueBatch(state: OutboxState, nowMs: Long, limit: Int): List<OutboxEntity> =
        rows.values.filter { it.state == state && it.nextAttemptMs <= nowMs }
            .sortedWith(compareBy({ it.createdAtMs }, { it.id }))
            .take(limit)

    override suspend fun count(): Int = rows.size

    override suspend fun evictionRows(): List<OutboxEvictRow> =
        rows.values.sortedWith(compareBy({ it.createdAtMs }, { it.id }))
            .map { OutboxEvictRow(it.id, it.kind, it.createdAtMs) }

    override suspend fun byId(id: Long): OutboxEntity? = rows[id]

    override suspend fun byDedupKey(dedupKey: String): OutboxEntity? =
        rows.values.firstOrNull { it.dedupKey == dedupKey }

    override suspend fun deleteByDedupKey(dedupKey: String): Int {
        val hit = rows.values.filter { it.dedupKey == dedupKey }
        hit.forEach { rows.remove(it.id) }
        return hit.size
    }

    override suspend fun delete(id: Long) { rows.remove(id) }

    override suspend fun deleteByDedupKeyInState(dedupKey: String, state: OutboxState): Int {
        val doomed = rows.values.filter { it.dedupKey == dedupKey && it.state == state }.map { it.id }
        doomed.forEach { rows.remove(it) }
        return doomed.size
    }

    override suspend fun deleteAll(ids: List<Long>): Int {
        val before = rows.size
        ids.forEach { rows.remove(it) }
        return before - rows.size
    }

    override suspend fun idsInState(state: OutboxState): List<Long> =
        rows.values.filter { it.state == state }.map { it.id }

    override suspend fun deleteAllRows() {
        rows.clear()
    }

    override suspend fun resetState(from: OutboxState, to: OutboxState): Int {
        var n = 0
        rows.keys.toList().forEach { k ->
            val r = rows.getValue(k)
            if (r.state == from) { rows[k] = r.copy(state = to); n++ }
        }
        return n
    }

    override suspend fun reschedule(id: Long, state: OutboxState, attempts: Int, nextAttemptMs: Long) {
        rows[id]?.let { rows[id] = it.copy(state = state, attempts = attempts, nextAttemptMs = nextAttemptMs) }
    }

    override suspend fun claim(id: Long, from: OutboxState, to: OutboxState): Int {
        val r = rows[id] ?: return 0
        if (r.state != from) return 0
        rows[id] = r.copy(state = to)
        return 1
    }

    fun snapshot(): List<OutboxEntity> = rows.values.toList()
}

class RecordingBridge(
    private val onExecute: (SyncRequest) -> SyncResponse = { ok() },
    var posted: Boolean = false,
) : NightscoutClient {
    val requests = mutableListOf<SyncRequest>()
    var alreadyPostedCalls = 0

    override suspend fun execute(request: SyncRequest): SyncResponse {
        requests += request
        return onExecute(request)
    }

    override suspend fun probe() = "ok"

    override suspend fun alreadyPosted(request: SyncRequest): Boolean {
        alreadyPostedCalls++
        return posted
    }
}

fun ok() = SyncResponse(200, ByteArray(0))
fun status(code: Int) = SyncResponse(code, ByteArray(0))
