package com.t1dm.sync.nightscout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receiving host re-renders timestamps: a reading posted at `+03:00` reads back as the same
 * instant at `+00:00`. Anything in the replay guard that compares the rendering rather than the
 * moment would report every replay as "not present" and duplicate the dose it exists to protect.
 */
class NightscoutInstantMatchTest {

    private fun bolus(at: String, notes: String? = null) = NsTreatmentDto(
        eventType = NsEventType.BOLUS,
        created_at = at,
        insulin = 6.5,
        notes = notes,
    )

    @Test
    fun `a normalised readback is still the same instant`() {
        assertTrue(sameInstant("2026-08-17T23:53:20+03:00", "2026-08-17T20:53:20+00:00"))
        assertTrue(sameInstant("2026-08-17T20:53:20Z", "2026-08-17T20:53:20+00:00"))
        assertFalse(sameInstant("2026-08-17T23:53:20+03:00", "2026-08-17T23:53:20+00:00"))
    }

    @Test
    fun `a host that rewrites the offset does not defeat the shape match`() {
        val mine = bolus("2026-08-17T23:53:20+03:00", "cid-1")
        val readBack = bolus("2026-08-17T20:53:20+00:00")
        assertTrue(readBack.matches(mine))
    }

    /** The marker still wins when the host keeps it, offset rewriting or not. */
    @Test
    fun `the client id marker matches across a rewritten offset`() {
        val mine = bolus("2026-08-17T23:53:20+03:00", "cid-1")
        val readBack = bolus("2026-08-17T20:53:20+00:00", "cid-1")
        assertTrue(readBack.matches(mine, requireMarker = true))
    }

    /** A different moment is a different event, however it is rendered. */
    @Test
    fun `a neighbouring slot is not a match`() {
        val mine = bolus("2026-08-17T23:53:20+03:00", "cid-1")
        assertFalse(bolus("2026-08-17T20:58:20+00:00").matches(mine))
    }
}
