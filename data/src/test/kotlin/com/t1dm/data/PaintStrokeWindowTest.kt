package com.t1dm.data

import com.t1dm.core.model.PaintStroke
import com.t1dm.data.db.PaintStrokeBlob
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two halves of the `bg_paint_stroke` viewport cull (Room v8): the indexed time bounds a stroke is
 * stored under, and the window predicate they are selected by.
 *
 * The bounds are real production code ([toEntity]) and carry the actual bug surface — a freehand
 * stroke may be dragged backwards in time, so its first/last sample are not its extremes. The window
 * rule is pinned here as a table of cases and replayed verbatim against real SQLite by the instrumented
 * `PaintStrokeDaoTest`; this half runs on the host JVM, where no Room query can.
 */
class PaintStrokeWindowTest {

    // ── item 1: indexed bounds are scanned, not read off the ends ───────────────────────────

    @Test
    fun boundsSpanTheWholePolyline() {
        val e = stroke(longArrayOf(T0 + 100, T0 + 900, T0 + 400)).toEntity()
        assertEquals(T0 + 100, e.minTsMs)
        assertEquals(T0 + 900, e.maxTsMs)
    }

    /** Dragged leftwards: the last sample is the EARLIEST, so first/last would invert the bounds. */
    @Test
    fun boundsSurviveAStrokeDrawnBackwardsInTime() {
        val e = stroke(longArrayOf(T0 + 900, T0 + 500, T0 + 100)).toEntity()
        assertEquals(T0 + 100, e.minTsMs)
        assertEquals(T0 + 900, e.maxTsMs)
    }

    /** Dragged out and back: the extreme is in the middle, unreachable from either end. */
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

    /**
     * The two columns that carry a pen's identity are pure pass-through in both directions: `tool` is
     * open TEXT with no converter, `widthDp` a plain REAL with no clamp. That is the whole of the
     * forward-compatibility story — a pen a later build invents, at whatever width it was authored,
     * decodes on an older one, which resolves the unknown name onto the fine pencil and draws it.
     */
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

    // ── item 2: the window predicate ────────────────────────────────────────────────────────

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

        /**
         * The transcription of `PaintStrokeDao.observeOverlapping`'s WHERE clause: intersection, not
         * containment, inclusive at both ends. The SQL itself is exercised by the instrumented test.
         */
        fun overlaps(minTsMs: Long, maxTsMs: Long, fromMs: Long, toMs: Long): Boolean =
            maxTsMs >= fromMs && minTsMs <= toMs

        /** name, minTsMs, maxTsMs, is-visible — replayed row-for-row by `PaintStrokeDaoTest`. */
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
