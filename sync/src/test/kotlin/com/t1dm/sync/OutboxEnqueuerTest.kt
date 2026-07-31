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

/**
 * Records every enqueued row so a test can assert the exact [OutboxKind] + replayed [OutboxRequest]
 * (method/path/body) captured at write time, with no Room in the loop.
 */
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

/**
 * A queue faithful to the two rules the prediction replace turns on: the `dedupKey` index is UNIQUE
 * (a plain [enqueue] under a taken key is IGNORED and yields -1), and INFLIGHT is the drainer's
 * mutual-exclusion token — [enqueueReplacingPending] may displace a PENDING row and must leave a
 * claimed one alone. Ids are strictly increasing and never reused, as the `AUTOINCREMENT` column is.
 */
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

    /** Move the row under [dedupKey] to INFLIGHT, as `OutboxDao.claim` does before anything is sent. */
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
}

/**
 * The redesigned event writers push self-describing curve events to the durable outbox: a meal as a
 * `PUT /v1/meals` batch, a dose as `PUT /v1/doses`, and a phone-computed window block as
 * `PUT /v1/stats`. This asserts each enqueues exactly one row with the right
 * kind, `client_id`/`window`-keyed `dedupKey`, endpoint, and method — and that the eviction-priority
 * ordering places irreplaceable clinical records above regenerable forecasts.
 */
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
            assertEquals("meal:meal-1", it.dedupKey)          // keyed by client_id (#4/#7)
        }
        byPath.getValue("/v1/doses").let {
            assertEquals(OutboxKind.DOSE, it.kind)
            assertEquals("PUT", it.request.method)
            assertEquals("dose:dose-1", it.dedupKey)
        }
        byPath.getValue("/v1/stats").let {
            assertEquals(OutboxKind.STATS, it.kind)
            assertEquals("PUT", it.request.method)
            assertEquals("stats:7d:${now / 86_400_000}", it.dedupKey)   // ≤1 per window per day (#6)
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

    /**
     * The withdrawal hold reaches `nextAttemptMs` and nothing else: only MEAL/DOSE take one, and only
     * when asked. A stats push must never be delayed by it, and a re-mirror replay (which
     * passes no hold) must stay immediately due or the walk stalls.
     */
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

    // ── the prediction replace ────────────────────────────────────────────────────────────────────

    /** One model's forecast for [cycleTs], flat at [mgdl] so the enqueued body identifies the run. */
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

    /**
     * The wire hazard a log-driven re-run introduced. `pred:<cycleTs>` keys a CYCLE, and a cycle can
     * run twice inside its own 5-min slot — the tick fires one, a logged meal fires another off the
     * channels it just moved. Under the plain unique-key IGNORE the second batch was silently dropped,
     * leaving the server on the pre-log forecast while the phone drew the post-log one. The queue must
     * still hold at most ONE prediction push per cycle, and it must be the newest.
     */
    @Test
    fun `a re-run inside one cycle replaces the queued prediction rather than being dropped`() = runTest {
        val sink = QueueSink()
        val enqueuer = OutboxEnqueuer(sink)

        val first = enqueuer.enqueuePredictions(gridTs, listOf(prediction(120.0)), now)
        val second = enqueuer.enqueuePredictions(gridTs, listOf(prediction(180.0)), now + 4_000)

        assertTrue("the re-run must not be ignored", second > 0)
        assertTrue("the replacement is a NEW row — ids are never reused", second != first)
        assertEquals("still at most one prediction push per cycle", 1, sink.rows.size)
        val body = sink.bodyUnder("pred:$gridTs")
        assertTrue("the queued push must carry the LATEST forecast", body.contains("180.0"))
        assertFalse("the superseded forecast must be gone", body.contains("120.0"))
    }

    /** A different cycle is a different key, so a genuine second cycle still queues beside the first. */
    @Test
    fun `a later cycle queues beside the earlier one instead of replacing it`() = runTest {
        val sink = QueueSink()
        val enqueuer = OutboxEnqueuer(sink)

        enqueuer.enqueuePredictions(gridTs, listOf(prediction(120.0)), now)
        enqueuer.enqueuePredictions(gridTs + 300_000L, listOf(prediction(180.0, gridTs + 300_000L)), now + 300_000L)

        assertEquals(2, sink.rows.size)
        assertEquals(
            setOf("pred:$gridTs", "pred:${gridTs + 300_000L}"),
            sink.rows.map { it.dedupKey }.toSet(),
        )
    }

    /**
     * The drain race, held to the side it is documented on. Once the drainer has claimed the row it
     * owns the key and the body is on the wire; the replace must NOT delete it out from under the send,
     * so the fresher batch loses the unique index and reports -1. Nothing is corrupted by that — the
     * `updated_at` ordering means the delivered body cannot outrank a newer one — and the next cycle
     * re-enqueues under its own key seconds later.
     */
    @Test
    fun `a claimed row is left to its send and the replacement reports it`() = runTest {
        val sink = QueueSink()
        val enqueuer = OutboxEnqueuer(sink)

        enqueuer.enqueuePredictions(gridTs, listOf(prediction(120.0)), now)
        sink.claim("pred:$gridTs")
        val blocked = enqueuer.enqueuePredictions(gridTs, listOf(prediction(180.0)), now + 4_000)

        assertEquals("a claimed key must report the refusal, not pretend to have queued", -1L, blocked)
        assertEquals(1, sink.rows.size)
        assertTrue("the body on the wire is untouched", sink.bodyUnder("pred:$gridTs").contains("120.0"))
    }

    @Test
    fun `eviction priority ranks irreplaceable clinical records above regenerable forecasts`() {
        assertEquals(7, OutboxKind.ALERT.priority)
        assertEquals(6, OutboxKind.DOSE.priority)
        assertEquals(5, OutboxKind.MEAL.priority)
        assertEquals(4, OutboxKind.INGEST.priority)
        assertEquals(3, OutboxKind.STATS.priority)
        assertEquals(2, OutboxKind.PREDICTIONS.priority)
        assertEquals(1, OutboxKind.SERIES.priority)      // retired tombstone
        assertEquals(0, OutboxKind.PHOTO.priority)

        val ranked = listOf(
            OutboxKind.ALERT, OutboxKind.DOSE, OutboxKind.MEAL, OutboxKind.INGEST,
            OutboxKind.STATS, OutboxKind.PREDICTIONS, OutboxKind.SERIES, OutboxKind.PHOTO,
        )
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
