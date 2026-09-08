package com.t1dm.data.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** LE codec for bg_paint_stroke.points: T1P|ver|count, then (i64 tsMs,f32 yFrac); X is absolute. */
object PaintStrokeBlob {

    const val VERSION: Int = 1

    private const val HEADER_BYTES = 8
    private const val POINT_BYTES = 12

    private val MAGIC = byteArrayOf('T'.code.toByte(), '1'.code.toByte(), 'P'.code.toByte())

    /** Parallel arrays, always of equal length. */
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
        // Long arithmetic: an Int product would overflow and could match the length.
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
