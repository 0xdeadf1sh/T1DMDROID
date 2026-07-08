package com.t1dm.sync

import com.t1dm.data.LwwMerge
import com.t1dm.data.SamplePatch
import com.t1dm.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LwwMergeTest {

    private fun existing(updatedAt: Long, bg: Int? = null, carbs: Double? = null, steps: Int? = null) =
        SampleEntity(
            ts = 300_000, tzOffsetMin = 0, bgMgdl = bg, bgProvenance = null, bgFlag = null,
            carbsG = carbs, bolusU = null, basalU = null, steps = steps, mood = null,
            hr = null, sleep = null, exercise = null, updatedAt = updatedAt,
        )

    private fun patch(updatedAt: Long, bg: Int? = null, carbs: Double? = null, steps: Int? = null) =
        SamplePatch(ts = 300_000, tzOffsetMin = 0, updatedAt = updatedAt, bgMgdl = bg, carbsG = carbs, steps = steps)

    @Test
    fun materializesWhenNoLocalRow() {
        val merged = LwwMerge.merge(null, patch(updatedAt = 5, bg = 120))
        assertEquals(120, merged!!.bgMgdl)
        assertEquals(5, merged.updatedAt)
    }

    @Test
    fun newerServerWins() {
        val merged = LwwMerge.merge(existing(updatedAt = 10, bg = 100), patch(updatedAt = 20, bg = 140))
        assertEquals(140, merged!!.bgMgdl)
        assertEquals(20, merged.updatedAt)
    }

    @Test
    fun olderOrEqualServerIsRejected() {
        assertNull(LwwMerge.merge(existing(updatedAt = 20, bg = 100), patch(updatedAt = 10, bg = 140)))
        assertNull(LwwMerge.merge(existing(updatedAt = 20, bg = 100), patch(updatedAt = 20, bg = 140)))
    }

    @Test
    fun nullServerFieldsPreserveLocalGaps() {
        // Newer server row carries only carbs; the local bg must survive (per-field gap preservation).
        val merged = LwwMerge.merge(existing(updatedAt = 10, bg = 100, steps = 42), patch(updatedAt = 20, carbs = 30.0))
        assertEquals(100, merged!!.bgMgdl)   // preserved
        assertEquals(42, merged.steps)       // preserved
        assertEquals(30.0, merged.carbsG!!, 0.0)
        assertEquals(20, merged.updatedAt)
    }
}
