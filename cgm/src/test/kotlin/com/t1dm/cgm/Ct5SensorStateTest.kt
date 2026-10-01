package com.t1dm.cgm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CIPHER_ID unrecoverable, RANDOM_ID the unbind password, bindTimeMs anchors wear timestamps. */
class Ct5SensorStateTest {

    @Test
    fun `a state round-trips through its blob exactly`() {
        val decoded = Ct5SensorState.decode(STATE.encode())
        assertEquals(STATE, decoded)
        // A data class equals on two ByteArrays is the trap: compare field by field too.
        decoded!!
        assertEquals(0x5A, decoded.cipherId)
        assertTrue(decoded.a.contentEquals(STATE.a))
        assertTrue(decoded.b.contentEquals(STATE.b))
        assertEquals("1234", decoded.randomId)
        assertEquals(125, decoded.kX100)
        assertEquals(100, decoded.rX100)
        assertEquals("001734456789012510B2C", decoded.ssn)
        assertEquals(1_755_431_173_000L, decoded.bindTimeMs)
        assertTrue(decoded.initialised)
    }

    /** Anything but the affirmative byte reads unfinished, else a reconnect reports nothing. */
    @Test
    fun `an unfinished bind round-trips, and a doubtful flag reads as unfinished`() {
        val half = STATE.copy(initialised = false)
        assertEquals(half, Ct5SensorState.decode(half.encode()))
        assertEquals(false, Ct5SensorState.decode(half.encode())!!.initialised)

        val blob = STATE.encode()
        for (byte in listOf(0, 2, 0xFF)) {
            val doubtful = blob.copyOf().also { it[27] = byte.toByte() }
            assertEquals("flag $byte", false, Ct5SensorState.decode(doubtful)!!.initialised)
        }
    }

    @Test
    fun `K and R survive as exact hundredths`() {
        // Never floats: K multiplies every reading; 1.25 as 1.2499999 is silently wrong glucose.
        for (k in listOf(0, 1, 52, 125, 999, 1_000, 25_599)) {
            val round = Ct5SensorState.decode(STATE.copy(kX100 = k, rX100 = k).encode())!!
            assertEquals(k, round.kX100)
            assertEquals(k, round.rX100)
        }
    }

    @Test
    fun `every byte of the blob matters`() {
        val blob = STATE.encode()
        for (i in blob.indices) {
            val flipped = blob.copyOf().also { it[i] = (it[i].toInt() xor 0xFF).toByte() }
            val decoded = Ct5SensorState.decode(flipped)
            if (decoded != null) assertNotEquals("byte $i decoded back to the original", STATE, decoded)
        }
    }

    @Test
    fun `a blob of the wrong version or shape is refused rather than half-read`() {
        val blob = STATE.encode()
        assertNull("no version byte", Ct5SensorState.decode(ByteArray(0)))
        assertNull("truncated header", Ct5SensorState.decode(blob.copyOfRange(0, 20)))
        assertNull("truncated SSN", Ct5SensorState.decode(blob.copyOfRange(0, blob.size - 1)))
        assertNull("trailing junk", Ct5SensorState.decode(blob + byteArrayOf(0)))
        assertNull(
            "a future version",
            Ct5SensorState.decode(blob.copyOf().also { it[0] = 3 }),
        )
        // A length byte that disagrees with the payload.
        assertNull(Ct5SensorState.decode(blob.copyOf().also { it[26] = 99 }))
        // An empty SSN: a state with no identity string is not one.
        assertNull(Ct5SensorState.decode(blob.copyOfRange(0, 27).also { it[26] = 0 }))
    }

    @Test
    fun `a RANDOM_ID that is not four digits is refused`() {
        val blob = STATE.encode()
        for (i in 10..13) {
            val bad = blob.copyOf().also { it[i] = 'x'.code.toByte() }
            assertNull("byte $i", Ct5SensorState.decode(bad))
        }
    }

    @Test
    fun `the state never prints its own secrets`() {
        val text = STATE.toString()
        assertTrue(text.contains("001734456789012510B2C"))
        assertTrue(text.contains("secrets withheld"))
        assertTrue("RANDOM_ID must not appear", !text.contains("1234"))
    }

    @Test
    fun `the random material is of the widths the wire fixes`() {
        repeat(50) {
            assertEquals(Ct5Constants.NONCE_BYTES, SecureRandomCt5Nonces.nonce().size)
            val id = SecureRandomCt5Nonces.randomId()
            assertEquals(Ct5Constants.RANDOM_ID_DIGITS, id.length)
            assertTrue(id, id.all { c -> c in '0'..'9' })
        }
    }

    private companion object {
        /** Every value here is INVENTED: session key, unbind password, and both nonces. */
        val STATE = Ct5SensorState(
            cipherId = 0x5A,
            a = byteArrayOf(0x3C, 0x71, 0xA2.toByte(), 0x0E),
            b = byteArrayOf(0x5D, 0x08, 0xE4.toByte(), 0x93.toByte()),
            randomId = "1234",
            kX100 = 125,
            rX100 = 100,
            ssn = "001734456789012510B2C",
            bindTimeMs = 1_755_431_173_000L,
            initialised = true,
        )
    }
}
