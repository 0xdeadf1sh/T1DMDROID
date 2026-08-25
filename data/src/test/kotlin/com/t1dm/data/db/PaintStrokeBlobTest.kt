package com.t1dm.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PaintStrokeBlobTest {

    @Test
    fun roundTripsATypicalStroke() {
        val ts = longArrayOf(T0, T0 + 16, T0 + 33, T0 + 50)
        val y = floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f)
        val back = PaintStrokeBlob.decode(PaintStrokeBlob.encode(ts, y))
        assertArrayEquals(ts, back.tsMs)
        assertArrayEquals(y, back.yFrac, 0f)
    }

    @Test
    fun roundTripsAnEmptyStroke() {
        val blob = PaintStrokeBlob.encode(LongArray(0), FloatArray(0))
        assertEquals("empty stroke is header-only", 8, blob.size)
        val back = PaintStrokeBlob.decode(blob)
        assertEquals(0, back.tsMs.size)
        assertEquals(0, back.yFrac.size)
    }

    @Test
    fun roundTripsASinglePointTap() {
        val back = PaintStrokeBlob.decode(PaintStrokeBlob.encode(longArrayOf(T0), floatArrayOf(0.42f)))
        assertArrayEquals(longArrayOf(T0), back.tsMs)
        assertEquals(0.42f, back.yFrac[0], 0f)
    }

    @Test
    fun roundTripsAVeryLongStroke() {
        val n = 250_000
        val ts = LongArray(n) { T0 + it * 7L }
        val y = FloatArray(n) { (it % 1000) / 1000f }
        val blob = PaintStrokeBlob.encode(ts, y)
        assertEquals(8 + n * 12, blob.size)
        val back = PaintStrokeBlob.decode(blob)
        assertArrayEquals(ts, back.tsMs)
        assertArrayEquals(y, back.yFrac, 0f)
    }

    @Test
    fun preservesNonMonotonicTimestampsInOrder() {
        val ts = longArrayOf(T0 + 500, T0 + 200, T0, T0 + 900)
        val y = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        assertArrayEquals(ts, PaintStrokeBlob.decode(PaintStrokeBlob.encode(ts, y)).tsMs)
    }

    /** Out-of-panel y is legal; the renderer clips. */
    @Test
    fun preservesOutOfRangeAndExtremeValues() {
        val ts = longArrayOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)
        val y = floatArrayOf(-3.5f, 0f, 12.25f)
        val back = PaintStrokeBlob.decode(PaintStrokeBlob.encode(ts, y))
        assertArrayEquals(ts, back.tsMs)
        assertArrayEquals(y, back.yFrac, 0f)
    }

    @Test
    fun writesTheDocumentedLittleEndianLayout() {
        val blob = PaintStrokeBlob.encode(longArrayOf(1L), floatArrayOf(0f))
        assertEquals('T'.code.toByte(), blob[0])
        assertEquals('1'.code.toByte(), blob[1])
        assertEquals('P'.code.toByte(), blob[2])
        assertEquals(PaintStrokeBlob.VERSION.toByte(), blob[3])
        // count = 1 then tsMs = 1, little-endian.
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), blob.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0), blob.copyOfRange(8, 16))
        assertEquals("12 B stride per point", 20, blob.size)
    }

    @Test
    fun rejectsMismatchedChannelLengths() {
        assertRejects { PaintStrokeBlob.encode(longArrayOf(1L, 2L), floatArrayOf(0f)) }
    }

    @Test
    fun rejectsATruncatedHeader() {
        assertRejects { PaintStrokeBlob.decode(byteArrayOf('T'.code.toByte(), '1'.code.toByte())) }
    }

    @Test
    fun rejectsAForeignBlob() {
        // The f64 series codec `customCurve` uses: right column type, wrong payload.
        assertRejects { PaintStrokeBlob.decode(doubleArrayOf(1.0, 2.0).toBlob()) }
    }

    @Test
    fun rejectsAnUnknownVersion() {
        val blob = PaintStrokeBlob.encode(longArrayOf(T0), floatArrayOf(0.5f))
        blob[3] = 99
        assertRejects { PaintStrokeBlob.decode(blob) }
    }

    @Test
    fun rejectsATruncatedPayload() {
        val blob = PaintStrokeBlob.encode(longArrayOf(T0, T0 + 1), floatArrayOf(0.1f, 0.2f))
        assertRejects { PaintStrokeBlob.decode(blob.copyOf(blob.size - 1)) }
    }

    private fun assertRejects(body: () -> Unit) {
        try {
            body()
            fail("malformed input was accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue("message names the fault", !e.message.isNullOrBlank())
        }
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}
