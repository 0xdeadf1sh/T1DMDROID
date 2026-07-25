package com.t1dm.ui.game

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.PaintFrame
import com.t1dm.ui.graph.buildGraphFrame
import com.t1dm.ui.graph.buildPaintFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `(tsMs, yFrac)` → world projection. The whole reason the stroke store chose absolute epoch-ms is
 * on trial here: the game's x axis IS time, so registration between the art and the ground it hangs
 * over should cost nothing and require no fudge factor. Y is the panel's height fraction inverted onto
 * a y-up world, which is exact given the same fixed world map the terrain used.
 */
class WorldPaintTest {

    private val GRID = 300_000L
    private val T0 = 1_700_000_000_000L

    private fun reading(ts: Long, bg: Int) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 60, quality = 100, provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL, tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

    private fun track(hours: Int = 4): GameTrack {
        val rs = (0 until hours * 12).map { reading(T0 + it * GRID, 120) }
        return buildGameTrack(TrackTrace.of(buildGraphFrame(rs, UnitSpace.MgDl, maxPoints = rs.size + 1)))
    }

    private fun stroke(
        id: Long, tool: String = "fine", widthDp: Float = 6f,
        vararg pts: Pair<Long, Float>,
    ) = PaintStroke(
        id = id, createdAtMs = id, tool = tool, colorArgb = 0xFF33CC99.toInt(), widthDp = widthDp,
        tsMs = LongArray(pts.size) { pts[it].first },
        yFrac = FloatArray(pts.size) { pts[it].second },
    )

    @Test fun aStrokeLandsAtTheTimeAndHeightItWasDrawn() {
        val t = track()
        // Two points 30 minutes apart, at the plot's top edge and its exact middle.
        val f = buildPaintFrame(listOf(stroke(1L, pts = arrayOf(T0 to 0f, T0 + 30 * 60_000L to 0.5f))))
        val w = buildWorldPaint(f, t)

        assertEquals(1, w.strokeCount)
        assertEquals(2, w.pointCount)
        // x is time, straight through: 30 minutes is 30 metres at the shipped scale.
        assertEquals(0f, w.xs[0], 1e-3f)
        assertEquals(30f * METRES_PER_MINUTE, w.xs[1], 1e-3f)
        // yFrac 0 is the plot TOP, which is the world CEILING; 0.5 is half way down.
        assertEquals(WORLD_HEIGHT_M, w.ys[0], 1e-3f)
        assertEquals(WORLD_HEIGHT_M / 2f, w.ys[1], 1e-3f)
    }

    @Test fun theProjectionIsExactlyTheTerrainsOwn() {
        // The claim that makes the layer world-anchored rather than merely nearby: paint x and terrain
        // x are the SAME function of time, so a stroke drawn over a reading sits over that reading's
        // hill at every camera position, with no registration step anywhere.
        val t = track()
        val ts = T0 + 17 * GRID
        val f = buildPaintFrame(listOf(stroke(1L, pts = arrayOf(ts to 0.25f))))
        val w = buildWorldPaint(f, t)
        assertEquals(t.map.worldXOf(ts), w.xs[0], 0f)
    }

    @Test fun yFracOutsideThePlotStaysOutside() {
        // A finger that strayed past the plot box is clipped at draw time, never flattened onto the
        // edge — so the world must not clamp it either.
        val t = track()
        val f = buildPaintFrame(listOf(stroke(1L, pts = arrayOf(T0 to -0.2f, T0 + 60_000L to 1.4f))))
        val w = buildWorldPaint(f, t)
        assertTrue("above the ceiling", w.ys[0] > WORLD_HEIGHT_M)
        assertTrue("below the floor", w.ys[1] < 0f)
    }

    @Test fun widthsBecomeWorldUnits() {
        val t = track()
        val f = buildPaintFrame(listOf(stroke(1L, widthDp = 32f, pts = arrayOf(T0 to 0.5f))))
        val w = buildWorldPaint(f, t, panelDp = 320f)
        // A tenth of a nominal panel is a tenth of the world height, whatever the camera later does.
        assertEquals(WORLD_HEIGHT_M / 10f, w.widths[0], 1e-3f)
    }

    @Test fun strokesOutsideTheRunAreDroppedAtBuildTime() {
        val t = track(hours = 4)
        val inside = stroke(1L, pts = arrayOf(T0 + 60 * 60_000L to 0.3f))
        val before = stroke(2L, pts = arrayOf(T0 - 30L * 86_400_000L to 0.3f))
        val after = stroke(3L, pts = arrayOf(t.endMs + 86_400_000L to 0.3f))
        // Straddling: intersection, not containment — a stroke drawn across a wider window still shows.
        val straddling = stroke(4L, pts = arrayOf(T0 - 86_400_000L to 0.1f, t.endMs + 86_400_000L to 0.9f))

        val w = buildWorldPaint(buildPaintFrame(listOf(inside, before, after, straddling)), t)
        assertEquals(2, w.strokeCount)
    }

    @Test fun paintOrderAndAttributesSurvive() {
        val t = track()
        val a = stroke(1L, tool = "chalk", pts = arrayOf(T0 to 0.2f))
        val b = stroke(2L, tool = "highlighter", pts = arrayOf(T0 + 60_000L to 0.4f))
        val w = buildWorldPaint(buildPaintFrame(listOf(b, a)), t)
        // buildPaintFrame orders by (createdAtMs, id) so later strokes cover earlier ones; the world
        // must not reshuffle that or the layering inverts.
        assertEquals(PaintFrame.TOOL_CHALK, w.tools[0])
        assertEquals(PaintFrame.TOOL_HIGHLIGHTER, w.tools[1])
        assertEquals(a.colorArgb, w.colors[0])
    }

    @Test fun boundsAreScannedNotReadOffTheEnds() {
        // A stroke dragged backwards: its last point is its leftmost. Reading the ends would cull it
        // out of a camera it is plainly inside.
        val t = track()
        val f = buildPaintFrame(
            listOf(stroke(1L, pts = arrayOf(T0 + 60 * 60_000L to 0.5f, T0 + 10 * 60_000L to 0.5f))),
        )
        val w = buildWorldPaint(f, t)
        assertEquals(10f * METRES_PER_MINUTE, w.minX[0], 1e-3f)
        assertEquals(60f * METRES_PER_MINUTE, w.maxX[0], 1e-3f)
        // Windows in MINUTES mapped through the scale, not bare world metres: the stroke spans
        // minutes 10–60, so a camera over minutes 20–30 is inside it and one over 100–120 is past it
        // at any [METRES_PER_MINUTE].
        assertTrue("a camera over the stroke sees it", w.intersects(0, 20f * METRES_PER_MINUTE, 30f * METRES_PER_MINUTE))
        assertFalse("one past it does not", w.intersects(0, 100f * METRES_PER_MINUTE, 120f * METRES_PER_MINUTE))
    }

    @Test fun compressedRowsStayConsistent() {
        val t = track()
        val strokes = (1L..5L).map { id ->
            stroke(id, pts = Array(id.toInt() + 1) { (T0 + it * 90_000L) to (it * 0.1f) })
        }
        val w = buildWorldPaint(buildPaintFrame(strokes), t)
        assertEquals(0, w.offsets[0])
        assertEquals(w.pointCount, w.offsets[w.strokeCount])
        for (s in 0 until w.strokeCount) assertTrue(w.offsets[s] < w.offsets[s + 1])
    }

    @Test fun anEmptyLayerIsEmpty() {
        assertTrue(buildWorldPaint(PaintFrame.EMPTY, track()).isEmpty)
        assertTrue(buildWorldPaint(buildPaintFrame(listOf(stroke(1L, pts = arrayOf(T0 to 0f)))), GameTrack.EMPTY).isEmpty)
    }
}
