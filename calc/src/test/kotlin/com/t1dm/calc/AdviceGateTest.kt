package com.t1dm.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdviceGateTest {

    private val t0 = 1_900_000_000_000L

    @Test
    fun fresh_inside_the_ttl() {
        assertTrue(AdviceGate.fresh(t0, null, t0))
        assertTrue(AdviceGate.fresh(t0, null, t0 + AdviceGate.TTL_MS - 1))
        assertNull(AdviceGate.staleness(t0, t0 - 60_000L, t0 + 60_000L))
    }

    @Test
    fun expired_past_the_ttl() {
        assertFalse(AdviceGate.fresh(t0, null, t0 + AdviceGate.TTL_MS))
        assertEquals(AdviceGate.Stale.AGED, AdviceGate.staleness(t0, null, t0 + 3 * 60 * 60_000L))
    }

    @Test
    fun expired_by_a_curve_write_at_or_after_the_search_start() {
        assertEquals(AdviceGate.Stale.LOG_CHANGED, AdviceGate.staleness(t0, t0 + 30_000L, t0 + 60_000L))
        assertEquals(AdviceGate.Stale.LOG_CHANGED, AdviceGate.staleness(t0, t0, t0 + 1L))
    }

    @Test
    fun a_clock_moved_back_expires_it() {
        assertEquals(AdviceGate.Stale.CLOCK_BACK, AdviceGate.staleness(t0, null, t0 - 1L))
    }

    @Test
    fun acceptance_is_tied_to_the_target_the_dose_was_computed_for() {
        assertTrue(AdviceGate.sameTarget(123.0, 123.4))
        assertFalse("slider moved off the computed target", AdviceGate.sameTarget(123.0, 140.0))
    }
}
