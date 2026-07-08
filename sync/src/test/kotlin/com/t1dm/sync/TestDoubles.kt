package com.t1dm.sync

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.db.OutboxDao
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxEvictRow
import com.t1dm.data.db.OutboxState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** All work on Unconfined — the drainer has no delays, so virtual time is unnecessary. */
class TestDispatchers : T1dmDispatchers {
    override val main = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val inference = Dispatchers.Unconfined
}

/**
 * In-memory [OutboxDao] faithful to the Room contract that matters for the drainer: `enqueue`
 * honours the unique `dedupKey` (IGNORE on conflict), `dueBatch` filters by state + due time and
 * orders FIFO (`createdAtMs, id`), and `evictionRows` is oldest-first.
 */
class FakeOutboxDao : OutboxDao {
    private val rows = LinkedHashMap<Long, OutboxEntity>()
    private var seq = 0L
    private val depth = MutableStateFlow(0)

    override suspend fun enqueue(item: OutboxEntity): Long {
        if (rows.values.any { it.dedupKey == item.dedupKey }) return -1L // unique dedupKey → IGNORE
        val id = ++seq
        rows[id] = item.copy(id = id)
        depth.value = rows.size
        return id
    }

    override suspend fun dueBatch(state: OutboxState, nowMs: Long, limit: Int): List<OutboxEntity> =
        rows.values.filter { it.state == state && it.nextAttemptMs <= nowMs }
            .sortedWith(compareBy({ it.createdAtMs }, { it.id }))
            .take(limit)

    override fun observeDepth(): Flow<Int> = depth

    override suspend fun count(): Int = rows.size

    override suspend fun oldestCreatedAt(): Long? = rows.values.minOfOrNull { it.createdAtMs }

    override suspend fun evictionRows(): List<OutboxEvictRow> =
        rows.values.sortedWith(compareBy({ it.createdAtMs }, { it.id }))
            .map { OutboxEvictRow(it.id, it.kind, it.createdAtMs) }

    override suspend fun delete(id: Long) { rows.remove(id); depth.value = rows.size }

    override suspend fun deleteAll(ids: List<Long>): Int {
        val before = rows.size
        ids.forEach { rows.remove(it) }
        depth.value = rows.size
        return before - rows.size
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

    fun snapshot(): List<OutboxEntity> = rows.values.toList()
}

/** Records every executed request and returns a scripted response (or throws) per call. */
class RecordingHttpClient(
    private val handler: (SyncRequest, Int) -> SyncResponse,
) : SyncHttpClient {
    val requests = mutableListOf<SyncRequest>()

    override suspend fun execute(request: SyncRequest): SyncResponse {
        val idx = requests.size
        requests += request
        return handler(request, idx)
    }

    private fun nope(): Nothing = throw UnsupportedOperationException("not used in these tests")
    override suspend fun health() = nope()
    override suspend fun ingest(body: IngestDto) = nope()
    override suspend fun putPredictions(preds: List<PredictionWriteDto>) = nope()
    override suspend fun putSeries(name: String, body: SeriesPutDto) = nope()
    override suspend fun postNote(body: NoteWriteDto) = nope()
    override suspend fun postAlert(body: AlertWriteDto) = nope()
    override suspend fun getSeries(from: Long?, to: Long?, cursor: Long?, limit: Int?, fields: String?) = nope()
    override suspend fun getStats(window: String) = nope()
    override suspend fun postPhotoStub(): Nothing = nope()
}

fun ok() = SyncResponse(200, ByteArray(0))
fun status(code: Int) = SyncResponse(code, ByteArray(0))
