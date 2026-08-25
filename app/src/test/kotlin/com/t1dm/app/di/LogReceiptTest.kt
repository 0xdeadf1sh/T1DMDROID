package com.t1dm.app.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class LogReceiptTest {

    private val utc = ZoneOffset.UTC

    /** 1970-01-01T14:30:00Z, a grid slot. */
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

    /** A deletion is ordered against the create it removes, so there is nothing to hedge. */
    @Test
    fun `the undo names what was removed and claims nothing about the server`() {
        val msg = undoReceipt(handle())
        assertEquals("Removed 45 g (GI 60).", msg)
        for (hedge in listOf("may have landed", "already sent", "next sync")) {
            assertFalse(msg, msg.contains(hedge, ignoreCase = true))
        }
    }

    /** Caveats name what the undo could not unwind. */
    @Test
    fun `caveats are appended to the undo line`() {
        val h = handle(caveats = listOf("Any uploaded photo stays on the server"))
        assertTrue(undoReceipt(h).endsWith("Any uploaded photo stays on the server"))
    }
}
