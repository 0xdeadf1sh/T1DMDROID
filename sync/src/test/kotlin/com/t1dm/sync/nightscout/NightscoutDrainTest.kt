package com.t1dm.sync.nightscout

import com.t1dm.data.db.NS_ENTRY_DEDUP_PREFIX
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
import com.t1dm.sync.DrainConfig
import com.t1dm.sync.FakeOutboxDao
import com.t1dm.sync.OutboxRequest
import com.t1dm.sync.QueueDrainer
import com.t1dm.sync.SyncRequest
import com.t1dm.sync.SyncResponse
import com.t1dm.sync.SyncHttpClient
import com.t1dm.sync.TestDispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NightscoutDrainTest {

    private val dispatchers = TestDispatchers()

    private class RecordingBridge(
        private val onExecute: (SyncRequest) -> SyncResponse,
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

    private class FakeServer(private val code: Int = 200) : SyncHttpClient {
        val requests = mutableListOf<SyncRequest>()
        override suspend fun execute(request: SyncRequest): SyncResponse {
            requests += request
            return SyncResponse(code, ByteArray(0))
        }
        override suspend fun health() = throw UnsupportedOperationException()
        override suspend fun ingest(body: com.t1dm.sync.IngestDto) = throw UnsupportedOperationException()
        override suspend fun putMeals(meals: List<com.t1dm.sync.MealEventDto>) = throw UnsupportedOperationException()
        override suspend fun putDoses(doses: List<com.t1dm.sync.DoseEventDto>) = throw UnsupportedOperationException()
        override suspend fun putBasalSchedule(body: com.t1dm.sync.BasalScheduleDto) = throw UnsupportedOperationException()
        override suspend fun putStats(body: com.t1dm.sync.StatsPushDto) = throw UnsupportedOperationException()
        override suspend fun postAlert(body: com.t1dm.sync.AlertWriteDto) = throw UnsupportedOperationException()
        override suspend fun getSeries(from: Long?, to: Long?, cursor: Long?, limit: Int?, fields: String?) = throw UnsupportedOperationException()
        override suspend fun getMeals(from: Long?, to: Long?) = throw UnsupportedOperationException()
        override suspend fun getDoses(from: Long?, to: Long?) = throw UnsupportedOperationException()
        override suspend fun getBasalSchedule() = throw UnsupportedOperationException()
        override suspend fun postPhoto(tsMs: Long, bytes: ByteArray, ext: String) = throw UnsupportedOperationException()
        override suspend fun listModels() = throw UnsupportedOperationException()
        override suspend fun downloadModel(id: String) = throw UnsupportedOperationException()
    }

    private fun treatmentRow(id: Long, state: OutboxState = OutboxState.PENDING, attempts: Int = 0) = OutboxEntity(
        kind = OutboxKind.NIGHTSCOUT,
        dedupKey = "ns:treat:cid-$id",
        payload = com.t1dm.sync.SyncJson.encodeToString(
            OutboxRequest.serializer(),
            OutboxRequest("POST", "/api/v1/treatments", """[{"eventType":"Correction Bolus","created_at":"2026-08-17T23:53:20+03:00","insulin":6.5,"notes":"cid-$id"}]"""),
        ).toByteArray(),
        createdAtMs = 10,
        attempts = attempts,
        nextAttemptMs = 0,
        state = state,
    )

    /** Deliberately not an INGEST marker: `sampleAt` is null here, so one would be dropped before the
     *  wire and could not witness whether the bridge disturbed it. */
    private fun serverRow(key: String) = OutboxEntity(
        kind = OutboxKind.ALERT,
        dedupKey = key,
        payload = com.t1dm.sync.SyncJson.encodeToString(
            OutboxRequest.serializer(),
            OutboxRequest("POST", "/v1/alerts", "{}"),
        ).toByteArray(),
        createdAtMs = 20,
        attempts = 0,
        nextAttemptMs = 0,
        state = OutboxState.PENDING,
    )

    private fun drainer(
        dao: FakeOutboxDao,
        http: SyncHttpClient,
        bridge: NightscoutClient?,
    ) = QueueDrainer(
        dao, http, { null }, dispatchers,
        DrainConfig(baseBackoffMs = 1_000, jitterFrac = 0.0, maxQueueSize = 100),
        { 10_000L }, { 0.0 }, bridge, { null },
    )

    /** `resetState` reclaims a crash-wedged INFLIGHT row WITHOUT advancing `attempts`, so keying the
     *  replay check on `attempts` alone would re-POST a bolus the host already holds. */
    @Test
    fun `a row reclaimed from INFLIGHT is treated as a replay`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(treatmentRow(1, state = OutboxState.INFLIGHT, attempts = 0))
        val bridge = RecordingBridge({ SyncResponse(200, ByteArray(0)) }, posted = true)

        val result = drainer(dao, FakeServer(), bridge).drainOnce()

        assertEquals("the guard must be consulted", 1, bridge.alreadyPostedCalls)
        assertTrue("the treatment must not be POSTed again", bridge.requests.isEmpty())
        assertEquals(1, result.sent)
    }

    @Test
    fun `a first attempt skips the guard and posts`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(treatmentRow(1))
        val bridge = RecordingBridge({ SyncResponse(200, ByteArray(0)) })

        drainer(dao, FakeServer(), bridge).drainOnce()

        assertEquals(0, bridge.alreadyPostedCalls)
        assertEquals(1, bridge.requests.size)
    }

    @Test
    fun `a bridge 401 backs off one row and never stands the queue down`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(treatmentRow(1))
        dao.enqueue(serverRow("alert:1"))
        val bridge = RecordingBridge({ SyncResponse(401, ByteArray(0)) })
        val server = FakeServer()

        val result = drainer(dao, server, bridge).drainOnce()

        assertEquals("the queue must not stand down", null, result.standDown)
        assertTrue("the server row must still have been attempted", server.requests.isNotEmpty())
        assertTrue(result.nightscoutError!!.contains("401"))
    }

    /** The batch is FIFO and interleaved: one unreachable bridge must cost the pass one timeout, not
     *  one per bridged row. */
    @Test
    fun `one bridge transport failure stands the bridge down for the rest of the pass`() = runTest {
        val dao = FakeOutboxDao()
        repeat(4) { dao.enqueue(treatmentRow(it + 1L)) }
        dao.enqueue(serverRow("alert:1"))
        val bridge = RecordingBridge({ throw IOException("unreachable") })
        val server = FakeServer()

        val result = drainer(dao, server, bridge).drainOnce()

        assertEquals("only the first bridged row may touch the wire", 1, bridge.requests.size)
        assertEquals("every bridged row is still retried", 4, result.retried)
        assertTrue("the server row is unaffected", server.requests.isNotEmpty())
    }

    @Test
    fun `rows for a disabled bridge are dropped, not retried`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(treatmentRow(1))
        val bridge = RecordingBridge({ throw NightscoutDisabledException() })

        val result = drainer(dao, FakeServer(), bridge).drainOnce()

        assertEquals(1, result.dropped)
        assertEquals(0, result.retried)
        assertEquals(0, dao.count())
    }

    @Test
    fun `a bg marker with no sample is dropped`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(
            OutboxEntity(
                kind = OutboxKind.NIGHTSCOUT,
                dedupKey = "${NS_ENTRY_DEDUP_PREFIX}1787000000000",
                payload = ByteArray(0),
                createdAtMs = 10,
                attempts = 0,
                nextAttemptMs = 0,
                state = OutboxState.PENDING,
            ),
        )
        val bridge = RecordingBridge({ SyncResponse(200, ByteArray(0)) })

        val result = drainer(dao, FakeServer(), bridge).drainOnce()

        assertEquals(1, result.dropped)
        assertTrue(bridge.requests.isEmpty())
    }
}
