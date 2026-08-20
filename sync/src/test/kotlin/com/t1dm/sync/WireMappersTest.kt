package com.t1dm.sync

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the wire says about a BG, and what the phone stores because of it.
 *
 * The one mapping in the app where a wrong answer is not a wrong pixel: provenance is what every
 * safety gate keys on — an alarm may only be cleared by a measured reading, and only a measured
 * reading enters the dosing series — so a reconstruction that arrives flagged MEASURED is a model's
 * output admitted as sensor signal. Both inbound paths are pinned here because they are separate
 * code and only one of them was taught the flag when it shipped.
 */
class WireMappersTest {

    private fun rest(bg: Double?, reconstructed: Boolean) = SampleDto(
        ts = 1_735_689_600_000L,
        tz_offset = 0,
        updated_at = 1_735_689_610_000L,
        bg = bg,
        bg_reconstructed = reconstructed,
    ).toPatch()

    private fun live(bg: Double?, reconstructed: Boolean) = WsEvent.Sample(
        ts = 1_735_689_600_000L,
        tz_offset = 0,
        updated_at = 1_735_689_610_000L,
        bg = bg,
        bg_reconstructed = reconstructed,
    ).toPatch()

    @Test
    fun `a reconstruction arrives reconstructed on the REST path`() {
        assertEquals(ReadingProvenance.RECONSTRUCTED, rest(112.0, true).bgProvenance)
        assertEquals(ReadingFlag.NORMAL, rest(112.0, true).bgFlag)
        assertEquals(112, rest(112.0, true).bgMgdl)
    }

    @Test
    fun `a reconstruction arrives reconstructed on the live path`() {
        assertEquals(
            "the live frame must not launder a reconstruction into a measurement",
            ReadingProvenance.RECONSTRUCTED,
            live(112.0, true).bgProvenance,
        )
    }

    @Test
    fun `an unflagged reading is measured on both paths`() {
        assertEquals(ReadingProvenance.MEASURED, rest(112.0, false).bgProvenance)
        assertEquals(ReadingProvenance.MEASURED, live(112.0, false).bgProvenance)
    }

    @Test
    fun `no bg means no provenance and no flag on either path`() {
        for (p in listOf(rest(null, false), live(null, false), rest(null, true), live(null, true))) {
            assertNull(p.bgMgdl)
            assertNull(p.bgProvenance)
            assertNull(p.bgFlag)
        }
    }

    @Test
    fun `the two paths agree for every combination`() {
        for (bg in listOf(null, 112.0)) {
            for (r in listOf(false, true)) {
                assertEquals(
                    "REST and the live frame must read one wire the same way (bg=$bg recon=$r)",
                    rest(bg, r).bgProvenance,
                    live(bg, r).bgProvenance,
                )
            }
        }
    }
}
