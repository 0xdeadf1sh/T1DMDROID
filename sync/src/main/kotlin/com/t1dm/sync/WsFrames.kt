package com.t1dm.sync

import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Minimal RFC 6455 framing for the `/v1/stream` client — enough to consume a server-to-client push
 * stream (we only ever send Close/Pong). No external WS dependency: the framing lives here behind
 * the [StreamClient] seam so a later swap to OkHttp is local. Client→server frames MUST be masked;
 * server→client frames arrive unmasked. Isolated from any socket so the codec is host-testable.
 */
internal object WsFrames {
    const val OP_CONT = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    data class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray) {
        override fun equals(other: Any?) = other is Frame &&
            fin == other.fin && opcode == other.opcode && payload.contentEquals(other.payload)
        override fun hashCode() = (fin.hashCode() * 31 + opcode) * 31 + payload.contentHashCode()
    }

    /** The `Sec-WebSocket-Accept` a server must echo — lets the client validate the handshake. */
    fun acceptFor(key: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(sha1, android.util.Base64.NO_WRAP)
    }

    /** A masked client frame (FIN=1). [maskKey] is injectable for deterministic tests. */
    fun encodeClientFrame(opcode: Int, payload: ByteArray, maskKey: ByteArray = Random.nextBytes(4)): ByteArray {
        require(maskKey.size == 4)
        val len = payload.size
        val header = ArrayList<Byte>(14)
        header.add((0x80 or (opcode and 0x0F)).toByte())
        when {
            len < 126 -> header.add((0x80 or len).toByte())
            len <= 0xFFFF -> {
                header.add((0x80 or 126).toByte())
                header.add((len ushr 8).toByte()); header.add(len.toByte())
            }
            else -> {
                header.add((0x80 or 127).toByte())
                for (s in 7 downTo 0) header.add((len.toLong() ushr (8 * s)).toByte())
            }
        }
        maskKey.forEach { header.add(it) }
        val out = ByteArray(header.size + len)
        for (i in header.indices) out[i] = header[i]
        for (i in 0 until len) out[header.size + i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
        return out
    }

    /** Read one frame (server→client; unmasked, but masked payloads are unmasked defensively). */
    fun readFrame(input: InputStream): Frame {
        val b0 = readByte(input)
        val b1 = readByte(input)
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((readByte(input).toLong() shl 8) or readByte(input).toLong())
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or readByte(input).toLong() }
        }
        val mask = if (masked) readN(input, 4) else null
        val payload = readN(input, len.toInt())
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(fin, opcode, payload)
    }

    private fun readByte(input: InputStream): Int {
        val v = input.read()
        if (v < 0) throw EOFException("stream closed")
        return v and 0xFF
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("stream closed at $off/$n")
            off += r
        }
        return buf
    }
}
