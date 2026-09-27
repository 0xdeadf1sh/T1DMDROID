package com.t1dm.feature.dashboard

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class CutRangeClockTest {

    private val SLOT = Instant.parse("2026-10-24T13:00:00Z").toEpochMilli()

    private fun reading(ts: Long, tz: Int) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = 100, trendTenthsPerMin = 0,
        minFromStart = 5, quality = 100, provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL, tzOffsetMin = tz, rxWallMs = ts, rssi = -60,
    )

    @Test fun theSlotReadsInItsOwnStoredOffset() {
        // Stored at +120 before the DST change; the newest reading is at +60.
        val readings = listOf(reading(SLOT, 120), reading(SLOT + 2 * 86_400_000L, 60))
        assertEquals("15:00", cutClock(readings, SLOT))
    }

    @Test fun noReadingsReadsUtc() {
        assertEquals("13:00", cutClock(emptyList(), SLOT))
    }
}
