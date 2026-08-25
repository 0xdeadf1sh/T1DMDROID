package com.t1dm.ui.graph

import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.PaintTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class PaintLayerTest {

    private val T0 = 1_700_000_000_000L
    private val MIN = 60_000L

    private fun stroke(
        id: Long,
        ts: LongArray,
        y: FloatArray,
        createdAtMs: Long = T0,
        tool: String = "fine",
        colorArgb: Int = 0xFFCC3311.toInt(),
        widthDp: Float = 3f,
    ) = PaintStroke(id, createdAtMs, tool, colorArgb, widthDp, ts, y)

    private fun sweep(id: Long, from: Long, n: Int, createdAtMs: Long = T0) = stroke(
        id = id,
        ts = LongArray(n) { from + it * MIN },
        y = FloatArray(n) { 0.2f + it * 0.05f },
        createdAtMs = createdAtMs,
    )


    @Test fun frame_ordersByAuthoringTimeThenId() {
        val f = buildPaintFrame(
            listOf(
                sweep(id = 7, from = T0, n = 3, createdAtMs = T0 + 500),
                sweep(id = 2, from = T0, n = 3, createdAtMs = T0 + 100),
                sweep(id = 3, from = T0, n = 3, createdAtMs = T0 + 100),
            ),
        )
        assertEquals(listOf(2L, 3L, 7L), f.ids.toList())
    }

    @Test fun frame_packsPointsIntoOneCompressedRowArray() {
        val f = buildPaintFrame(listOf(sweep(1, T0, 3), sweep(2, T0 + 10 * MIN, 5)))
        assertEquals(2, f.strokeCount)
        assertEquals(8, f.pointCount)
        assertEquals(listOf(0, 3, 8), f.offsets.toList())
        assertEquals(T0 + 10 * MIN, f.tsMs[f.offsets[1]])
        assertEquals(T0 + 14 * MIN, f.tsMs[f.offsets[2] - 1])
    }

    @Test fun frame_carriesColourWidthAndToolPerStroke() {
        val f = buildPaintFrame(
            listOf(stroke(1, longArrayOf(T0, T0 + MIN), floatArrayOf(0.4f, 0.6f), widthDp = 2.5f, colorArgb = 0x8811AA33.toInt())),
        )
        assertEquals(0x8811AA33.toInt(), f.colors[0])
        assertEquals(2.5f, f.widthsDp[0], 1e-6f)
        assertEquals(PaintFrame.TOOL_FINE, f.tools[0])
    }

    @Test fun frame_carriesTheFloodPenAtItsFullWidth() {
        val f = buildPaintFrame(
            listOf(stroke(1, longArrayOf(T0, T0 + MIN), floatArrayOf(0.1f, 0.9f), tool = "broad", widthDp = 96f)),
        )
        assertEquals(PaintFrame.TOOL_BROAD, f.tools[0])
        // No clamp here; the only bound downstream is the renderer's one-pixel floor.
        assertEquals(96f, f.widthsDp[0], 1e-6f)
    }

    @Test fun frame_everyDomainToolHasItsOwnRenderId() {
        // A key added to PaintTool without a case in toolIdOf collapses silently onto the fine pencil.
        val ids = PaintTool.entries.map { PaintFrame.toolIdOf(it.key) }
        assertEquals(PaintTool.entries.size, ids.toSet().size)
        assertEquals(PaintFrame.TOOL_FINE, PaintFrame.toolIdOf(PaintTool.FINE.key))
        assertEquals(PaintFrame.TOOL_BROAD, PaintFrame.toolIdOf(PaintTool.BROAD.key))
        assertEquals(PaintTool.BROAD, PaintTool.forKey("broad"))
        assertEquals(PaintTool.DEFAULT, PaintTool.forKey("airbrush-2027"))
    }

    @Test fun frame_unknownToolStillDraws() {
        val f = buildPaintFrame(listOf(stroke(1, longArrayOf(T0), floatArrayOf(0.5f), tool = "airbrush-2027")))
        assertEquals(PaintFrame.TOOL_FINE, f.tools[0])
    }

    @Test fun frame_boundsAreScannedNotTakenFromTheEnds() {
        val ts = longArrayOf(T0 + 30 * MIN, T0 + 5 * MIN, T0 + 60 * MIN, T0 + 20 * MIN)
        val f = buildPaintFrame(listOf(stroke(1, ts, floatArrayOf(0.1f, 0.9f, 0.3f, 0.7f))))
        assertEquals(T0 + 5 * MIN, f.minTsMs[0])
        assertEquals(T0 + 60 * MIN, f.maxTsMs[0])
    }

    @Test fun frame_dropsPointlessStrokesAndEmptyInputYieldsEmpty() {
        assertTrue(buildPaintFrame(emptyList()).isEmpty)
        val f = buildPaintFrame(listOf(stroke(1, LongArray(0), FloatArray(0)), sweep(2, T0, 3)))
        assertEquals(1, f.strokeCount)
        assertEquals(2L, f.ids[0])
    }

    @Test fun frame_thinsCoincidentSamplesButKeepsEndpoints() {
        val n = 200
        val f = buildPaintFrame(
            listOf(stroke(1, LongArray(n) { T0 + it }, FloatArray(n) { 0.5f })),
        )
        assertEquals(2, f.pointCount)
        assertEquals(T0, f.tsMs[0])
        assertEquals(T0 + (n - 1).toLong(), f.tsMs[1])
    }

    @Test fun frame_keepsGenuineMovement() {
        val f = buildPaintFrame(listOf(sweep(1, T0, 40)))
        assertEquals(40, f.pointCount)
    }

    @Test fun frame_capsAPathologicalStrokeKeepingItsEnds() {
        val n = 9000
        val f = buildPaintFrame(
            listOf(stroke(1, LongArray(n) { T0 + it * 1000L }, FloatArray(n) { it * 1e-3f })),
            maxPointsPerStroke = 512,
        )
        assertEquals(512, f.pointCount)
        assertEquals(T0, f.tsMs[0])
        assertEquals(T0 + (n - 1) * 1000L, f.tsMs[f.pointCount - 1])
    }


    @Test fun cull_keepsOnlyStrokesIntersectingTheWindow() {
        val f = buildPaintFrame(
            listOf(
                sweep(1, T0 - 300 * MIN, 3),  // entirely before
                sweep(2, T0 + 10 * MIN, 3),   // inside
                sweep(3, T0 + 900 * MIN, 3),  // entirely after
            ),
        )
        val from = T0.toDouble()
        val to = (T0 + 180 * MIN).toDouble()
        assertFalse(f.intersects(0, from, to))
        assertTrue(f.intersects(1, from, to))
        assertFalse(f.intersects(2, from, to))
    }

    @Test fun cull_keepsAStrokeWiderThanTheWindow() {
        val f = buildPaintFrame(listOf(stroke(1, longArrayOf(T0 - 1000 * MIN, T0 + 1000 * MIN), floatArrayOf(0.2f, 0.8f))))
        assertTrue(f.intersects(0, T0.toDouble(), (T0 + 15 * MIN).toDouble()))
    }

    @Test fun cull_isInclusiveAtBothEdges() {
        // Matches PaintStrokeDao.observeOverlapping.
        val f = buildPaintFrame(listOf(sweep(1, T0, 3)))
        assertTrue("touching the left edge", f.intersects(0, (T0 + 2 * MIN).toDouble(), (T0 + 9 * MIN).toDouble()))
        assertTrue("touching the right edge", f.intersects(0, (T0 - 9 * MIN).toDouble(), T0.toDouble()))
        assertFalse(f.intersects(0, (T0 + 2 * MIN + 1).toDouble(), (T0 + 9 * MIN).toDouble()))
    }


    private val PLOT_LEFT = 46f
    private val PLOT_RIGHT = 1034f
    private val PLOT_TOP = 24f
    private val PLOT_BOTTOM = 636f
    private val PLOT_W = (PLOT_RIGHT - PLOT_LEFT).toDouble()
    private val PLOT_H = PLOT_BOTTOM - PLOT_TOP

    @Test fun projection_mapsTheWindowOntoThePlotBox() {
        val span = 180.0 * MIN
        val ppm = PLOT_W / span
        assertEquals(PLOT_LEFT, paintXPx(T0, T0.toDouble(), ppm, PLOT_LEFT), 1e-3f)
        assertEquals(PLOT_RIGHT, paintXPx(T0 + 180 * MIN, T0.toDouble(), ppm, PLOT_LEFT), 1e-3f)
        assertEquals(PLOT_TOP, paintYPx(0f, PLOT_TOP, PLOT_H), 1e-3f)
        assertEquals(PLOT_BOTTOM, paintYPx(1f, PLOT_TOP, PLOT_H), 1e-3f)
    }

    @Test fun projection_panScrollsTheArtWithTheData() {
        val span = 180.0 * MIN
        val ppm = PLOT_W / span
        val before = paintXPx(T0 + 60 * MIN, T0.toDouble(), ppm, PLOT_LEFT)
        val after = paintXPx(T0 + 60 * MIN, (T0 - 60 * MIN).toDouble(), ppm, PLOT_LEFT)
        assertEquals((60.0 * MIN * ppm).toFloat(), after - before, 1e-2f)
    }

    @Test fun projection_zoomStretchesHorizontallyOnly() {
        val wide = PLOT_W / (180.0 * MIN)
        val tight = PLOT_W / (90.0 * MIN)
        val dWide = paintXPx(T0 + 30 * MIN, T0.toDouble(), wide, PLOT_LEFT) - paintXPx(T0, T0.toDouble(), wide, PLOT_LEFT)
        val dTight = paintXPx(T0 + 30 * MIN, T0.toDouble(), tight, PLOT_LEFT) - paintXPx(T0, T0.toDouble(), tight, PLOT_LEFT)
        assertEquals(2f, dTight / dWide, 1e-3f)
    }

    @Test fun projection_yIsAnchoredToThePlotBoxNotTheValueAxis() {
        val a = paintYPx(0.5f, PLOT_TOP, PLOT_H)
        assertEquals((PLOT_TOP + PLOT_BOTTOM) / 2f, a, 1e-3f)
        // The model-clock axis appearing moves plotTop by 14 dp; the art moves with it.
        val shifted = paintYPx(0.5f, PLOT_TOP - 14f, PLOT_H + 14f)
        assertTrue(shifted < a)
    }

    @Test fun projection_outOfBoxFractionsSurviveToBeClipped() {
        assertTrue(paintYPx(-0.2f, PLOT_TOP, PLOT_H) < PLOT_TOP)
        assertTrue(paintYPx(1.3f, PLOT_TOP, PLOT_H) > PLOT_BOTTOM)
    }


    private fun runsOf(iLo: Int, iHi: Int, breakAfter: BooleanArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        forEachTraceRun(iLo, iHi, { breakAfter[it] }) { a, b -> out.add(a to b) }
        return out
    }

    /** Skia's stroke-to-fill of the same runs at [corridorWidthPx] is exactly the points within
     *  half that width of this polyline; the Skia op itself needs a device. */
    private fun distanceToTrace(
        px: Float, py: Float, xs: FloatArray, ys: FloatArray, runs: List<Pair<Int, Int>>,
    ): Float {
        var best = Float.MAX_VALUE
        for ((a, b) in runs) {
            if (b == a) {
                best = minOf(best, hypot(px - xs[a], py - ys[a]))
                continue
            }
            for (i in a until b) best = minOf(best, pointToSegment(px, py, xs[i], ys[i], xs[i + 1], ys[i + 1]))
        }
        return best
    }

    private fun hypot(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)

    private fun pointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 <= 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * vx), py - (ay + t * vy))
    }

    // The target device's density; the 7 dp corridor is ±12.25 px there.
    private val DP_PX = 3.5f

    @Test fun corridor_excludesAPointLyingOnTheTrace() {
        val xs = floatArrayOf(100f, 200f, 300f)
        val ys = floatArrayOf(400f, 300f, 380f)
        val runs = runsOf(0, 2, BooleanArray(3))
        assertTrue(distanceToTrace(200f, 300f, xs, ys, runs) < corridorHalfWidthPx(DP_PX))
        assertTrue(distanceToTrace(150f, 350f, xs, ys, runs) < corridorHalfWidthPx(DP_PX))
    }

    @Test fun corridor_leavesPaintUntouchedAwayFromTheTrace() {
        val xs = floatArrayOf(100f, 200f, 300f)
        val ys = floatArrayOf(400f, 300f, 380f)
        val runs = runsOf(0, 2, BooleanArray(3))
        assertTrue(distanceToTrace(200f, 340f, xs, ys, runs) > corridorHalfWidthPx(DP_PX))
    }

    @Test fun corridor_breaksAtDropoutsRatherThanBridgingThem() {
        // A dropout: the graph draws no line across it, so the mask carves none.
        val xs = floatArrayOf(100f, 200f, 600f, 700f)
        val ys = floatArrayOf(300f, 300f, 300f, 300f)
        val breaks = booleanArrayOf(false, true, false, false)
        val runs = runsOf(0, 3, breaks)
        assertEquals(listOf(0 to 1, 2 to 3), runs)
        assertTrue(distanceToTrace(400f, 300f, xs, ys, runs) > corridorHalfWidthPx(DP_PX))
        assertTrue(distanceToTrace(150f, 300f, xs, ys, runs) < corridorHalfWidthPx(DP_PX))
        assertTrue(distanceToTrace(650f, 300f, xs, ys, runs) < corridorHalfWidthPx(DP_PX))
    }

    @Test fun corridor_emitsAnIsolatedReadingAsItsOwnRun() {
        // No segment, but the renderer draws a marker there, so a disc is carved around it.
        val breaks = booleanArrayOf(true, true, false)
        assertEquals(listOf(0 to 0, 1 to 1, 2 to 2), runsOf(0, 2, breaks))
        val xs = floatArrayOf(100f, 200f, 300f)
        val ys = floatArrayOf(300f, 300f, 300f)
        assertTrue(distanceToTrace(203f, 302f, xs, ys, runsOf(0, 2, breaks)) < corridorHalfWidthPx(DP_PX))
    }

    @Test fun corridor_followsOnlyTheVisibleIndexWindow() {
        val breaks = BooleanArray(100)
        val runs = runsOf(40, 45, breaks)
        assertEquals(listOf(40 to 45), runs)
    }

    @Test fun corridor_cutsAFloodPenWithoutBeingSwallowedByIt() {
        // The mask is a clip, not a property of the pen, so the band is identical at any width.
        val xs = floatArrayOf(100f, 200f, 300f)
        val ys = floatArrayOf(400f, 300f, 380f)
        val runs = runsOf(0, 2, BooleanArray(3))
        val r = corridorHalfWidthPx(DP_PX)
        val floodHalfPx = PaintTool.BROAD.defaultWidthDp * DP_PX / 2f
        assertTrue(floodHalfPx > 8f * r)
        assertTrue(distanceToTrace(200f, 300f, xs, ys, runs) < r)
        assertTrue(distanceToTrace(200f, 300f + floodHalfPx, xs, ys, runs) > r)
        assertTrue(distanceToTrace(200f, 300f - floodHalfPx, xs, ys, runs) > r)
    }

    @Test fun corridor_widthIsTheStrokerPenAndHalfIsTheExclusionRadius() {
        assertEquals(PAINT_CORRIDOR_DP * DP_PX, corridorWidthPx(DP_PX), 1e-4f)
        assertEquals(corridorWidthPx(DP_PX) / 2f, corridorHalfWidthPx(DP_PX), 1e-4f)
        // 2.6 px is the marker radius; the halo must clear it.
        assertTrue(corridorHalfWidthPx(DP_PX) > 2.6f)
        assertTrue(abs(corridorHalfWidthPx(1f) - 3.5f) < 1e-4f)
    }
}
