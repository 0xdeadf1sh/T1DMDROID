package com.t1dm.cgm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ct5ConstantsTest {

    @Test
    fun `a BSN is taken from the name by length and digit class, there being no separator`() {
        assertEquals("0123456789", Ct5Constants.bsnFrom("Anytime0123456789"))
        assertNull("nine digits", Ct5Constants.bsnFrom("Anytime012345678"))
        assertNull("eleven digits", Ct5Constants.bsnFrom("Anytime01234567891"))
        assertNull("not digits", Ct5Constants.bsnFrom("Anytime01234567A9"))
        assertNull("nothing after the prefix", Ct5Constants.bsnFrom("Anytime"))
        assertNull("another family", Ct5Constants.bsnFrom("LinX-00000T1DM0"))
        assertNull("a separator this family does not use", Ct5Constants.bsnFrom("Anytime-0123456789"))
    }

    @Test
    fun `warm-up is expressed in minutes because every consumer reads it as minutes`() {
        // Minutes, not samples: a count here would understate a sensor's age threefold.
        assertEquals(45, Ct5Constants.warmupWindowMinFor(0))
        assertEquals(45, Ct5Constants.warmupWindowMinFor(1))
        assertEquals(60, Ct5Constants.warmupWindowMinFor(2))
        assertEquals(60, Ct5Constants.warmupWindowMinFor(3))
        assertEquals(45, Ct5Constants.warmupWindowMinFor(4))
        assertEquals(45, Ct5Constants.warmupWindowMinFor(9))
        assertEquals(
            Ct5Constants.WARMUP_SAMPLES * Ct5Constants.SAMPLE_INTERVAL_MIN,
            Ct5Constants.warmupWindowMinFor(0),
        )
    }

    @Test
    fun `the rated cycle and the sample interval agree with the sample count`() {
        // 7695 samples of 3 minutes is 16.03 days, which is what confirms the cycle byte's units.
        val days = Ct5Constants.RATED_SAMPLES * Ct5Constants.SAMPLE_INTERVAL_MIN / 1440.0
        assertTrue("$days", days > 16.0 && days < 16.1)
    }

    @Test
    fun `the staleness window is under the loss-of-signal window and over two intervals`() {
        // Two missed pushes with a margin, and well inside the 20-minute loss-of-signal alarm.
        assertTrue(Ct5Constants.PUSH_STALE_MS > 2 * Ct5Constants.SAMPLE_INTERVAL_MS)
        assertTrue(Ct5Constants.PUSH_STALE_MS < 3 * Ct5Constants.SAMPLE_INTERVAL_MS)
        assertTrue(Ct5Constants.PUSH_STALE_MS < 20 * 60_000L)
    }

    @Test
    fun `each ladder rung comes after the last and the step deadline after the ladder`() {
        for (ladder in listOf(Ct5Constants.DEFAULT_LADDER_MS, Ct5Constants.SELF_CHECK_LADDER_MS)) {
            assertTrue(ladder.isNotEmpty())
            assertEquals(ladder.toList(), ladder.toList().sorted().distinct())
            assertTrue(Ct5Constants.stepTimeoutMs(ladder) > ladder.last())
        }
        assertTrue(
            "the self-check is the one frame on its own ladder",
            Ct5Constants.ladderFor(Ct5Constants.Opcode.SELF_CHECK)
                .contentEquals(Ct5Constants.SELF_CHECK_LADDER_MS),
        )
        for (op in listOf(0x03, 0x01, 0x30, 0x31, 0x38, 0x06, 0x3F)) {
            assertTrue("0x${op.toString(16)}", Ct5Constants.ladderFor(op).contentEquals(Ct5Constants.DEFAULT_LADDER_MS))
        }
    }

    @Test
    fun `the shared grid and range constants are read from the one place that defines them`() {
        // Not restated here: a second copy is exactly the divergence this suite exists to prevent.
        assertEquals(300_000L, CgmConstants.GRID_MS)
        assertEquals(18..800, CgmConstants.VALID_BG_RANGE)
    }

    @Test
    fun `the vendor UUIDs are the vendor's base and not the Bluetooth base`() {
        val bluetoothBase = "-0000-1000-8000-00805f9b34fb"
        assertTrue(!Ct5Constants.SERVICE_UUID.toString().lowercase().endsWith(bluetoothBase))
        assertTrue(!Ct5Constants.WRITE_UUID.toString().lowercase().endsWith(bluetoothBase))
        assertTrue(!Ct5Constants.NOTIFY_UUID.toString().lowercase().endsWith(bluetoothBase))
        assertTrue(Ct5Constants.CCCD_UUID.toString().lowercase().endsWith(bluetoothBase))
        assertTrue(Ct5Constants.DFU_SERVICE_UUID.toString().lowercase().endsWith(bluetoothBase))
    }

    @Test
    fun `the requested MTU can carry the identity reply`() {
        // The 0x3F reply is 22 bytes and needs 25 of MTU; the default 23 delivers 20.
        assertTrue(Ct5Constants.REQUESTED_MTU >= 25)
    }

    @Test
    fun `the manufacturer category is matched on its ASCII bytes`() {
        // Recognition is by category rather than by company id. `CGM` is 0x43 0x47 0x4D.
        assertTrue(Ct5Constants.hasCgmCategory(byteArrayOf(0x43, 0x47, 0x4D, 0x00)))
        assertTrue(Ct5Constants.hasCgmCategory("CGM".toByteArray(Charsets.US_ASCII)))
        assertTrue(!Ct5Constants.hasCgmCategory("CG".toByteArray(Charsets.US_ASCII)))
        assertTrue(!Ct5Constants.hasCgmCategory("cgm".toByteArray(Charsets.US_ASCII)))
        assertTrue(!Ct5Constants.hasCgmCategory(byteArrayOf(0x59, 0x00, 0x03)))
        assertTrue(!Ct5Constants.hasCgmCategory(ByteArray(0)))
    }

    /**
     * Observed on hardware 2026-09-04: two sensors, both reporting company id `0x4743` with 24 bytes
     * of data starting `0x4D`. That is the ASCII category split across the platform's own boundary,
     * and reading the data alone left the bind flag unread on every advertisement ever received.
     */
    @Test
    fun `the wire block is rebuilt from the company id the platform split off`() {
        val onWire = byteArrayOf(0x43, 0x47, 0x4D, 0x00) + ByteArray(22) { 0xFF.toByte() }
        val companyId = 0x4743
        val afterTheId = onWire.copyOfRange(2, onWire.size)

        assertTrue("the platform's own view is not a CGM block", !Ct5Constants.hasCgmCategory(afterTheId))

        val rebuilt = Ct5Constants.adPayloadOf(companyId, afterTheId)

        assertArrayEquals("byte for byte what the sensor sent", onWire, rebuilt)
        assertTrue(Ct5Constants.hasCgmCategory(rebuilt))
        assertEquals("and the length the protocol's offsets are written against", 26, rebuilt.size)
    }

    @Test
    fun `the company id is put back little-endian, as the platform read it`() {
        assertArrayEquals(
            byteArrayOf(0x43, 0x47, 0x01),
            Ct5Constants.adPayloadOf(0x4743, byteArrayOf(0x01)),
        )
        assertArrayEquals(byteArrayOf(0x00, 0x00), Ct5Constants.adPayloadOf(0, ByteArray(0)))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), Ct5Constants.adPayloadOf(0xFFFF, ByteArray(0)))
    }

    @Test
    fun `the accepted error codes are an allowlist that includes no error`() {
        assertTrue(0 in Ct5Constants.ACCEPTED_ERROR_CODES)
        assertEquals(setOf(0, 2, 4, 5, 105), Ct5Constants.ACCEPTED_ERROR_CODES)
    }

    /** Allowlist line: SAMPLE INDEX survives, glucose reads current/temp/K, not electrode. */
    @Test
    fun `the indexing fault is accepted and every electrode fault is refused`() {
        assertTrue("ERROR_ALGORITHM_DATA", 2 in Ct5Constants.ACCEPTED_ERROR_CODES)
        for (code in listOf(1, 11, 12, 13, 14, 15, 16, 102, 103)) {
            assertTrue("code $code reports the electrode", code !in Ct5Constants.ACCEPTED_ERROR_CODES)
        }
    }

    /** Forward skew subtracts from apparent age the §3.6-D rail measures, bounded tighter. */
    @Test
    fun `the forward skew bound is far tighter than the backward one`() {
        assertTrue(Ct5Constants.MAX_FORWARD_SKEW_MS < Ct5Constants.MAX_CLOCK_SKEW_MS)
        assertTrue(Ct5Constants.MAX_FORWARD_SKEW_MS < CgmConstants.GRID_MS)
        assertTrue("it must not eat a tenth of the dose freshness limit", Ct5Constants.MAX_FORWARD_SKEW_MS <= 60_000L)
    }

    /** escalatedLossMs is AlarmConfig's lossEscalatedMin, restated since :cgm can't see it. */
    @Test
    fun `the backward skew bound leaves a reporting sensor well inside the staleness window`() {
        val escalatedLossMs = 12 * 60_000L
        val worstApparentAgeMs = Ct5Constants.MAX_CLOCK_SKEW_MS + Ct5Constants.SAMPLE_INTERVAL_MS
        assertTrue(
            "a sensor reporting on cadence must never approach the escalated loss window",
            worstApparentAgeMs * 2 <= escalatedLossMs,
        )
    }

    @Test
    fun `the plausible temperature band is the vendor algorithm's own`() {
        // The vendor's algorithm clamps below 12 C; [12,48] C settled the byte order.
        assertEquals(1200..4800, Ct5Constants.PLAUSIBLE_TEMP_CX100)
        // All-0xFF padding decodes to 217.55 C; the two byte orders differ by tens of degrees.
        assertTrue(21755 !in Ct5Constants.PLAUSIBLE_TEMP_CX100)
        assertTrue(-3529 !in Ct5Constants.PLAUSIBLE_TEMP_CX100)
        assertTrue(3104 in Ct5Constants.PLAUSIBLE_TEMP_CX100)
        // Enough that a stutter doesn't end a session, few enough a wrong key shows in minutes.
        assertTrue(Ct5Constants.MAX_IMPLAUSIBLE_RECORDS in 2..5)
    }
}
