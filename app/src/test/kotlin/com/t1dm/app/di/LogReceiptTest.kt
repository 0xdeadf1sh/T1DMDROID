package com.t1dm.app.di

import com.t1dm.data.PushWithdrawal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * The receipt is the only place the app tells the human what an Undo did and did not achieve, and the
 * one thing it must never do is overstate it: a push that already drained is on the server for good
 * (the API has no DELETE, and the WS catch-up re-hydrates the event by `clientId` on the next
 * reconnect), so "removed" without qualification would be a claim the next sync visibly refutes.
 */
class LogReceiptTest {

    private val utc = ZoneOffset.UTC

    /** 1970-01-01T14:30:00Z — a grid slot, as a persisted event ts always is. */
    private val slotTs = 14 * 3_600_000L + 30 * 60_000L

    private fun handle(
        label: String = "45 g (GI 60)",
        caveats: List<String> = emptyList(),
    ) = LogHandle(
        kind = LoggedEventKind.MEAL,
        rowId = 7,
        clientId = "c-1",
        tsMs = slotTs,
        outboxId = 3,
        dedupKey = "meal:c-1",
        label = label,
        caveats = caveats,
    )

    @Test
    fun `the receipt names what was written and when`() {
        assertEquals("Logged 45 g (GI 60) at 14:30", logReceipt(handle(), utc))
    }

    @Test
    fun `a withdrawn push is reported as never sent`() {
        assertEquals(
            "Removed 45 g (GI 60) — nothing was sent.",
            undoReceipt(handle(), PushWithdrawal.WITHDRAWN),
        )
    }

    /** A path that enqueues nothing (the insulin-types surface) is just as clean. */
    @Test
    fun `a never-queued write is reported as never sent`() {
        assertEquals(
            undoReceipt(handle(), PushWithdrawal.WITHDRAWN),
            undoReceipt(handle(), PushWithdrawal.NEVER_QUEUED),
        )
    }

    @Test
    fun `a drained push is never claimed to have been recalled`() {
        val msg = undoReceipt(handle(), PushWithdrawal.ALREADY_SENT)
        assertTrue(msg, msg.contains("server copy was already sent"))
        assertTrue("the local delete is still asserted", msg.startsWith("Removed"))
    }

    @Test
    fun `an inflight push is reported as a genuine unknown`() {
        val msg = undoReceipt(handle(), PushWithdrawal.RACED)
        assertTrue(msg, msg.contains("may have landed"))
    }

    /** Photo uploads and a cleared recommendation are unwind-proof; the receipt carries them verbatim. */
    @Test
    fun `caveats are appended to whatever the push outcome was`() {
        val h = handle(caveats = listOf("Any uploaded photo stays on the server"))
        PushWithdrawal.entries.forEach { outcome ->
            assertTrue(outcome.name, undoReceipt(h, outcome).endsWith("Any uploaded photo stays on the server"))
        }
    }
}
