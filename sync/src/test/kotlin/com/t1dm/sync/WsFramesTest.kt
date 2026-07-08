package com.t1dm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class WsFramesTest {

    private val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78.toByte())

    @Test
    fun encodeThenReadRoundTripsShortPayload() {
        val payload = "{\"type\":\"sample\"}".toByteArray()
        val encoded = WsFrames.encodeClientFrame(WsFrames.OP_TEXT, payload, mask)
        // Client frames MUST be masked (high bit of byte1 set) and carry the mask key.
        assertTrue((encoded[1].toInt() and 0x80) != 0)

        val frame = WsFrames.readFrame(ByteArrayInputStream(encoded))
        assertEquals(WsFrames.OP_TEXT, frame.opcode)
        assertTrue(frame.fin)
        assertEquals(String(payload), String(frame.payload))
    }

    @Test
    fun roundTripsExtended16BitLength() {
        val payload = ByteArray(500) { (it % 251).toByte() }   // > 125 ⇒ 16-bit length path
        val encoded = WsFrames.encodeClientFrame(WsFrames.OP_BINARY, payload, mask)
        assertEquals((0x80 or 126).toByte(), encoded[1])       // len marker 126

        val frame = WsFrames.readFrame(ByteArrayInputStream(encoded))
        assertEquals(WsFrames.OP_BINARY, frame.opcode)
        assertTrue(payload.contentEquals(frame.payload))
    }

    @Test
    fun readsUnmaskedServerFrame() {
        // Server→client frames are unmasked: FIN|TEXT, len 3, "abc".
        val server = byteArrayOf((0x80 or WsFrames.OP_TEXT).toByte(), 0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())
        val frame = WsFrames.readFrame(ByteArrayInputStream(server))
        assertEquals("abc", String(frame.payload))
    }
}
