package com.t1dm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    @Test
    fun doublesWithoutJitterAndCaps() {
        val cfg = DrainConfig(baseBackoffMs = 1_000, maxBackoffMs = 10_000, jitterFrac = 0.0)
        assertEquals(1_000, Backoff.delayMs(cfg, attempts = 0, rand01 = 0.5))
        assertEquals(2_000, Backoff.delayMs(cfg, attempts = 1, rand01 = 0.5))
        assertEquals(4_000, Backoff.delayMs(cfg, attempts = 2, rand01 = 0.5))
        assertEquals(8_000, Backoff.delayMs(cfg, attempts = 3, rand01 = 0.5))
        assertEquals(10_000, Backoff.delayMs(cfg, attempts = 4, rand01 = 0.5))  // capped
        assertEquals(10_000, Backoff.delayMs(cfg, attempts = 30, rand01 = 0.5)) // no overflow
    }

    @Test
    fun jitterStaysWithinSymmetricBand() {
        val cfg = DrainConfig(baseBackoffMs = 1_000, maxBackoffMs = 1_000_000, jitterFrac = 0.25)
        val exp = 4_000.0 // attempts=2
        val lo = Backoff.delayMs(cfg, attempts = 2, rand01 = 0.0)
        val mid = Backoff.delayMs(cfg, attempts = 2, rand01 = 0.5)
        val hi = Backoff.delayMs(cfg, attempts = 2, rand01 = 1.0)
        assertEquals((exp * 0.75).toLong(), lo)
        assertEquals(exp.toLong(), mid)
        assertEquals((exp * 1.25).toLong(), hi)
        assertTrue(lo < mid && mid < hi)
    }
}
