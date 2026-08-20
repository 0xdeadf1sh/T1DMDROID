package com.t1dm.app.di

import org.junit.Assert.assertFalse
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

    /**
     * One outcome, not four. A deletion is ordered against the create it removes, so it lands
     * whatever the push had already done and there is nothing left to hedge about.
     */
    @Test
    fun `the undo names what was removed and claims nothing about the server`() {
        val msg = undoReceipt(handle())
        assertEquals("Removed 45 g (GI 60).", msg)
        for (hedge in listOf("may have landed", "already sent", "next sync")) {
            assertFalse(msg, msg.contains(hedge, ignoreCase = true))
        }
    }

    /** Photo uploads and a cleared recommendation are unwind-proof; the receipt carries them verbatim. */
    @Test
    fun `caveats are appended to the undo line`() {
        val h = handle(caveats = listOf("Any uploaded photo stays on the server"))
        assertTrue(undoReceipt(h).endsWith("Any uploaded photo stays on the server"))
    }
}
