package com.t1dm.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NextTimedCycleTest {

    private val hour = 1_700_002_800_000L

    @Test
    fun `next timed cycle lands on the period boundary`() {
        assertEquals(hour + 60_000L, nextTimedCycleMs(hour, 1))
        assertEquals(hour + 60_000L, nextTimedCycleMs(hour + 59_999L, 1))
        assertEquals(hour + 15 * 60_000L, nextTimedCycleMs(hour, 15))
        assertEquals(hour + 15 * 60_000L, nextTimedCycleMs(hour + 7 * 60_000L, 15))
        assertEquals(hour + 30 * 60_000L, nextTimedCycleMs(hour + 15 * 60_000L, 15))
        assertEquals(hour + 60 * 60_000L, nextTimedCycleMs(hour + 1L, 60))
    }

    @Test
    fun `a zero period counts as one minute`() {
        assertEquals(hour + 60_000L, nextTimedCycleMs(hour + 1L, 0))
    }
}
