package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CgmPipelineTest {

    private val sourceId = CgmSourceId("aidexx:22222C74D9")

    // A grid-aligned instant: 300_000 * 5_666_667.
    private val base = 300_000L * 5_666_667L

    private fun pipeline() = CgmPipeline(
        sourceId = sourceId,
        nativeCore = ReferenceNativeCore(),
        tzOffsetMinFor = { 0 },
    )

    @Test
    fun `golden advert decodes to one on-grid MEASURED reading of 92`() {
        val raw = AdvertFixtures.raw(AdvertFixtures.fullAdvert(), rxWallMs = base)

        val out = pipeline().process(raw)

        assertTrue("golden CRC must validate", out.crcValid)
        assertEquals(21600, out.minFromStart)
        assertEquals(1, out.readings.size)
        val r = out.readings[0]
        assertEquals(92, r.bgMgdl)
        assertEquals(ReadingProvenance.MEASURED, r.provenance)
        assertEquals(ReadingFlag.NORMAL, r.flag)
        assertEquals(base, r.tsMs)
        assertEquals(0L, r.tsMs % 300_000L)
    }

    @Test
    fun `a re-advertised minute is deduped by minFromStart`() {
        val p = pipeline()
        val first = p.process(AdvertFixtures.raw(AdvertFixtures.fullAdvert(), rxWallMs = base))
        // Same minFromStart (21600), arriving ~1 min later; must produce no new reading.
        val second = p.process(AdvertFixtures.raw(AdvertFixtures.fullAdvert(), rxWallMs = base + 60_000))

        assertEquals(1, first.readings.size)
        assertTrue("dup minute yields no readings", second.readings.isEmpty())
    }

    @Test
    fun `a dropout is back-filled with INTERPOLATED readings`() {
        val p = pipeline()
        val advA = AdvertFixtures.fullAdvert(AidexCodec.encode(minFromStart = 100, glucose = 100))
        val advB = AdvertFixtures.fullAdvert(AidexCodec.encode(minFromStart = 103, glucose = 130))

        val outA = p.process(AdvertFixtures.raw(advA, rxWallMs = base))
        val outB = p.process(AdvertFixtures.raw(advB, rxWallMs = base + 900_000)) // +15 min = 3 slots

        assertEquals(listOf(100), outA.readings.map { it.bgMgdl })
        assertEquals(listOf(110, 120, 130), outB.readings.map { it.bgMgdl })
        assertEquals(
            listOf(
                ReadingProvenance.INTERPOLATED,
                ReadingProvenance.INTERPOLATED,
                ReadingProvenance.MEASURED,
            ),
            outB.readings.map { it.provenance },
        )
        assertEquals(base + 300_000, outB.readings[0].tsMs)
        assertEquals(base + 600_000, outB.readings[1].tsMs)
        assertEquals(base + 900_000, outB.readings[2].tsMs)
    }

    @Test
    fun `a CRC-failing payload yields no reading but is still reported for forensics`() {
        val bad = AdvertFixtures.GOLDEN_PAYLOAD.copyOf()
        bad[19] = (bad[19].toInt() xor 0xFF).toByte() // corrupt the CRC field
        val out = pipeline().process(AdvertFixtures.raw(AdvertFixtures.fullAdvert(bad), rxWallMs = base))

        assertTrue(out.readings.isEmpty())
        assertTrue("CRC-failing frame is flagged invalid", !out.crcValid)
        assertEquals(21600, out.minFromStart) // still isolated for the raw-advert store
    }
}
