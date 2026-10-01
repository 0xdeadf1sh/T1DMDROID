package com.t1dm.cgm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codec double checked against the Rust golden vector's real frames; keys are synthetic. */
class Ct5SessionCodecTest {

    @Test
    fun `the two captured pushes decode to the numbers the hardware showed`() {
        val a = Ct5SessionCodec.parsePush(CAPTURED_PUSH_A, VECTOR_CIPHER_ID)!!
        assertEquals(5, a.glucoseId)
        // INTEGER-FIRST: reversed order reads -35.29C here, plausible 35.70C next, hence pair.
        assertEquals(3104, a.tempCx100)
        assertEquals(893, a.iwX100)
        assertEquals(0, a.ibX100)
        assertNull("the wire glucose field is zero throughout warm-up: ABSENT, not 0 mg/dL", a.glucoseMgdl)
        assertEquals(0, a.errorCode)
        assertEquals(0, a.trendCode)
        assertEquals(1603, a.batteryRaw)
        assertEquals(listOf(1038, 1038, 996, 648), a.electrodesMv)

        val b = Ct5SessionCodec.parsePush(CAPTURED_PUSH_B, VECTOR_CIPHER_ID)!!
        assertEquals(6, b.glucoseId)
        assertEquals(3075, b.tempCx100)
        assertEquals(836, b.iwX100)
        assertEquals(1604, b.batteryRaw)
        assertNull(b.glucoseMgdl)
    }

    @Test
    fun `the glucose id is little-endian`() {
        // Big-endian would read 1280.
        assertEquals(5, Ct5SessionCodec.parsePush(CAPTURED_PUSH_A, VECTOR_CIPHER_ID)!!.glucoseId)
    }

    @Test
    fun `the complement key changes only the battery low bit`() {
        val a = Ct5SessionCodec.parsePush(CAPTURED_PUSH_A, VECTOR_CIPHER_ID)!!
        val b = Ct5SessionCodec.parsePush(CAPTURED_PUSH_A, VECTOR_CIPHER_ID xor 0xFF)!!
        // Key recoverable only up to (k, k xor 0xFF); the one differing bit lands in the battery.
        assertEquals(a.copy(batteryRaw = b.batteryRaw), b)
        assertEquals(1, Math.abs(a.batteryRaw!! - b.batteryRaw!!))
    }

    @Test
    fun `only the two push lengths and a valid checksum decode`() {
        assertTrue(Ct5SessionCodec.frameIsLegal(CAPTURED_PUSH_A))
        val corrupt = CAPTURED_PUSH_A.copyOf()
        corrupt[18] = (corrupt[18].toInt() xor 1).toByte()
        assertNull(Ct5SessionCodec.parsePush(corrupt, VECTOR_CIPHER_ID))
        assertNull(Ct5SessionCodec.parsePush(CAPTURED_PUSH_A.copyOfRange(0, 17), VECTOR_CIPHER_ID))
        val wrongOpcode = CAPTURED_PUSH_A.copyOf().also { it[0] = 0x37 }
        assertNull(Ct5SessionCodec.parsePush(wrongOpcode, VECTOR_CIPHER_ID))
    }

    @Test
    fun `setParameters reproduces the bind vector byte for byte`() {
        val built = Ct5SessionCodec.buildSetParameters(
            kX100 = 125,
            rX100 = 100,
            intervalMin = Ct5Constants.SAMPLE_INTERVAL_MIN,
            cycleDays = Ct5Constants.CYCLE_DAYS,
            randomId = VECTOR_RANDOM_ID,
            cipherId = VECTOR_CIPHER_ID,
        )
        assertTrue(
            "the double must build the exact bytes the sensor was bound with",
            SET_PARAMETERS_VECTOR.contentEquals(built!!),
        )
        assertTrue(Ct5SessionCodec.frameIsLegal(built))
        assertTrue(
            Ct5SessionCodec.verifySetParametersEcho(
                SET_PARAMETERS_VECTOR, 125, 100, Ct5Constants.SAMPLE_INTERVAL_MIN,
                Ct5Constants.CYCLE_DAYS, VECTOR_RANDOM_ID, VECTOR_CIPHER_ID,
            ),
        )
        assertFalse(
            Ct5SessionCodec.verifySetParametersEcho(
                SET_PARAMETERS_VECTOR, 126, 100, Ct5Constants.SAMPLE_INTERVAL_MIN,
                Ct5Constants.CYCLE_DAYS, VECTOR_RANDOM_ID, VECTOR_CIPHER_ID,
            ),
        )
    }

    /** Fails on hardware, after `0x38` plants the unbind password on a frame that won't resend. */
    @Test
    fun `the echo is the obfuscated frame not the plaintext payload`() {
        val payload = Ct5SessionCodec.setParametersPayload(
            125, 100, Ct5Constants.SAMPLE_INTERVAL_MIN, Ct5Constants.CYCLE_DAYS, VECTOR_RANDOM_ID,
        )!!
        val plaintextReply = Ct5SessionCodec.frame(Ct5Constants.Opcode.SET_PARAMETERS, payload)
        assertFalse(
            "the transform is not an identity, so these are genuinely different frames",
            plaintextReply.contentEquals(SET_PARAMETERS_VECTOR),
        )
        assertFalse(
            "a plaintext-payload reply is not what the sensor sends and proves nothing about K",
            Ct5SessionCodec.verifySetParametersEcho(
                plaintextReply, 125, 100, Ct5Constants.SAMPLE_INTERVAL_MIN,
                Ct5Constants.CYCLE_DAYS, VECTOR_RANDOM_ID, VECTOR_CIPHER_ID,
            ),
        )
    }

    @Test
    fun `the identity string decodes to the constants a real sensor was measured at`() {
        val id = Ct5SessionCodec.parseSsnResponse(Ct5SessionCodec.ssnReply(ANCHOR_SSN), Ct5Session.NO_CIPHER_ID)!!
        assertEquals(ANCHOR_SSN, id.ssn)
        assertEquals(125, id.kX100)
        assertEquals(100, id.rX100)
        assertEquals(456, id.unitOrder)
    }

    @Test
    fun `the frames the hardware answered are built exactly`() {
        assertTrue(byteArrayOf(0x01).contentEquals(Ct5SessionCodec.buildVersionRequest()))
        assertTrue(byteArrayOf(0x05, 0x55, 0xAA.toByte(), 0x04).contentEquals(Ct5SessionCodec.buildSelfCheck()))
        assertTrue(byteArrayOf(0x3F, 0x55, 0xAA.toByte(), 0x3E).contentEquals(Ct5SessionCodec.buildQuerySsn()))
        assertTrue(byteArrayOf(0x06, 0x55, 0xAA.toByte(), 0x05).contentEquals(Ct5SessionCodec.buildInit()))
        assertTrue(byteArrayOf(0x0F, 0x55, 0xAA.toByte(), 0x0E).contentEquals(Ct5SessionCodec.buildLowPower()))
    }

    @Test
    fun `convolve keeps the Rust's four terms, and the key follows`() {
        val b = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val a = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        // Pinned to ct5_session.rs; all seven terms would give key 242.
        assertTrue(intArrayOf(2890, 8959, 18496, 31790).contentEquals(Ct5SessionCodec.convolve(b, a)))
        val nonceB = byteArrayOf(1, 2, 3, 4)
        assertEquals(219, Ct5SessionCodec.cipherIdFromSetIdReply(Ct5SessionCodec.setIdReply(nonceB, b), a, nonceB))
    }

    @Test
    fun `the obfuscation round-trips at every key and every record length`() {
        for (len in listOf(1, 11, 12, 15, 18, 21)) {
            val payload = ByteArray(len) { (it * 37 + 11).toByte() }
            for (k in 0..255) {
                assertTrue(
                    "len $len key $k",
                    Ct5SessionCodec.deobfuscate(Ct5SessionCodec.obfuscate(payload, k), k).contentEquals(payload),
                )
            }
        }
    }

    @Test
    fun `the real advertisement reads unbound and not running`() {
        val mfg = ByteArray(26).also {
            it[0] = 'C'.code.toByte(); it[1] = 'G'.code.toByte(); it[2] = 'M'.code.toByte()
            it[3] = 0
            for (i in 4..24) it[i] = 0xFF.toByte()
            it[25] = 0xEB.toByte()
        }
        val a = Ct5SessionCodec.parseAdvert(mfg)!!
        assertFalse(a.bound)
        assertFalse("the block is all 0xFF until the sensor runs", a.running)
        assertTrue("the sum over [4..25] is the trailing byte", a.checksumValid)
        assertEquals(6, a.recordCount)
        assertNull(Ct5SessionCodec.parseAdvert(byteArrayOf(0x59, 0x00, 0x03)))
    }

    private companion object {
        /** SYNTHETIC key material, never a live sensor's. */
        const val VECTOR_CIPHER_ID = 0x5A
        const val VECTOR_RANDOM_ID = "1234"

        /** Synthetic; spells the `K` and `R` a real sensor was measured at. */
        const val ANCHOR_SSN = "001734456789012510B2C"

        /** The two warm-up records the hardware produced, re-sealed under [VECTOR_CIPHER_ID]. */
        val CAPTURED_PUSH_A = byteArrayOf(
            0x35, 0x05, 0x00, 0xF0.toByte(), 0xF0.toByte(), 0xF1.toByte(), 0xDB.toByte(),
            0xCD.toByte(), 0xF3.toByte(), 0x0F, 0x0F, 0x0F, 0x6B, 0x94.toByte(), 0x6D, 0x2B, 0x0D,
            0x31, 0xA8.toByte(),
        )
        val CAPTURED_PUSH_B = byteArrayOf(
            0x35, 0x06, 0x00, 0x0F, 0x0F, 0x0E, 0x33, 0x32, 0xC9.toByte(), 0xF0.toByte(),
            0xF0.toByte(), 0xF0.toByte(), 0x94.toByte(), 0x6B, 0x92.toByte(), 0xD4.toByte(),
            0xF2.toByte(), 0xCC.toByte(), 0x88.toByte(),
        )

        /** Computed independently of this codec. */
        val SET_PARAMETERS_VECTOR = byteArrayOf(
            0x38, 0x0F, 0xF8.toByte(), 0x0F, 0xF0.toByte(), 0xF1.toByte(), 0xFF.toByte(), 0x3C, 0x0F,
            0x1F, 0xE1.toByte(), 0x1E, 0x1C, 0xB3.toByte(),
        )
    }
}
