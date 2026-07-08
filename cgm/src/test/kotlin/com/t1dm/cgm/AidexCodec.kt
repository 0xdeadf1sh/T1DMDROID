package com.t1dm.cgm

import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.PrevGlucose

/**
 * A faithful pure-Kotlin port of the CGM.md §3.1 layout and §3.2 CRC32, used by the tests to
 * stand in for the (not-yet-wired) Rust `decode_advert`, and to *encode* synthetic valid adverts
 * so the pipeline can be exercised end-to-end without radio. When the Rust core lands, the same
 * golden bytes gate it under `rust-golden`; this port exists only to make the :cgm pipeline
 * deterministically testable now.
 */
object AidexCodec {

    private const val POLY = 0x04C11DB7L
    private const val SEED_MOD = 0x7FA777L

    fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** MSB-first bit-serial CRC32, poly 0x04C11DB7, no reflect, no xorout (CGM.md §3.2). */
    fun crc32Normal(buf: ByteArray, len: Int, init: Long): Long {
        var crc = init and 0xFFFFFFFFL
        for (k in 0 until len) {
            crc = crc xor ((buf[k].toLong() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000L != 0L) {
                    ((crc shl 1) xor POLY) and 0xFFFFFFFFL
                } else {
                    (crc shl 1) and 0xFFFFFFFFL
                }
            }
        }
        return crc and 0xFFFFFFFFL
    }

    /** seed = (le32[0]+le32[4]+le32[8]+le32[12]) mod 0x7FA777, over the 16-byte body. */
    fun seedFor(body16: ByteArray): Long =
        (le32(body16, 0) + le32(body16, 4) + le32(body16, 8) + le32(body16, 12)) % SEED_MOD

    /** CRC over payload[0..15] with the payload-derived seed. */
    fun crcOf(payload: ByteArray): Long = crc32Normal(payload, 16, seedFor(payload))

    /** Decode + CRC-validate a ≥20-byte LinX advert payload; `null` on short / CRC-fail. */
    fun decode(payload: ByteArray): DecodedAdvert? {
        if (payload.size < 20) return null
        val calc = crcOf(payload)
        val field = le32(payload, 16)
        if (calc != field) return null

        val bitfield = le16(payload, 5)
        fun prev(off: Int): PrevGlucose {
            val bf = le16(payload, off)
            return PrevGlucose(
                glucoseMgdl = bf and 0x3FF,
                valid = (bf ushr 15) and 1 == 1,
                quality = payload[off + 2].toInt() and 0xFF,
            )
        }
        return DecodedAdvert(
            minFromStart = le16(payload, 0),
            status = payload[2].toInt() and 0xFF,
            trendTenthsPerMin = payload[4].toInt(),      // signed i8
            glucoseMgdl = bitfield and 0x3FF,
            valid = (bitfield ushr 15) and 1 == 1,
            quality = payload[7].toInt() and 0xFF,
            prev = listOf(prev(8), prev(11)),
            crc32 = field,
        )
    }

    /** Build a 20-byte advert payload with a valid CRC32 (for synthetic pipeline tests). */
    fun encode(
        minFromStart: Int,
        glucose: Int,
        trend: Int = 0,
        status: Int = 0,
        valid: Boolean = true,
        quality: Int = 0,
        prev1: Int = glucose,
        prev2: Int = glucose,
    ): ByteArray {
        val p = ByteArray(20)
        fun putU16(off: Int, v: Int) {
            p[off] = (v and 0xFF).toByte()
            p[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        fun bitfield(g: Int, v: Boolean) = (g and 0x3FF) or (if (v) 1 shl 15 else 0)

        putU16(0, minFromStart)
        p[2] = status.toByte()
        p[3] = 0
        p[4] = trend.toByte()
        putU16(5, bitfield(glucose, valid))
        p[7] = quality.toByte()
        putU16(8, bitfield(prev1, true))
        p[10] = quality.toByte()
        putU16(11, bitfield(prev2, true))
        p[13] = quality.toByte()
        putU16(14, 0)
        val crc = crcOf(p)
        p[16] = (crc and 0xFF).toByte()
        p[17] = ((crc ushr 8) and 0xFF).toByte()
        p[18] = ((crc ushr 16) and 0xFF).toByte()
        p[19] = ((crc ushr 24) and 0xFF).toByte()
        return p
    }
}
