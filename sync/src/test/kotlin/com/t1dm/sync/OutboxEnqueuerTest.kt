package com.t1dm.sync

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
    data class Row(val kind: OutboxKind, val dedupKey: String, val request: OutboxRequest)

    val rows = mutableListOf<Row>()

    override suspend fun enqueue(kind: OutboxKind, dedupKey: String, payload: ByteArray, nowMs: Long): Long {
        val request = SyncJson.decodeFromString<OutboxRequest>(payload.toString(Charsets.UTF_8))
        rows += Row(kind, dedupKey, request)
        return rows.size.toLong()
    }
}

/**
 * The redesigned event writers push self-describing curve events to the durable outbox: a meal as a
 * `PUT /v1/meals` batch, a dose as `PUT /v1/doses`, a phone-computed window block as `PUT /v1/stats`,
 * and a free-text note as `POST /v1/notes`. This asserts each enqueues exactly one row with the right
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

    private fun note() = NoteWriteDto(
        client_id = "note-1", ts = now, tz_offset = 60, text = "felt low after lunch", updated_at = now,
    )

    @Test
    fun `each event kind enqueues one outbox row with the right key and endpoint`() = runTest {
        val sink = RecordingSink()
        val enqueuer = OutboxEnqueuer(sink)

        enqueuer.enqueueMeal(meal(), now)
        enqueuer.enqueueDose(dose(), now)
        enqueuer.enqueueStats(stats(), now)
        enqueuer.enqueueNote(note(), now)

        assertEquals("one row per write", 4, sink.rows.size)
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
        byPath.getValue("/v1/notes").let {
            assertEquals(OutboxKind.NOTE, it.kind)
            assertEquals("POST", it.request.method)
            assertTrue("note keyed on ts+text", it.dedupKey.startsWith("note:"))
            assertTrue("note body carries the text", it.request.body.contains("felt low after lunch"))
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
    fun `eviction priority ranks irreplaceable clinical records above regenerable forecasts`() {
        assertEquals(8, OutboxKind.ALERT.priority)
        assertEquals(7, OutboxKind.DOSE.priority)
        assertEquals(6, OutboxKind.MEAL.priority)
        assertEquals(5, OutboxKind.NOTE.priority)
        assertEquals(4, OutboxKind.INGEST.priority)
        assertEquals(3, OutboxKind.STATS.priority)
        assertEquals(2, OutboxKind.PREDICTIONS.priority)
        assertEquals(1, OutboxKind.SERIES.priority)      // retired tombstone
        assertEquals(0, OutboxKind.PHOTO.priority)

        val ranked = listOf(
            OutboxKind.ALERT, OutboxKind.DOSE, OutboxKind.MEAL, OutboxKind.NOTE, OutboxKind.INGEST,
            OutboxKind.STATS, OutboxKind.PREDICTIONS, OutboxKind.SERIES, OutboxKind.PHOTO,
        )
        assertEquals("priority is strictly descending in this order", ranked, ranked.sortedByDescending { it.priority })
    }

    @Test
    fun `only irreplaceable clinical kinds are exempt from age-eviction`() {
        for (k in listOf(OutboxKind.ALERT, OutboxKind.DOSE, OutboxKind.MEAL, OutboxKind.NOTE)) {
            assertFalse("$k must never age out", k.ageEvictable)
        }
        for (k in listOf(OutboxKind.INGEST, OutboxKind.STATS, OutboxKind.PREDICTIONS, OutboxKind.SERIES, OutboxKind.PHOTO)) {
            assertTrue("$k may age out", k.ageEvictable)
        }
    }
}
