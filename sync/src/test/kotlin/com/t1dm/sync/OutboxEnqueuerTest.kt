package com.t1dm.sync

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.data.OutboxSink
import com.t1dm.data.db.OutboxKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingSink : OutboxSink {
    data class Row(
        val kind: OutboxKind,
        val dedupKey: String,
        val request: OutboxRequest,
        val notBeforeMs: Long,
    )

    val rows = mutableListOf<Row>()

    override suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long {
        val request = SyncJson.decodeFromString<OutboxRequest>(payload.toString(Charsets.UTF_8))
        rows += Row(kind, dedupKey, request, notBeforeMs)
        return rows.size.toLong()
    }

    override suspend fun enqueueSuperseding(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long {
        rows.removeAll { it.dedupKey == dedupKey }
        return enqueue(kind, dedupKey, payload, nowMs, notBeforeMs)
    }

    override suspend fun enqueueReplacingPending(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long {
        rows.removeAll { it.dedupKey == dedupKey }
        return enqueue(kind, dedupKey, payload, nowMs, notBeforeMs)
    }
}

/** Faithful to Room: dedupKey UNIQUE (taken ⇒ -1), INFLIGHT=drainers claim, ids strictly rise. */
private class QueueSink : OutboxSink {
    data class Row(
        val id: Long,
        val kind: OutboxKind,
        val dedupKey: String,
        val request: OutboxRequest,
        var inflight: Boolean = false,
    )

    val rows = mutableListOf<Row>()
    private var seq = 0L

    fun claim(dedupKey: String) {
        rows.first { it.dedupKey == dedupKey }.inflight = true
    }

    fun bodyUnder(dedupKey: String): String = rows.first { it.dedupKey == dedupKey }.request.body

    override suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long {
        if (rows.any { it.dedupKey == dedupKey }) return -1L
        val request = SyncJson.decodeFromString<OutboxRequest>(payload.toString(Charsets.UTF_8))
        rows += Row(++seq, kind, dedupKey, request)
        return seq
    }

    override suspend fun enqueueReplacingPending(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long {
        rows.removeAll { it.dedupKey == dedupKey && !it.inflight }
        return enqueue(kind, dedupKey, payload, nowMs, notBeforeMs)
    }

    override suspend fun enqueueSuperseding(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long {
        rows.removeAll { it.dedupKey == dedupKey }
        return enqueue(kind, dedupKey, payload, nowMs, notBeforeMs)
    }
}

class OutboxEnqueuerTest {

    private val now = 1_700_000_000_000L
    private val gridTs = 1_700_000_000_000L

    private fun meal() = MealEventDto(
        client_id = "meal-1", ts = gridTs, tz_offset = 60, updated_at = now,
        grams = 45.0, duration_min = 180.0,
    )

    private fun dose() = DoseEventDto(
        client_id = "dose-1", ts = gridTs, tz_offset = 60, updated_at = now,
        kind = "bolus", units = 3.5, duration_min = 300.0,
    )

    private fun stats() = StatsPushDto(window = "7d", updated_at = now, tir = 72.0)

    @Test
    fun `each event kind enqueues one outbox row with the right key and endpoint`() = runTest {
        val sink = RecordingSink()
        val enqueuer = OutboxEnqueuer(sink)

        enqueuer.enqueueMeal(meal(), now)
        enqueuer.enqueueDose(dose(), now)
        enqueuer.enqueueStats(stats(), now)

        assertEquals("one row per write", 3, sink.rows.size)
        val byPath = sink.rows.associateBy { it.request.path }

        byPath.getValue("/v1/meals").let {
            assertEquals(OutboxKind.MEAL, it.kind)
            assertEquals("PUT", it.request.method)
            assertEquals("meal:meal-1", it.dedupKey)
        }
        byPath.getValue("/v1/doses").let {
            assertEquals(OutboxKind.DOSE, it.kind)
            assertEquals("PUT", it.request.method)
            assertEquals("dose:dose-1", it.dedupKey)
        }
        byPath.getValue("/v1/stats").let {
            assertEquals(OutboxKind.STATS, it.kind)
            assertEquals("PUT", it.request.method)
            assertEquals("stats:7d:${now / 86_400_000}", it.dedupKey)   // ≤1 per window per day
        }
    }

    @Test
    fun `meal and dose ride as one-element batches, stats rides as a bare block`() = runTest {
        val sink = RecordingSink()
        val enqueuer = OutboxEnqueuer(sink)

        enqueuer.enqueueMeal(meal(), now)
        enqueuer.enqueueDose(dose(), now)
        enqueuer.enqueueStats(stats(), now)
        val byPath = sink.rows.associateBy { it.request.path }

        val meals = SyncJson.decodeFromString<List<MealEventDto>>(byPath.getValue("/v1/meals").request.body)
        assertEquals(1, meals.size)
        assertEquals("meal-1", meals.single().client_id)
        assertEquals(45.0, meals.single().grams, 0.0)

        val doses = SyncJson.decodeFromString<List<DoseEventDto>>(byPath.getValue("/v1/doses").request.body)
        assertEquals(1, doses.size)
        assertEquals("bolus", doses.single().kind)
        assertEquals(3.5, doses.single().units, 0.0)

        val block = SyncJson.decodeFromString<StatsPushDto>(byPath.getValue("/v1/stats").request.body)
        assertEquals("7d", block.window)
        assertEquals(72.0, block.tir, 0.0)
    }

    @Test
    fun `only a held meal or dose is postponed, and only by the hold`() = runTest {
        val sink = RecordingSink()
        val enqueuer = OutboxEnqueuer(sink)
        val hold = 15 * 60_000L

        enqueuer.enqueueMeal(meal(), now, holdMs = hold)
        enqueuer.enqueueDose(dose(), now, holdMs = hold)
        enqueuer.enqueueStats(stats(), now)
        val byPath = sink.rows.associateBy { it.request.path }

        assertEquals(now + hold, byPath.getValue("/v1/meals").notBeforeMs)
        assertEquals(now + hold, byPath.getValue("/v1/doses").notBeforeMs)
        assertEquals("stats must not be held", 0L, byPath.getValue("/v1/stats").notBeforeMs)
    }

    @Test
    fun `an unheld meal or dose is due immediately`() = runTest {
        val sink = RecordingSink()
        val enqueuer = OutboxEnqueuer(sink)

        enqueuer.enqueueMeal(meal(), now)
        enqueuer.enqueueDose(dose(), now)

        assertTrue("a re-mirrored event must be due at once", sink.rows.all { it.notBeforeMs == 0L })
    }

    private fun prediction(mgdl: Double, cycleTs: Long = gridTs) = ModelPrediction(
        modelId = "m1",
        cycleTsMs = cycleTs,
        anchorTsMs = cycleTs,
        stepMs = 300_000L,
        medianBg = List(3) { mgdl },
        bandsMgdl = List(3 * 5) { mgdl },
        nQuantiles = 5,
        lastBg = mgdl,
        status = ForecastStatus.OK,
        backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        precision = Precision.FP32,
        selected = true,
        stale = false,
        latencyMs = 13.8,
    )

    @Test
    fun `eviction priority ranks irreplaceable clinical records above regenerable forecasts`() {
        assertEquals(9, OutboxKind.ALERT.priority)
        assertEquals(8, OutboxKind.DOSE.priority)
        assertEquals(7, OutboxKind.MEAL.priority)
        assertEquals(6, OutboxKind.INGEST.priority)
        assertEquals(5, OutboxKind.CGM_SOURCE.priority)  // a label, not a reading
        assertEquals(4, OutboxKind.STATS.priority)
        assertEquals(3, OutboxKind.PREDICTIONS.priority)
        assertEquals(2, OutboxKind.SERIES.priority)      // retired tombstone
        assertEquals(1, OutboxKind.NIGHTSCOUT.priority)  // mirror of a record the phone still holds
        assertEquals(0, OutboxKind.PHOTO.priority)

        val ranked = listOf(
            OutboxKind.ALERT, OutboxKind.DOSE, OutboxKind.MEAL, OutboxKind.INGEST,
            OutboxKind.CGM_SOURCE, OutboxKind.STATS, OutboxKind.PREDICTIONS, OutboxKind.SERIES,
            OutboxKind.NIGHTSCOUT, OutboxKind.PHOTO,
        )
        assertEquals("every OutboxKind is ranked", OutboxKind.entries.size, ranked.size)
        assertEquals("priority is strictly descending in this order", ranked, ranked.sortedByDescending { it.priority })
    }

    @Test
    fun `only irreplaceable clinical kinds are exempt from age-eviction`() {
        for (k in listOf(OutboxKind.ALERT, OutboxKind.DOSE, OutboxKind.MEAL)) {
            assertFalse("$k must never age out", k.ageEvictable)
        }
        for (k in listOf(OutboxKind.INGEST, OutboxKind.STATS, OutboxKind.PREDICTIONS, OutboxKind.SERIES, OutboxKind.PHOTO)) {
            assertTrue("$k may age out", k.ageEvictable)
        }
    }
}
