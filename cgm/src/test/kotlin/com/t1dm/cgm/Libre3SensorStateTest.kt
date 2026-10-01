package com.t1dm.cgm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Round-trip and fail-closed decode of the versioned state blob (§4). */
class Libre3SensorStateTest {

    private fun state(kAuth: ByteArray? = null) = Libre3SensorState(
        receiverId = 0x039DF1A1u,
        serial = "7A1234ABC",
        bleAddress = "C0:FF:EE:C0:FF:EE",
        blePin = byteArrayOf(0x11, 0x22, 0x33, 0x44),
        activationTimeS = 1_790_000_000L,
        wearDurationMin = 20_160,
        region = Libre3Region.Eu,
        provisionedAtMs = 1_800_000_000_000L,
        kAuth = kAuth,
        lastRealtimeLifeCount = 150,
        lastHistoricalLifeCount = 140,
    )

    @Test
    fun `encode then decode is the same state`() {
        assertEquals(state(), Libre3SensorState.decode(state().encode()))
    }

    @Test
    fun `round trip with a kAuth blob`() {
        val kAuth = ByteArray(40) { it.toByte() }
        assertEquals(state(kAuth), Libre3SensorState.decode(state(kAuth).encode()))
    }

    @Test
    fun `the walk floor round-trips and decodes to null before the first walk`() {
        val walked = state().copy(lastHistoricalFloorLifeCount = 1241)
        assertEquals(walked, Libre3SensorState.decode(walked.encode()))
        assertNull(Libre3SensorState.decode(state().encode())?.lastHistoricalFloorLifeCount)
    }

    /** v1 blobs (pre-walk builds) are on disk as the only PIN copy; they must keep decoding. */
    @Test
    fun `a v1 blob decodes with the walk floor absent`() {
        val blob = state().encode()
        assertEquals(54, blob.size) // v3: FIXED 47 + kAuth 0 + tail 7
        val v1 = blob.copyOfRange(0, 51).also { it[0] = 1 }
        val decoded = Libre3SensorState.decode(v1)
        assertEquals(state().copy(lastHistoricalFloorLifeCount = null), decoded)
    }

    /**
     * v2's walk floor advanced even over writes the GATT rejected (live 2026-09-24), so a v2
     * blob's floor is distrusted once: v3 decodes it as absent and the walk re-covers.
     */
    @Test
    fun `a v2 blob's walk floor is distrusted and decodes to null`() {
        val blob = state().encode()
        val v2 = blob.copyOfRange(0, 53).also { it[0] = 2 }
        val decoded = Libre3SensorState.decode(v2)
        assertEquals(state().copy(lastHistoricalFloorLifeCount = null), decoded)
    }

    @Test
    fun `life counts before the first reading decode to null`() {
        val blank = state().copy(lastRealtimeLifeCount = null, lastHistoricalLifeCount = null)
        assertEquals(blank, Libre3SensorState.decode(blank.encode()))
    }

    @Test
    fun `a wrong version or truncated blob is not a state`() {
        val blob = state().encode()
        assertNull(Libre3SensorState.decode(blob.copyOf().also { it[0] = 9 }))
        assertNull(Libre3SensorState.decode(blob.copyOfRange(0, blob.size - 1)))
        assertNull(Libre3SensorState.decode(ByteArray(0)))
    }

    @Test
    fun `a bad region byte is not a state`() {
        val blob = state().encode()
        blob[7] = 9
        assertNull(Libre3SensorState.decode(blob))
    }

    @Test
    fun `the provisioned address normalizes case and colons`() {
        val reply = Libre3SwitchResponse(
            bleAddress = "c0ffEec0ffee",
            blePin = byteArrayOf(1, 2, 3, 4),
            activationTimeS = 123L,
        )
        val patch = Libre3PatchInfo(
            wearDurationMin = 20_160,
            rawStatus = 0,
            fw = "1.4.2.30",
            sensorState = 3,
            serial = "7A1234ABC",
        )
        val provisioned = Libre3SensorState.provisioned(
            patch, reply, 0x039DF1A1u, Libre3Region.Us, 5L,
        )
        assertEquals("C0:FF:EE:C0:FF:EE", provisioned.bleAddress)
        assertEquals("C0:FF:EE:C0:FF:EE", provisioned.bleAddressDisplay)
        assertEquals(123L, provisioned.activationTimeS)
    }

    @Test
    fun `the wire layout is little-endian and stable`() {
        val blob = state().encode()
        assertEquals(3.toByte(), blob[0])
        // receiverId 0x039DF1A1 → a1 f1 9d 03.
        assertArrayEquals(byteArrayOf(0xA1.toByte(), 0xF1.toByte(), 0x9D.toByte(), 0x03), blob.copyOfRange(1, 5))
        // FIXED 47 (through kAuthLen) + 7 tail (realtime, historical, floor, epoch); no kAuth.
        assertEquals(54, blob.size)
    }

    @Test
    fun `toString never names the pin or kAuth`() {
        val s = state(kAuth = ByteArray(8)).toString()
        assertFalse(s.contains("pin", ignoreCase = true))
        assertFalse(s.contains("kAuth"))
    }
}