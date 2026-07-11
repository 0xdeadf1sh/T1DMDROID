package com.t1dm.app.notify

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BgFormat.ageShort] — the second-resolution age used by the live notification (F1). Boundaries that
 * matter: the sub-second "just now", the s→m rollover at 60s, and the m→h rollover at 3600s (which
 * must read "1h 0m ago", not "60m ago").
 */
class BgFormatTest {

    @Test fun ageShort_covers_boundaries() {
        assertEquals("just now", BgFormat.ageShort(0L))
        assertEquals("5s ago", BgFormat.ageShort(5_000L))
        assertEquals("59s ago", BgFormat.ageShort(59_000L))
        assertEquals("1m ago", BgFormat.ageShort(60_000L))
        assertEquals("59m ago", BgFormat.ageShort(3_540_000L))
        assertEquals("1h 0m ago", BgFormat.ageShort(3_600_000L))
        assertEquals("1h 30m ago", BgFormat.ageShort(5_400_000L))
    }
}
