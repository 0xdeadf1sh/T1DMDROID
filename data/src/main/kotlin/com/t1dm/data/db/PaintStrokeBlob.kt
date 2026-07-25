package com.t1dm.data.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Versioned little-endian codec for `bg_paint_stroke.points` — the (epoch-ms, y-fraction) polyline of
 * one freehand annotation. Fixed endianness for the same reason as [toBlob] (a DB copied between
 * hosts must decode identically), but with a header the `customCurve` f64 series does not carry: a
 * stroke's layout can plausibly grow a channel (pressure, velocity) later, and a keep-forever store
 * cannot retro-fit a discriminator onto rows written without one.
 *
 * v1 layout — an 8-byte header, then [count] fixed 12-byte points:
 * ```
 * 'T' '1' 'P' | u8 version | u32 count | (i64 tsMs, f32 yFrac) × count
 * ```
 * X is a full **absolute** i64 per point rather than an i32 delta from a base timestamp: a stroke
 * drawn across the 90-day window spans ~7.8e9 ms, which an i32 delta overflows, and 4 bytes a point
 * does not buy a zoom-dependent failure mode. f32 on Y resolves ~4 decimal orders finer than one
 * pixel of any plausible panel height.
 */
object PaintStrokeBlob {

    /** The layout version written into every new blob; [decode] refuses anything else. */
    const val VERSION: Int = 1

    private const val HEADER_BYTES = 8
    private const val POINT_BYTES = 12

    private val MAGIC = byteArrayOf('T'.code.toByte(), '1'.code.toByte(), 'P'.code.toByte())

    /** The decoded polyline; parallel arrays, always of equal length. */
    class Points internal constructor(val tsMs: LongArray, val yFrac: FloatArray)

    fun encode(tsMs: LongArray, yFrac: FloatArray): ByteArray {
        require(tsMs.size == yFrac.size) {
            "paint stroke carries ${tsMs.size} timestamps but ${yFrac.size} y values"
        }
        val buf = ByteBuffer.allocate(HEADER_BYTES + tsMs.size * POINT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.put(VERSION.toByte())
        buf.putInt(tsMs.size)
        for (i in tsMs.indices) {
            buf.putLong(tsMs[i])
            buf.putFloat(yFrac[i])
        }
        return buf.array()
    }

    fun decode(blob: ByteArray): Points {
        require(blob.size >= HEADER_BYTES) { "paint blob is ${blob.size} B, shorter than its $HEADER_BYTES B header" }
        require(blob[0] == MAGIC[0] && blob[1] == MAGIC[1] && blob[2] == MAGIC[2]) { "paint blob has no T1P magic" }
        val version = blob[3].toInt()
        require(version == VERSION) { "paint blob version $version is not readable (this build writes $VERSION)" }
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(HEADER_BYTES - Int.SIZE_BYTES)
        val count = buf.int
        // Long arithmetic: a hostile count would overflow the Int product and could match the length.
        require(count >= 0 && blob.size.toLong() == HEADER_BYTES + count.toLong() * POINT_BYTES) {
            "paint blob declares $count points but carries ${blob.size - HEADER_BYTES} B of payload"
        }
        val tsMs = LongArray(count)
        val yFrac = FloatArray(count)
        for (i in 0 until count) {
            tsMs[i] = buf.long
            yFrac[i] = buf.float
        }
        return Points(tsMs, yFrac)
    }
}
