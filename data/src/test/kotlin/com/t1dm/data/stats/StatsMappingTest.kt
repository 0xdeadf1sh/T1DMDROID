package com.t1dm.data.stats

import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import com.t1dm.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host JVM coverage for the pure `:data` stats helpers (Phase 6): the kv setting parsers and the
 * wide-sample → Rust [com.t1dm.core.model.StatSample] projection. The numeric reduction itself is
 * golden-gated in the Rust crate; these pin the marshalling contract on this side of the seam.
 */
class StatsMappingTest {

    @Test
    fun parseTargetRange_roundtrips_and_defaults_on_garbage() {
        assertEquals(TargetRange(70, 180), parseTargetRange("70:180"))
        assertEquals(TargetRange(80, 160), parseTargetRange("80:160"))
        // Malformed / inverted / absent all fall back to the default.
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
        val row = sample(ts = 300_000L, bg = null, carbs = 40.0, bolus = 6.0, basal = null, steps = 120, mood = 3)
        val s = row.toStatSample()
        assertEquals(300_000L, s.tsMs)
        assertEquals(0.0, s.bgMgdl, 0.0) // excluded from BG metrics by the Rust, channels retained
        assertEquals(40.0, s.carbsG!!, 0.0)
        assertEquals(6.0, s.bolusU!!, 0.0)
        assertNull(s.basalU)
        assertEquals(120L, s.steps)
        assertEquals(3, s.mood)
    }

    @Test
    fun toStatSample_preserves_present_bg() {
        val s = sample(ts = 600_000L, bg = 142, carbs = null, bolus = null, basal = null, steps = null, mood = null).toStatSample()
        assertEquals(142.0, s.bgMgdl, 0.0)
        assertNull(s.carbsG)
        assertNull(s.steps)
    }

    private fun sample(
        ts: Long,
        bg: Int?,
        carbs: Double?,
        bolus: Double?,
        basal: Double?,
        steps: Int?,
        mood: Int?,
    ) = SampleEntity(
        ts = ts,
        tzOffsetMin = 0,
        bgMgdl = bg,
        bgProvenance = null,
        bgFlag = null,
        carbsG = carbs,
        bolusU = bolus,
        basalU = basal,
        steps = steps,
        mood = mood,
        hr = null,
        sleep = null,
        exercise = null,
        updatedAt = ts,
    )
}
