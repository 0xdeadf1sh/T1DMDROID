package com.t1dm.cgm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AidexCodecTest {

    @Test
    fun `a seed whose words sum past 2^32 wraps`() {
        // SYNTHETIC advert, never a live capture; CRC from an independent implementation.
        val p = hex("a0 0f 00 00 fd 96 80 f0 98 80 f0 ff 80 f0 00 00 c3 6d 05 2d")
        val sum = AidexCodec.le32(p, 0) + AidexCodec.le32(p, 4) + AidexCodec.le32(p, 8) + AidexCodec.le32(p, 12)
        assertEquals(0x1_F072_17B5L, sum)
        // Unwrapped, the CRC is 0x7B32FE9C.
        assertEquals(0x2D05_6DC3L, AidexCodec.crcOf(p))
        assertNotNull(AidexCodec.decode(p))
        val encoded = AidexCodec.encode(
            minFromStart = 4000, glucose = 150, trend = -3, quality = 0xF0, prev1 = 152, prev2 = 255,
        )
        assertTrue(encoded.contentEquals(p))
    }

    private fun hex(s: String): ByteArray =
        s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
