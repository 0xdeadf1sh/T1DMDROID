package com.t1dm.sync

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.OutboxDao
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxEvictRow
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
import com.t1dm.data.db.SampleEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDrainerTest {

    private val dispatchers = TestDispatchers()

    private fun envelope(path: String, body: String = "{}") =
        SyncJson.encodeToString(OutboxRequest("POST", path, body)).toByteArray()

    private fun sample(ts: Long, bg: Int) = SampleEntity(
        ts = ts, tzOffsetMin = 0, bgMgdl = bg,
        bgProvenance = ReadingProvenance.MEASURED, bgFlag = ReadingFlag.NORMAL,
        steps = null, mood = null,
        hr = null, sleep = null, exercise = null, updatedAt = ts,
    )

    private fun drainer(
        dao: FakeOutboxDao,
        http: SyncHttpClient,
        config: DrainConfig = DrainConfig(baseBackoffMs = 1_000, jitterFrac = 0.0, maxQueueSize = 100),
        clock: () -> Long = { 10_000L },
        sampleAt: suspend (Long) -> SampleEntity? = { null },
    ) = QueueDrainer(dao, http, sampleAt, dispatchers, config, clock, random = { 0.0 })

    @Test
    fun drainsFifoAndDeletesOnSuccess() = runTest {
        val dao = FakeOutboxDao()
        // Insertion order is scrambled; FIFO is by createdAtMs, so drain order must be 10,20,30.
        dao.enqueue(OutboxEntity(kind = OutboxKind.ALERT, dedupKey = "c", payload = envelope("/c"), createdAtMs = 30, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        dao.enqueue(OutboxEntity(kind = OutboxKind.ALERT, dedupKey = "a", payload = envelope("/a"), createdAtMs = 10, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        dao.enqueue(OutboxEntity(kind = OutboxKind.ALERT, dedupKey = "b", payload = envelope("/b"), createdAtMs = 20, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val http = RecordingHttpClient { _, _ -> ok() }

        val result = drainer(dao, http).drainOnce()

        assertEquals(listOf("/a", "/b", "/c"), http.requests.map { it.path })
        assertEquals(3, result.sent)
        assertEquals(0, dao.count())
    }

    @Test
    fun transientFailureReschedulesWithExponentialBackoff() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(OutboxEntity(kind = OutboxKind.ALERT, dedupKey = "n", payload = envelope("/v1/alerts"), createdAtMs = 1, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val http = RecordingHttpClient { _, _ -> status(500) }
        var now = 10_000L
        val d = drainer(dao, http, clock = { now })

        val r1 = d.drainOnce()
        assertEquals(1, r1.retried)
        val row1 = dao.snapshot().single()
        assertEquals(OutboxState.PENDING, row1.state)
        assertEquals(1, row1.attempts)
        assertEquals(10_000L + 1_000L, row1.nextAttemptMs) // base·2^0, jitter off

        // Not due yet at the same instant → a second pass attempts nothing.
        assertEquals(0, d.drainOnce().retried)

        now = row1.nextAttemptMs
        d.drainOnce()
        val row2 = dao.snapshot().single()
        assertEquals(2, row2.attempts)
        assertEquals(now + 2_000L, row2.nextAttemptMs) // base·2^1
    }

    /**
     * The empty queue — the steady state, and the one the timer, the grid tick and every WS reconnect
     * all keep asking about — costs one COUNT and nothing else, and answers exactly what the full pass
     * answered: an all-zero [DrainResult].
     */
    @Test
    fun emptyQueueCostsOneCountAndNoTableWork() = runTest {
        val dao = CountingOutboxDao(FakeOutboxDao())
        val http = RecordingHttpClient { _, _ -> ok() }

        val r = QueueDrainer(dao, http, { null }, dispatchers, DrainConfig(), { 10_000L }, { 0.0 }).drainOnce()

        assertEquals(DrainResult(), r)
        assertEquals(0, dao.resetStateCalls)
        assertEquals(0, dao.evictionRowsCalls)
        assertEquals(0, dao.dueBatchCalls)
        assertTrue(http.requests.isEmpty())
    }

    /** A queue holding only a crash-wedged INFLIGHT row has nothing DUE, but it is not empty — the pass
     *  must still run, or the row would never be reclaimed to PENDING and would never be sent again. */
    @Test
    fun inflightOnlyQueueStillRunsTheFullPass() = runTest {
        val inner = FakeOutboxDao()
        inner.enqueue(OutboxEntity(kind = OutboxKind.ALERT, dedupKey = "w", payload = envelope("/w"), createdAtMs = 1, attempts = 0, nextAttemptMs = 0, state = OutboxState.INFLIGHT))
        val dao = CountingOutboxDao(inner)
        val http = RecordingHttpClient { _, _ -> ok() }

        val r = QueueDrainer(dao, http, { null }, dispatchers, DrainConfig(), { 10_000L }, { 0.0 }).drainOnce()

        assertEquals(1, dao.resetStateCalls)
        assertEquals(1, dao.evictionRowsCalls)
        assertEquals(1, dao.dueBatchCalls)
        assertEquals(1, r.sent)
        assertEquals(0, inner.count())
    }

    /** Counts the table operations one pass issues, so "the empty pass touches nothing" is asserted
     *  rather than assumed. Everything not counted delegates to the real fake. */
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
        dao.enqueue(OutboxEntity(kind = OutboxKind.SERIES, dedupKey = "s", payload = envelope("/v1/series/carbs"), createdAtMs = 1, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val http = RecordingHttpClient { _, _ -> status(400) }

        val r = drainer(dao, http).drainOnce()

        assertEquals(1, r.dropped)
        assertEquals(0, dao.count())
    }

    @Test
    fun authErrorStandsDownWithoutDropping() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(OutboxEntity(kind = OutboxKind.ALERT, dedupKey = "a", payload = envelope("/a"), createdAtMs = 1, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        dao.enqueue(OutboxEntity(kind = OutboxKind.ALERT, dedupKey = "b", payload = envelope("/b"), createdAtMs = 2, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val http = RecordingHttpClient { _, _ -> status(401) }

        val r = drainer(dao, http).drainOnce()

        assertEquals(DrainResult.StandDown.AUTH, r.standDown)
        assertEquals(0, r.sent)
        assertEquals(2, dao.count())                         // nothing dropped
        assertEquals(1, http.requests.size)                  // broke after the first 401
        assertTrue(dao.snapshot().all { it.state == OutboxState.PENDING })
    }

    @Test
    fun noActiveProfileStandsDown() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(OutboxEntity(kind = OutboxKind.INGEST, dedupKey = "${INGEST_DEDUP_PREFIX}300000", payload = ByteArray(0), createdAtMs = 1, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val http = RecordingHttpClient { _, _ -> throw NoActiveProfileException() }

        val r = drainer(dao, http, sampleAt = { sample(it, 120) }).drainOnce()

        assertEquals(DrainResult.StandDown.NO_PROFILE, r.standDown)
        assertEquals(1, dao.count())
        assertEquals(OutboxState.PENDING, dao.snapshot().single().state)
    }

    @Test
    fun ingestResolvesCurrentSampleAtDrainTime() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(OutboxEntity(kind = OutboxKind.INGEST, dedupKey = "${INGEST_DEDUP_PREFIX}300000", payload = ByteArray(0), createdAtMs = 1, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val http = RecordingHttpClient { _, _ -> ok() }

        val r = drainer(dao, http, sampleAt = { ts -> sample(ts, 140) }).drainOnce()

        assertEquals(1, r.sent)
        assertEquals("/v1/ingest", http.requests.single().path)
        assertTrue(String(http.requests.single().body!!).contains("\"bg\":140.0"))
    }

    @Test
    fun ingestForVanishedSlotIsDropped() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(OutboxEntity(kind = OutboxKind.INGEST, dedupKey = "${INGEST_DEDUP_PREFIX}300000", payload = ByteArray(0), createdAtMs = 1, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val http = RecordingHttpClient { _, _ -> ok() }

        val r = drainer(dao, http, sampleAt = { null }).drainOnce()

        assertEquals(1, r.dropped)
        assertEquals(0, http.requests.size)
        assertEquals(0, dao.count())
    }

    @Test
    fun rowWithdrawnAfterTheBatchSnapshotIsNeverSent() = runTest {
        // `dueBatch` snapshots the whole pass up front, so a tail row stays PENDING on disk for as
        // long as the rows ahead of it take to send — the window in which an undo can withdraw it and
        // report WITHDRAWN ("nothing about this event left the phone"). Claiming PENDING→INFLIGHT
        // conditionally is what keeps that receipt honest.
        val dao = FakeOutboxDao()
        dao.enqueue(OutboxEntity(kind = OutboxKind.ALERT, dedupKey = "a", payload = envelope("/a"), createdAtMs = 10, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val doseId = dao.enqueue(OutboxEntity(kind = OutboxKind.DOSE, dedupKey = "dose:x", payload = envelope("/v1/doses"), createdAtMs = 20, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val http = object : NoopSyncHttpClient() {
            val paths = mutableListOf<String>()
            override suspend fun execute(request: SyncRequest): SyncResponse {
                paths += request.path
                dao.delete(doseId) // the undo lands while the row ahead is still on the wire
                return ok()
            }
        }

        val r = drainer(dao, http).drainOnce()

        assertEquals(listOf("/a"), http.paths)
        assertEquals(1, r.sent)
        assertEquals(0, dao.count())
    }

    @Test
    fun dedupKeyRejectsDuplicateEnqueue() = runTest {
        val dao = FakeOutboxDao()
        val a = dao.enqueue(OutboxEntity(kind = OutboxKind.INGEST, dedupKey = "${INGEST_DEDUP_PREFIX}300000", payload = ByteArray(0), createdAtMs = 1, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val b = dao.enqueue(OutboxEntity(kind = OutboxKind.INGEST, dedupKey = "${INGEST_DEDUP_PREFIX}300000", payload = ByteArray(0), createdAtMs = 2, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))

        assertTrue(a > 0)
        assertEquals(-1L, b)          // IGNORE on the unique dedupKey
        assertEquals(1, dao.count())
    }

    @Test
    fun sizeEvictionDropsLowestPriorityOldestFirst() = runTest {
        val dao = FakeOutboxDao()
        // 4 rows, cap 2 → drop 2. Priority ALERT(7) > MEAL(5) > INGEST(4) > PREDICTIONS(2) > SERIES(1) > PHOTO(0).
        dao.enqueue(OutboxEntity(kind = OutboxKind.ALERT, dedupKey = "1", payload = ByteArray(0), createdAtMs = 10, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        dao.enqueue(OutboxEntity(kind = OutboxKind.PHOTO, dedupKey = "2", payload = ByteArray(0), createdAtMs = 20, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        dao.enqueue(OutboxEntity(kind = OutboxKind.SERIES, dedupKey = "3", payload = ByteArray(0), createdAtMs = 30, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        dao.enqueue(OutboxEntity(kind = OutboxKind.MEAL, dedupKey = "4", payload = ByteArray(0), createdAtMs = 40, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val d = drainer(dao, RecordingHttpClient { _, _ -> ok() }, config = DrainConfig(maxQueueSize = 2, maxAgeMs = Long.MAX_VALUE))

        val evicted = d.evict(nowMs = 100)

        assertEquals(2, evicted)
        // PHOTO(0) then SERIES(1) go first; ALERT + MEAL survive.
        val kinds = dao.snapshot().map { it.kind }.toSet()
        assertEquals(setOf(OutboxKind.ALERT, OutboxKind.MEAL), kinds)
    }

    @Test
    fun ageEvictionSparesNonEvictableClinicalKindsButExpiresRegenerable() = runTest {
        // §3.7: age-eviction is gated on `ageEvictable`. An irreplaceable ALERT (not ageEvictable)
        // survives past `maxAgeMs`; a regenerable PHOTO expires — priority is irrelevant to age.
        val dao = FakeOutboxDao()
        dao.enqueue(OutboxEntity(kind = OutboxKind.ALERT, dedupKey = "old", payload = ByteArray(0), createdAtMs = 0, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        dao.enqueue(OutboxEntity(kind = OutboxKind.PHOTO, dedupKey = "old-photo", payload = ByteArray(0), createdAtMs = 0, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        dao.enqueue(OutboxEntity(kind = OutboxKind.PHOTO, dedupKey = "new", payload = ByteArray(0), createdAtMs = 9_000, attempts = 0, nextAttemptMs = 0, state = OutboxState.PENDING))
        val d = drainer(dao, RecordingHttpClient { _, _ -> ok() }, config = DrainConfig(maxQueueSize = 100, maxAgeMs = 5_000))

        val evicted = d.evict(nowMs = 10_000)   // cutoff = 5_000; both ts-0 rows are old

        assertEquals(1, evicted)   // only the expired PHOTO is dropped; the expired ALERT is spared
        assertEquals(setOf(OutboxKind.ALERT, OutboxKind.PHOTO), dao.snapshot().map { it.kind }.toSet())
        assertEquals(listOf("old", "new"), dao.snapshot().map { it.dedupKey })
    }
}
