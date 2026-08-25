package com.t1dm.data.stats

import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import com.t1dm.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsMappingTest {

    @Test
    fun parseTargetRange_roundtrips_and_defaults_on_garbage() {
        assertEquals(TargetRange(70, 180), parseTargetRange("70:180"))
        assertEquals(TargetRange(80, 160), parseTargetRange("80:160"))
        assertEquals(TargetRange.DEFAULT, parseTargetRange(null))
        assertEquals(TargetRange.DEFAULT, parseTargetRange("abc"))
        assertEquals(TargetRange.DEFAULT, parseTargetRange("180:70")) // low >= high
        assertEquals(TargetRange.DEFAULT, parseTargetRange("100"))
    }

    @Test
    fun parseUnitSpace_roundtrips_and_defaults() {
        assertEquals(UnitSpace.MgDl, parseUnitSpace("MgDl"))
        assertEquals(UnitSpace.MmolL, parseUnitSpace("MmolL"))
        assertEquals(UnitSpace.Kovatchev, parseUnitSpace("Kovatchev"))
        assertEquals(UnitSpace.MgDl, parseUnitSpace(null))
        assertEquals(UnitSpace.MgDl, parseUnitSpace("nonsense"))
    }

    @Test
    fun toStatSample_maps_null_bg_to_zero_and_keeps_channels() {
        // carbs/bolus/basal are event-reconstructed, always null here.
        val row = sample(ts = 300_000L, bg = null, steps = 120, mood = 3)
        val s = row.toStatSample()
        assertEquals(300_000L, s.tsMs)
        assertEquals(0.0, s.bgMgdl, 0.0) // excluded from BG metrics by the Rust
        assertNull(s.carbsG)
        assertNull(s.bolusU)
        assertNull(s.basalU)
        assertEquals(120L, s.steps)
        assertEquals(3, s.mood)
    }

    @Test
    fun toStatSample_preserves_present_bg() {
        val s = sample(ts = 600_000L, bg = 142, steps = null, mood = null).toStatSample()
        assertEquals(142.0, s.bgMgdl, 0.0)
        assertNull(s.carbsG)
        assertNull(s.steps)
    }

    @Test
    fun toStatSample_carries_the_rows_own_tz_offset() {
        // The row's own offset, not the phone's: they differ after travel or a DST change.
        assertEquals(330, sample(ts = 0L, bg = 100, steps = null, mood = null, tz = 330).toStatSample().tzOffsetMin)
        assertEquals(-300, sample(ts = 0L, bg = 100, steps = null, mood = null, tz = -300).toStatSample().tzOffsetMin)
    }

    private fun sample(
        ts: Long,
        bg: Int?,
        steps: Int?,
        mood: Int?,
        tz: Int = 0,
    ) = SampleEntity(
        ts = ts,
        tzOffsetMin = tz,
        bgMgdl = bg, bgSource = null,
        bgProvenance = null,
        bgFlag = null,
        steps = steps,
        mood = mood,
        hr = null,
        sleep = null,
        exercise = null,
        updatedAt = ts,
    )
}
