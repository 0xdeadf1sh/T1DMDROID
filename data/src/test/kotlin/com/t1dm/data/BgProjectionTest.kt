package com.t1dm.data

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BgProjectionTest {

    @Test
    fun `an empty slot takes the reading whole`() {
        val out = T1dmRepository.projectedBgSample(empty(SLOT, updatedAt = SLOT), reading(bgMgdl = 121))

        assertEquals(121, out.bgMgdl)
        assertEquals(ReadingProvenance.MEASURED, out.bgProvenance)
        assertEquals(ReadingFlag.NORMAL, out.bgFlag)
        assertEquals(330, out.tzOffsetMin)
        assertNotNull("the label must name the sensor that produced the number", out.bgSource)
        assertEquals(SOURCE.opaque, out.bgSource)
    }

    @Test
    fun `the other channels of the row are left alone`() {
        val base = empty(SLOT, updatedAt = SLOT).copy(steps = 412, mood = 3, hr = 68, exercise = 2.5)
        val out = T1dmRepository.projectedBgSample(base, reading(bgMgdl = 96))

        assertEquals(412, out.steps)
        assertEquals(3, out.mood)
        assertEquals(68, out.hr)
        assertEquals(2.5, out.exercise!!, 0.0)
        assertEquals(SLOT, out.ts)
    }

    @Test
    fun `a reading older than the row's stamp still projects`() {
        val touchedLater = empty(SLOT, updatedAt = SLOT + 90 * 60_000L)
        val out = T1dmRepository.projectedBgSample(touchedLater, reading(bgMgdl = 58, rxWallMs = SLOT))

        assertEquals("the BG of the slot was dropped", 58, out.bgMgdl)
        assertEquals(ReadingFlag.NORMAL, out.bgFlag)
    }

    /** §7. */
    @Test
    fun `the row's stamp never moves backwards`() {
        val stamped = empty(SLOT, updatedAt = SLOT + 600_000)
        assertEquals(
            SLOT + 600_000,
            T1dmRepository.projectedBgSample(stamped, reading(rxWallMs = SLOT)).updatedAt,
        )
        assertEquals(
            SLOT + 900_000,
            T1dmRepository.projectedBgSample(stamped, reading(rxWallMs = SLOT + 900_000)).updatedAt,
        )
    }

    @Test
    fun `a reading with no value clears the slot's BG`() {
        val held = T1dmRepository.projectedBgSample(empty(SLOT, updatedAt = SLOT), reading(bgMgdl = 140))
        val out = T1dmRepository.projectedBgSample(
            held,
            reading(bgMgdl = null, flag = ReadingFlag.WARMUP, rxWallMs = SLOT + 60_000),
        )
        assertEquals(null, out.bgMgdl)
        assertEquals(ReadingFlag.WARMUP, out.bgFlag)
    }

    private companion object {
        /** On the grid: 1_700_000_100_000 % 300_000 == 0. */
        const val SLOT = 1_700_000_100_000L
        val SOURCE = CgmSourceId("vendor:serial")

        fun reading(
            bgMgdl: Int? = 100,
            flag: ReadingFlag = ReadingFlag.NORMAL,
            rxWallMs: Long = SLOT + 40_000,
        ) = CgmReading(
            sourceId = SOURCE,
            tsMs = SLOT,
            bgMgdl = bgMgdl,
            trendTenthsPerMin = 2,
            minFromStart = 300,
            quality = null,
            provenance = ReadingProvenance.MEASURED,
            flag = flag,
            tzOffsetMin = 330,
            rxWallMs = rxWallMs,
            rssi = -70,
        )

        fun empty(ts: Long, updatedAt: Long) = SampleEntity(
            ts = ts,
            tzOffsetMin = 0,
            bgMgdl = null,
            bgSource = null,
            bgProvenance = null,
            bgFlag = null,
            steps = null,
            mood = null,
            hr = null,
            sleep = null,
            exercise = null,
            updatedAt = updatedAt,
        )
    }
}
