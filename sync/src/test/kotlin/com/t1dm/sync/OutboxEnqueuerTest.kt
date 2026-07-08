package com.t1dm.sync

import com.t1dm.data.OutboxSink
import com.t1dm.data.db.OutboxKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
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
 * The Phase-4 event writers push five new kinds to the durable outbox on write: carbs/bolus/basal
 * as `PUT /v1/series/{name}`, mood as `PUT /v1/series/mood`, and a free-text note as `POST /v1/notes`.
 * This asserts each enqueues exactly one outbox row with the right kind, endpoint, and priority
 * class (SERIES for the four series, NOTE for the note).
 */
class OutboxEnqueuerTest {

    @Test
    fun `each new kind enqueues one outbox row with the right endpoint`() = runTest {
        val sink = RecordingSink()
        val enqueuer = OutboxEnqueuer(sink)
        val now = 1_700_000_000_000L
        val gridTs = 1_700_000_000_000L

        enqueuer.enqueueSeries("carbs", listOf(SeriesPointDto(gridTs, 45.0)), now)
        enqueuer.enqueueSeries("bolus", listOf(SeriesPointDto(gridTs, 3.5)), now)
        enqueuer.enqueueSeries("basal", listOf(SeriesPointDto(gridTs, 12.0)), now)
        enqueuer.enqueueSeries("mood", listOf(SeriesPointDto(gridTs, 4.0)), now)
        enqueuer.enqueueNote(NoteWriteDto(ts = now, tz_offset = 60, text = "felt low after lunch"), now)

        assertEquals("one row per write", 5, sink.rows.size)

        val byPath = sink.rows.associateBy { it.request.path }

        for (name in listOf("carbs", "bolus", "basal", "mood")) {
            val row = byPath.getValue("/v1/series/$name")
            assertEquals("$name is a SERIES kind", OutboxKind.SERIES, row.kind)
            assertEquals("$name replays as PUT", "PUT", row.request.method)
        }

        val note = byPath.getValue("/v1/notes")
        assertEquals("note is a NOTE kind", OutboxKind.NOTE, note.kind)
        assertEquals("note replays as POST", "POST", note.request.method)
        assertTrue("note body carries the text", note.request.body.contains("felt low after lunch"))
    }

    @Test
    fun `series body carries the sample point`() = runTest {
        val sink = RecordingSink()
        val enqueuer = OutboxEnqueuer(sink)

        enqueuer.enqueueSeries("carbs", listOf(SeriesPointDto(1_700_000_000_000L, 45.0)), 1L)

        val body = SyncJson.decodeFromString<SeriesPutDto>(sink.rows.single().request.body)
        assertEquals(1, body.samples.size)
        assertEquals(45.0, body.samples.single().value, 0.0)
    }
}
