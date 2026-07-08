package com.t1dm.cgm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdStructureParserTest {

    @Test
    fun `picks the 20-byte 0x0059 structure over the short one`() {
        val payload = AdStructureParser.manufacturerPayload(AdvertFixtures.fullAdvert())
        assertArrayEquals(AdvertFixtures.GOLDEN_PAYLOAD, payload)
    }

    @Test
    fun `picks the glucose block regardless of structure order`() {
        val payload = AdStructureParser.manufacturerPayload(AdvertFixtures.fullAdvertShortFirst())
        assertArrayEquals(AdvertFixtures.GOLDEN_PAYLOAD, payload)
    }

    @Test
    fun `returns null when only the short 0x0059 status block is present`() {
        // flags + service + a lone 5-byte 0x0059 structure (len 0x08).
        val bytes = AdvertFixtures.hex("02 01 06 03 02 1F 18 08 FF 59 00 AA BB CC DD EE")
        assertNull(AdStructureParser.manufacturerPayload(bytes))
    }

    @Test
    fun `extracts the local name`() {
        assertEquals("LinX-22222C74D9", AdStructureParser.localName(AdvertFixtures.fullAdvert()))
    }
}
