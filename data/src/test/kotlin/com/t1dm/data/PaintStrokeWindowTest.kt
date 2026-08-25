package com.t1dm.data

import com.t1dm.core.model.PaintStroke
import com.t1dm.data.db.PaintStrokeBlob
import org.junit.Assert.assertEquals
import org.junit.Test

class PaintStrokeWindowTest {

    @Test
    fun boundsSpanTheWholePolyline() {
        val e = stroke(longArrayOf(T0 + 100, T0 + 900, T0 + 400)).toEntity()
        assertEquals(T0 + 100, e.minTsMs)
        assertEquals(T0 + 900, e.maxTsMs)
    }

    @Test
    fun boundsSurviveAStrokeDrawnBackwardsInTime() {
        val e = stroke(longArrayOf(T0 + 900, T0 + 500, T0 + 100)).toEntity()
        assertEquals(T0 + 100, e.minTsMs)
        assertEquals(T0 + 900, e.maxTsMs)
    }

    @Test
    fun boundsSurviveAStrokeThatDoublesBack() {
        val e = stroke(longArrayOf(T0 + 300, T0 + 999, T0 + 320)).toEntity()
        assertEquals(T0 + 300, e.minTsMs)
        assertEquals(T0 + 999, e.maxTsMs)
    }

    @Test
    fun aSingleInstantStrokeHasCollapsedBounds() {
        val e = stroke(longArrayOf(T0 + 42)).toEntity()
        assertEquals(T0 + 42, e.minTsMs)
        assertEquals(T0 + 42, e.maxTsMs)
    }

    @Test
    fun theEncodedPolylineSurvivesTheEntityRoundTrip() {
        val ts = longArrayOf(T0, T0 + 20, T0 + 5)
        val decoded = PaintStrokeBlob.decode(stroke(ts).toEntity().points)
        assertEquals(ts.toList(), decoded.tsMs.toList())
    }

    /** `tool` is open TEXT and `widthDp` an unclamped REAL, so a later build's pen decodes here. */
    @Test
    fun theToolNameAndItsWidthSurviveTheEntityRoundTrip() {
        val s = PaintStroke(
            id = 0, createdAtMs = T0, tool = "broad", colorArgb = 0xFF112233.toInt(), widthDp = 96f,
            tsMs = longArrayOf(T0, T0 + 10), yFrac = floatArrayOf(0f, 1f),
        )
        val back = s.toEntity().toModel()
        assertEquals("broad", back.tool)
        assertEquals(96f, back.widthDp, 0f)
        assertEquals(0xFF112233.toInt(), back.colorArgb)
    }

    @Test
    fun windowSelectsEveryIntersectingStroke() {
        for ((name, minTs, maxTs, expected) in CASES) {
            assertEquals(name, expected, overlaps(minTs, maxTs, WINDOW_FROM, WINDOW_TO))
        }
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val WINDOW_FROM = T0 + 1_000L
        const val WINDOW_TO = T0 + 2_000L

        /** Transcribes `PaintStrokeDao.observeOverlapping`'s WHERE clause: intersection, inclusive. */
        fun overlaps(minTsMs: Long, maxTsMs: Long, fromMs: Long, toMs: Long): Boolean =
            maxTsMs >= fromMs && minTsMs <= toMs

        /** Replayed row-for-row by `PaintStrokeDaoTest`. */
        val CASES = listOf(
            Case("wholly before the window", T0, T0 + 500, false),
            Case("wholly after the window", T0 + 3_000, T0 + 4_000, false),
            Case("ends exactly on the left edge", T0, WINDOW_FROM, true),
            Case("starts exactly on the right edge", WINDOW_TO, T0 + 5_000, true),
            Case("one instant just left of the edge", WINDOW_FROM - 1, WINDOW_FROM - 1, false),
            Case("one instant on the left edge", WINDOW_FROM, WINDOW_FROM, true),
            Case("contained in the window", T0 + 1_200, T0 + 1_800, true),
            Case("straddles the left edge", T0 + 500, T0 + 1_500, true),
            Case("straddles the right edge", T0 + 1_500, T0 + 2_500, true),
            Case("wider than the window on both sides", T0, T0 + 9_000, true),
        )

        fun stroke(tsMs: LongArray) = PaintStroke(
            id = 0,
            createdAtMs = T0,
            tool = "brush",
            colorArgb = 0xFF00FF00.toInt(),
            widthDp = 3f,
            tsMs = tsMs,
            yFrac = FloatArray(tsMs.size) { 0.5f },
        )
    }

    data class Case(val name: String, val minTsMs: Long, val maxTsMs: Long, val visible: Boolean)
}
