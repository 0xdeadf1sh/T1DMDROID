package com.t1dm.sync

import com.t1dm.data.db.OutboxDao
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxEvictRow
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
import com.t1dm.sync.nightscout.NightscoutClient
import com.t1dm.sync.nightscout.NsJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDrainerTest {

    private val dispatchers = TestDispatchers()

    private fun envelope(path: String, body: String = "[]") =
        NsJson.encodeToString(OutboxRequest("POST", path, body)).toByteArray()

    private fun row(
        key: String,
        createdAtMs: Long,
        path: String = "/api/v1/treatments",
        state: OutboxState = OutboxState.PENDING,
    ) = OutboxEntity(
        kind = OutboxKind.NIGHTSCOUT,
        dedupKey = key,
        payload = envelope(path),
        createdAtMs = createdAtMs,
        attempts = 0,
        nextAttemptMs = 0,
        state = state,
    )

    private fun drainer(
        dao: OutboxDao,
        bridge: NightscoutClient,
        config: DrainConfig = DrainConfig(baseBackoffMs = 1_000, jitterFrac = 0.0, maxQueueSize = 100),
        clock: () -> Long = { 10_000L },
    ) = QueueDrainer(dao, { null }, dispatchers, config, clock, random = { 0.0 }, nightscout = bridge)

    @Test
    fun drainsFifoAndDeletesOnSuccess() = runTest {
        val dao = FakeOutboxDao()
        // FIFO is by createdAtMs, not insertion order.
        dao.enqueue(row("c", 30, "/c"))
        dao.enqueue(row("a", 10, "/a"))
        dao.enqueue(row("b", 20, "/b"))
        val bridge = RecordingBridge()

        val result = drainer(dao, bridge).drainOnce()

        assertEquals("oldest first, and the budget stops at two", listOf("/a", "/b"), bridge.requests.map { it.path })
        assertEquals(2, result.sent)
        assertEquals("/c keeps its place for the next pass", 1, dao.count())
    }

    @Test
    fun transientFailureReschedulesWithExponentialBackoff() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(row("n", 1))
        val bridge = RecordingBridge({ status(500) })
        var now = 10_000L
        val d = drainer(dao, bridge, clock = { now })

        val r1 = d.drainOnce()
        assertEquals(1, r1.retried)
        val row1 = dao.snapshot().single()
        assertEquals(OutboxState.PENDING, row1.state)
        assertEquals(1, row1.attempts)
        assertEquals(10_000L + 1_000L, row1.nextAttemptMs) // base·2^0, jitter off

        assertEquals(0, d.drainOnce().retried)

        now = row1.nextAttemptMs
        d.drainOnce()
        val row2 = dao.snapshot().single()
        assertEquals(2, row2.attempts)
        assertEquals(now + 2_000L, row2.nextAttemptMs) // base·2^1
    }

    @Test
    fun emptyQueueCostsOneCountAndNoTableWork() = runTest {
        val dao = CountingOutboxDao(FakeOutboxDao())
        val bridge = RecordingBridge()

        val r = drainer(dao, bridge).drainOnce()

        assertEquals(DrainResult(), r)
        assertEquals(0, dao.resetStateCalls)
        assertEquals(0, dao.evictionRowsCalls)
        assertEquals(0, dao.dueBatchCalls)
        assertTrue(bridge.requests.isEmpty())
    }

    /** Nothing is DUE, but the pass must run or the wedged row is never reclaimed to PENDING. */
    @Test
    fun inflightOnlyQueueStillRunsTheFullPass() = runTest {
        val inner = FakeOutboxDao()
        inner.enqueue(row("w", 1, "/w", state = OutboxState.INFLIGHT))
        val dao = CountingOutboxDao(inner)

        val r = drainer(dao, RecordingBridge()).drainOnce()

        assertEquals(1, dao.resetStateCalls)
        assertEquals(1, dao.evictionRowsCalls)
        assertEquals(1, dao.dueBatchCalls)
        assertEquals(1, r.sent)
        assertEquals(0, inner.count())
    }

    private class CountingOutboxDao(private val inner: FakeOutboxDao) : OutboxDao by inner {
        var resetStateCalls = 0
        var evictionRowsCalls = 0
        var dueBatchCalls = 0

        override suspend fun resetState(from: OutboxState, to: OutboxState): Int {
            resetStateCalls++
            return inner.resetState(from, to)
        }

        override suspend fun evictionRows(): List<OutboxEvictRow> {
            evictionRowsCalls++
            return inner.evictionRows()
        }

        override suspend fun dueBatch(state: OutboxState, nowMs: Long, limit: Int): List<OutboxEntity> {
            dueBatchCalls++
            return inner.dueBatch(state, nowMs, limit)
        }
    }

    @Test
    fun permanentClientErrorIsDropped() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(row("s", 1))

        val r = drainer(dao, RecordingBridge({ status(400) })).drainOnce()

        assertEquals(1, r.dropped)
        assertEquals(0, dao.count())
    }

    @Test
    fun rowWithdrawnAfterTheBatchSnapshotIsNeverSent() = runTest {
        // dueBatch snapshots up front; conditional PENDING->INFLIGHT keeps undo's WITHDRAWN honest.
        val dao = FakeOutboxDao()
        dao.enqueue(row("a", 10, "/a"))
        val withdrawnId = dao.enqueue(row("ns:treat:x", 20, "/x"))
        val bridge = object : NightscoutClient {
            val paths = mutableListOf<String>()
            override suspend fun execute(request: SyncRequest): SyncResponse {
                paths += request.path
                dao.delete(withdrawnId) // the undo lands while the row ahead is still on the wire
                return ok()
            }
            override suspend fun probe() = "ok"
            override suspend fun alreadyPosted(request: SyncRequest) = false
        }

        val r = drainer(dao, bridge).drainOnce()

        assertEquals(listOf("/a"), bridge.paths)
        assertEquals(1, r.sent)
        assertEquals(0, dao.count())
    }

    @Test
    fun dedupKeyRejectsDuplicateEnqueue() = runTest {
        val dao = FakeOutboxDao()
        val a = dao.enqueue(row("ns:entry:300000", 1))
        val b = dao.enqueue(row("ns:entry:300000", 2))

        assertTrue(a > 0)
        assertEquals(-1L, b)          // IGNORE on the unique dedupKey
        assertEquals(1, dao.count())
    }

    @Test
    fun sizeEvictionDropsOldestFirst() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(row("1", 10))
        dao.enqueue(row("2", 20))
        dao.enqueue(row("3", 30))
        dao.enqueue(row("4", 40))
        val d = drainer(dao, RecordingBridge(), config = DrainConfig(maxQueueSize = 2, maxAgeMs = Long.MAX_VALUE))

        val evicted = d.evict(nowMs = 100)

        assertEquals(2, evicted)
        assertEquals(listOf("3", "4"), dao.snapshot().map { it.dedupKey })
    }

    @Test
    fun ageEvictionExpiresRowsPastTheBound() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(row("old", 0))
        dao.enqueue(row("old-2", 0))
        dao.enqueue(row("new", 9_000))
        val d = drainer(dao, RecordingBridge(), config = DrainConfig(maxQueueSize = 100, maxAgeMs = 5_000))

        val evicted = d.evict(nowMs = 10_000)   // cutoff = 5_000; both ts-0 rows are old

        assertEquals(2, evicted)
        assertEquals(listOf("new"), dao.snapshot().map { it.dedupKey })
    }
}
