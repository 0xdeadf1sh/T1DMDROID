package com.t1dm.sync.nightscout

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.NS_ENTRY_DEDUP_PREFIX
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
import com.t1dm.data.db.SampleEntity
import com.t1dm.sync.DrainConfig
import com.t1dm.sync.FakeOutboxDao
import com.t1dm.sync.OutboxRequest
import com.t1dm.sync.QueueDrainer
import com.t1dm.sync.RecordingBridge
import com.t1dm.sync.SyncRequest
import com.t1dm.sync.SyncResponse
import com.t1dm.sync.TestDispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NightscoutDrainTest {

    private val dispatchers = TestDispatchers()

    private fun treatmentRow(id: Long, state: OutboxState = OutboxState.PENDING, attempts: Int = 0) = OutboxEntity(
        kind = OutboxKind.NIGHTSCOUT,
        dedupKey = "ns:treat:cid-$id",
        payload = NsJson.encodeToString(
            OutboxRequest.serializer(),
            OutboxRequest("POST", "/api/v1/treatments", """[{"eventType":"Correction Bolus","created_at":"2026-08-17T23:53:20+03:00","insulin":6.5,"notes":"cid-$id"}]"""),
        ).toByteArray(),
        createdAtMs = 10,
        attempts = attempts,
        nextAttemptMs = 0,
        state = state,
    )

    private fun entryRow(ts: Long) = OutboxEntity(
        kind = OutboxKind.NIGHTSCOUT,
        dedupKey = "$NS_ENTRY_DEDUP_PREFIX$ts",
        payload = ByteArray(0),
        createdAtMs = ts,
        attempts = 0,
        nextAttemptMs = 0,
        state = OutboxState.PENDING,
    )

    private fun sampleAt(ts: Long) = SampleEntity(
        ts = ts,
        tzOffsetMin = 240,
        bgMgdl = 120,
        bgSource = "src",
        bgProvenance = ReadingProvenance.MEASURED,
        bgFlag = ReadingFlag.NORMAL,
        steps = null,
        mood = null,
        hr = null,
        sleep = null,
        exercise = null,
        updatedAt = ts,
    )

    private fun entriesOf(request: SyncRequest): List<NsEntryDto> =
        NsJson.decodeFromString(String(request.body!!))

    private fun drainer(
        dao: FakeOutboxDao,
        bridge: NightscoutClient?,
        sampleAt: suspend (Long) -> SampleEntity? = { null },
    ) = QueueDrainer(
        dao, sampleAt, dispatchers,
        DrainConfig(baseBackoffMs = 1_000, jitterFrac = 0.0, maxQueueSize = 100),
        { 10_000L }, { 0.0 }, bridge, { null },
    )

    /** One POST per reading is a burst a third-party host is not owed. */
    @Test
    fun `bg markers ride one entries POST`() = runTest {
        val dao = FakeOutboxDao()
        repeat(3) { dao.enqueue(entryRow(1_787_000_000_000L + it * 300_000L)) }
        val bridge = RecordingBridge({ SyncResponse(200, ByteArray(0)) })

        val result = drainer(dao, bridge, sampleAt = ::sampleAt).drainOnce()

        assertEquals("three readings, one request", 1, bridge.requests.size)
        val body = NsJson.decodeFromString<List<NsEntryDto>>(String(bridge.requests[0].body!!))
        assertEquals(3, body.size)
        assertEquals(3, result.sent)
        assertEquals(0, dao.count())
    }

    @Test
    fun `the bridge sends no more than its per-pass request budget`() = runTest {
        val dao = FakeOutboxDao()
        repeat(10) { dao.enqueue(treatmentRow(it + 1L)) }
        val bridge = RecordingBridge({ SyncResponse(200, ByteArray(0)) })

        val result = drainer(dao, bridge).drainOnce()

        assertEquals(2, bridge.requests.size)
        assertEquals(2, result.sent)
        assertEquals("the rest keeps its place for the next pass", 8, dao.count())
    }

    /** A backlog must not arrive as one burst: 25 a POST, 2 POSTs a pass, 50 readings a minute. */
    @Test
    fun `a backlog of bg readings is chunked, not sent whole`() = runTest {
        val dao = FakeOutboxDao()
        repeat(60) { dao.enqueue(entryRow(1_787_000_000_000L + it * 300_000L)) }
        val bridge = RecordingBridge({ SyncResponse(200, ByteArray(0)) })

        val result = drainer(dao, bridge, ::sampleAt).drainOnce()

        assertEquals(2, bridge.requests.size)
        assertEquals(25, entriesOf(bridge.requests[0]).size)
        assertEquals(25, entriesOf(bridge.requests[1]).size)
        assertEquals(50, result.sent)
        assertEquals("the tail waits for the next pass", 10, dao.count())
    }

    /** resetState reclaims INFLIGHT WITHOUT advancing attempts; attempts alone would re-POST. */
    @Test
    fun `a row reclaimed from INFLIGHT is treated as a replay`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(treatmentRow(1, state = OutboxState.INFLIGHT, attempts = 0))
        val bridge = RecordingBridge({ SyncResponse(200, ByteArray(0)) }, posted = true)

        val result = drainer(dao, bridge).drainOnce()

        assertEquals("the guard must be consulted", 1, bridge.alreadyPostedCalls)
        assertTrue("the treatment must not be POSTed again", bridge.requests.isEmpty())
        assertEquals(1, result.sent)
    }

    @Test
    fun `a first attempt skips the guard and posts`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(treatmentRow(1))
        val bridge = RecordingBridge({ SyncResponse(200, ByteArray(0)) })

        drainer(dao, bridge).drainOnce()

        assertEquals(0, bridge.alreadyPostedCalls)
        assertEquals(1, bridge.requests.size)
    }

    @Test
    fun `a bridge 401 backs off the row instead of dropping it`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(treatmentRow(1))
        val bridge = RecordingBridge({ SyncResponse(401, ByteArray(0)) })

        val result = drainer(dao, bridge).drainOnce()

        assertEquals(1, result.retried)
        assertEquals(1, dao.count())
        assertTrue(result.nightscoutError!!.contains("401"))
    }

    /** Batch is FIFO: one unreachable bridge costs one timeout, not one per row. */
    @Test
    fun `one bridge transport failure stands the bridge down for the rest of the pass`() = runTest {
        val dao = FakeOutboxDao()
        repeat(4) { dao.enqueue(treatmentRow(it + 1L)) }
        val bridge = RecordingBridge({ throw IOException("unreachable") })

        val result = drainer(dao, bridge).drainOnce()

        assertEquals("only the first bridged row may touch the wire", 1, bridge.requests.size)
        assertEquals("every bridged row is still retried", 4, result.retried)
    }

    @Test
    fun `rows for a disabled bridge are dropped, not retried`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(treatmentRow(1))
        val bridge = RecordingBridge({ throw NightscoutDisabledException() })

        val result = drainer(dao, bridge).drainOnce()

        assertEquals(1, result.dropped)
        assertEquals(0, result.retried)
        assertEquals(0, dao.count())
    }

    @Test
    fun `a bg marker with no sample is dropped`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(entryRow(1_787_000_000_000L))
        val bridge = RecordingBridge({ SyncResponse(200, ByteArray(0)) })

        val result = drainer(dao, bridge).drainOnce()

        assertEquals(1, result.dropped)
        assertTrue(bridge.requests.isEmpty())
    }
}
