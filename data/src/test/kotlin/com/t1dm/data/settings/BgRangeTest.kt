package com.t1dm.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Host tests for the BG-panel axis-range sanitizer (Phase 7A item 1). */
class BgRangeTest {

    @Test fun keepsValidRange() {
        assertEquals(BgRange(20, 250), BgRange.of(20, 250))
    }

    @Test fun enforcesMinBelowMax() {
        // max <= min is nudged so the axis can never invert or collapse.
        val r = BgRange.of(200, 150)
        assertEquals(200, r.minMgdl)
        assertEquals(201, r.maxMgdl)
    }

    @Test fun clampsToPhysicalBounds() {
        val r = BgRange.of(-50, 9999)
        assertEquals(BgRange.FLOOR, r.minMgdl)
        assertEquals(BgRange.CEIL, r.maxMgdl)
    }
}
