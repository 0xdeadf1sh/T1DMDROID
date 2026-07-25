package com.t1dm.ui.graph

import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.PaintTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Host JVM tests for the BG panel's freehand annotation layer: the [PaintFrame] render model, the
 * viewport cull, the `(tsMs, yFrac) → px` projection, and the geometry of the corridor that keeps the
 * glucose trace legible under it. The Canvas draw itself is not unit-tested (nor is Skia's
 * stroke-to-fill, which needs a device); these cover the numerics behind it, as in [BgPanelTest].
 */
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

    /** A short left-to-right stroke starting at [from], one point per minute. */
    private fun sweep(id: Long, from: Long, n: Int, createdAtMs: Long = T0) = stroke(
        id = id,
        ts = LongArray(n) { from + it * MIN },
        y = FloatArray(n) { 0.2f + it * 0.05f },
        createdAtMs = createdAtMs,
    )

    // ── the render model: paint order, compressed-row layout, scanned bounds ─────────────────────

    @Test fun frame_ordersByAuthoringTimeThenId() {
        // Given out of order; later strokes must end up LAST so they paint OVER earlier ones.
        val f = buildPaintFrame(
            listOf(
                sweep(id = 7, from = T0, n = 3, createdAtMs = T0 + 500),
                sweep(id = 2, from = T0, n = 3, createdAtMs = T0 + 100),
                sweep(id = 3, from = T0, n = 3, createdAtMs = T0 + 100), // same ms ⇒ id breaks the tie
            ),
        )
        assertEquals(listOf(2L, 3L, 7L), f.ids.toList())
    }

    @Test fun frame_packsPointsIntoOneCompressedRowArray() {
        val f = buildPaintFrame(listOf(sweep(1, T0, 3), sweep(2, T0 + 10 * MIN, 5)))
        assertEquals(2, f.strokeCount)
        assertEquals(8, f.pointCount)
        assertEquals(listOf(0, 3, 8), f.offsets.toList())
        // Stroke 1's points occupy [3, 8) of the flat arrays — no per-stroke array to chase.
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
        // The frame neither clamps nor reinterprets a width: the only bound anywhere downstream is the
        // renderer's one-pixel FLOOR, which is why the palette's maximum can be raised on its own.
        assertEquals(96f, f.widthsDp[0], 1e-6f)
    }

    @Test fun frame_everyDomainToolHasItsOwnRenderId() {
        // The open text vocabulary and these ids are two halves of one seam. A key added to [PaintTool]
        // without a case in [PaintFrame.toolIdOf] would not throw or vanish — it would silently collapse
        // onto the fine pencil and misreport itself in `tools`, which is exactly the drift this catches.
        val ids = PaintTool.entries.map { PaintFrame.toolIdOf(it.key) }
        assertEquals(PaintTool.entries.size, ids.toSet().size)
        assertEquals(PaintFrame.TOOL_FINE, PaintFrame.toolIdOf(PaintTool.FINE.key))
        assertEquals(PaintFrame.TOOL_BROAD, PaintFrame.toolIdOf(PaintTool.BROAD.key))
        // …and the domain's own decode is total over the same vocabulary, in both directions.
        assertEquals(PaintTool.BROAD, PaintTool.forKey("broad"))
        assertEquals(PaintTool.DEFAULT, PaintTool.forKey("airbrush-2027"))
    }

    @Test fun frame_unknownToolStillDraws() {
        // `tool` is an OPEN text vocabulary in the domain precisely so a stroke authored by a later
        // build decodes here; it must resolve to something drawable, never throw and never vanish.
        val f = buildPaintFrame(listOf(stroke(1, longArrayOf(T0), floatArrayOf(0.5f), tool = "airbrush-2027")))
        assertEquals(PaintFrame.TOOL_FINE, f.tools[0])
    }

    @Test fun frame_boundsAreScannedNotTakenFromTheEnds() {
        // A finger dragged LEFT then back right: neither endpoint is an extreme, so reading
        // tsMs.first()/last() would cull the stroke off-screen from windows it genuinely covers.
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
        // A finger held still deposits many samples on the same spot; only the endpoints survive.
        val n = 200
        val f = buildPaintFrame(
            listOf(stroke(1, LongArray(n) { T0 + it }, FloatArray(n) { 0.5f })),
        )
        assertEquals(2, f.pointCount)
        assertEquals(T0, f.tsMs[0])
        assertEquals(T0 + (n - 1).toLong(), f.tsMs[1])
    }

    @Test fun frame_keepsGenuineMovement() {
        // Real motion (a minute and 5% of the plot per sample) is never thinned away.
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

    // ── viewport culling (drawn per frame; must be O(1) per stroke and INTERSECTION, not containment) ──

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
        // Drawn on the 90-day view, then zoomed to 15 minutes: it spans the window without either end
        // being in it. Containment would drop it; intersection keeps it.
        val f = buildPaintFrame(listOf(stroke(1, longArrayOf(T0 - 1000 * MIN, T0 + 1000 * MIN), floatArrayOf(0.2f, 0.8f))))
        assertTrue(f.intersects(0, T0.toDouble(), (T0 + 15 * MIN).toDouble()))
    }

    @Test fun cull_isInclusiveAtBothEdges() {
        // Matches PaintStrokeDao.observeOverlapping: a stroke touching the window by a single instant
        // counts, so a stroke does not blink out one pixel before it leaves the plot.
        val f = buildPaintFrame(listOf(sweep(1, T0, 3)))
        assertTrue("touching the left edge", f.intersects(0, (T0 + 2 * MIN).toDouble(), (T0 + 9 * MIN).toDouble()))
        assertTrue("touching the right edge", f.intersects(0, (T0 - 9 * MIN).toDouble(), T0.toDouble()))
        assertFalse(f.intersects(0, (T0 + 2 * MIN + 1).toDouble(), (T0 + 9 * MIN).toDouble()))
    }

    // ── projection: the art scrolls and stretches with the data, and the Y auto-fit never moves it ──

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
        // Pan one hour left: the same instant must move right by exactly one hour's worth of pixels.
        val span = 180.0 * MIN
        val ppm = PLOT_W / span
        val before = paintXPx(T0 + 60 * MIN, T0.toDouble(), ppm, PLOT_LEFT)
        val after = paintXPx(T0 + 60 * MIN, (T0 - 60 * MIN).toDouble(), ppm, PLOT_LEFT)
        assertEquals((60.0 * MIN * ppm).toFloat(), after - before, 1e-2f)
    }

    @Test fun projection_zoomStretchesHorizontallyOnly() {
        // Halving the span doubles the pixel distance between two instants; y is untouched.
        val wide = PLOT_W / (180.0 * MIN)
        val tight = PLOT_W / (90.0 * MIN)
        val dWide = paintXPx(T0 + 30 * MIN, T0.toDouble(), wide, PLOT_LEFT) - paintXPx(T0, T0.toDouble(), wide, PLOT_LEFT)
        val dTight = paintXPx(T0 + 30 * MIN, T0.toDouble(), tight, PLOT_LEFT) - paintXPx(T0, T0.toDouble(), tight, PLOT_LEFT)
        assertEquals(2f, dTight / dWide, 1e-3f)
        // There is no vertical counterpart to express: [paintYPx] takes no viewport argument at all.
    }

    @Test fun projection_yIsAnchoredToThePlotBoxNotTheValueAxis() {
        // The value axis rescales on every window change (auto-fit, unit switch, fixed-range growth);
        // the annotation must not budge. paintYPx cannot even see yMin/yMax — this pins that seam.
        val a = paintYPx(0.5f, PLOT_TOP, PLOT_H)
        assertEquals((PLOT_TOP + PLOT_BOTTOM) / 2f, a, 1e-3f)
        // …but it DOES follow the plot box: the model-clock axis appearing moves plotTop by 14 dp and
        // the art moves with it rather than sliding off the box it was drawn in.
        val shifted = paintYPx(0.5f, PLOT_TOP - 14f, PLOT_H + 14f)
        assertTrue(shifted < a)
    }

    @Test fun projection_outOfBoxFractionsSurviveToBeClipped() {
        // A finger that strayed past the plot is clipped at draw time, never flattened onto the edge.
        assertTrue(paintYPx(-0.2f, PLOT_TOP, PLOT_H) < PLOT_TOP)
        assertTrue(paintYPx(1.3f, PLOT_TOP, PLOT_H) > PLOT_BOTTOM)
    }

    // ── the corridor: a several-dp halo the paint cannot enter ───────────────────────────────────

    /** The runs [PaintCorridor] strokes into its mask, as (index-pair) segments. */
    private fun runsOf(iLo: Int, iHi: Int, breakAfter: BooleanArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        forEachTraceRun(iLo, iHi, { breakAfter[it] }) { a, b -> out.add(a to b) }
        return out
    }

    /** Distance from (px, py) to the polyline the corridor is stroked from. Skia's stroke-to-fill of
     *  the same runs at [corridorWidthPx] is exactly the set of points within half that width of this
     *  polyline, so this is the geometry the mask excludes — the Skia op itself needs a device. */
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

    // A 3.5x-density phone (the target device): the corridor is 7 dp ⇒ ±12.25 px around the line.
    private val DP_PX = 3.5f

    @Test fun corridor_excludesAPointLyingOnTheTrace() {
        val xs = floatArrayOf(100f, 200f, 300f)
        val ys = floatArrayOf(400f, 300f, 380f)
        val runs = runsOf(0, 2, BooleanArray(3))
        // A paint sample dropped exactly on the middle vertex, and one on a segment's interior.
        assertTrue(distanceToTrace(200f, 300f, xs, ys, runs) < corridorHalfWidthPx(DP_PX))
        assertTrue(distanceToTrace(150f, 350f, xs, ys, runs) < corridorHalfWidthPx(DP_PX))
    }

    @Test fun corridor_leavesPaintUntouchedAwayFromTheTrace() {
        val xs = floatArrayOf(100f, 200f, 300f)
        val ys = floatArrayOf(400f, 300f, 380f)
        val runs = runsOf(0, 2, BooleanArray(3))
        // 40 px below the trace — well outside a 12.25 px radius, so the stroke survives whole.
        assertTrue(distanceToTrace(200f, 340f, xs, ys, runs) > corridorHalfWidthPx(DP_PX))
    }

    @Test fun corridor_breaksAtDropoutsRatherThanBridgingThem() {
        // breakAfter[1] is a genuine sensor dropout: the graph draws NO line across it, so the mask
        // must carve none either — otherwise the art shows a halo around a line that does not exist.
        val xs = floatArrayOf(100f, 200f, 600f, 700f)
        val ys = floatArrayOf(300f, 300f, 300f, 300f)
        val breaks = booleanArrayOf(false, true, false, false)
        val runs = runsOf(0, 3, breaks)
        assertEquals(listOf(0 to 1, 2 to 3), runs)
        // Mid-gap, exactly where a bridging line would have run.
        assertTrue(distanceToTrace(400f, 300f, xs, ys, runs) > corridorHalfWidthPx(DP_PX))
        // …while the drawn halves are still protected.
        assertTrue(distanceToTrace(150f, 300f, xs, ys, runs) < corridorHalfWidthPx(DP_PX))
        assertTrue(distanceToTrace(650f, 300f, xs, ys, runs) < corridorHalfWidthPx(DP_PX))
    }

    @Test fun corridor_emitsAnIsolatedReadingAsItsOwnRun() {
        // Broken on both sides: no segment, but the renderer still draws a marker there, so the
        // corridor must carve a disc around it.
        val breaks = booleanArrayOf(true, true, false)
        assertEquals(listOf(0 to 0, 1 to 1, 2 to 2), runsOf(0, 2, breaks))
        val xs = floatArrayOf(100f, 200f, 300f)
        val ys = floatArrayOf(300f, 300f, 300f)
        assertTrue(distanceToTrace(203f, 302f, xs, ys, runsOf(0, 2, breaks)) < corridorHalfWidthPx(DP_PX))
    }

    @Test fun corridor_followsOnlyTheVisibleIndexWindow() {
        // The mask is built from [iLo, iHi] — the same window the polyline is drawn from — so a long
        // history costs nothing off-screen.
        val breaks = BooleanArray(100)
        val runs = runsOf(40, 45, breaks)
        assertEquals(listOf(40 to 45), runs)
    }

    @Test fun corridor_cutsAFloodPenWithoutBeingSwallowedByIt() {
        // The mask is a CLIP, not a property of the pen that laid the pixels down, so the band it
        // subtracts is identical under a hairline and under the flood pen — the trace stays exactly as
        // legible however much opaque ink is dragged across it. What the width does change is the
        // reading: the halo has to remain a slit THROUGH the flood rather than a cut that severs it,
        // which it does because the widest pen is an order of magnitude broader than the corridor.
        val xs = floatArrayOf(100f, 200f, 300f)
        val ys = floatArrayOf(400f, 300f, 380f)
        val runs = runsOf(0, 2, BooleanArray(3))
        val r = corridorHalfWidthPx(DP_PX)
        val floodHalfPx = PaintTool.BROAD.defaultWidthDp * DP_PX / 2f
        assertTrue(floodHalfPx > 8f * r)
        // On the trace: excluded, as it is for every other tool.
        assertTrue(distanceToTrace(200f, 300f, xs, ys, runs) < r)
        // A pen-width away on either side: well outside the band, so the flood closes over the halo
        // rather than ending at it, and the slit reads as a halo the trace sits in.
        assertTrue(distanceToTrace(200f, 300f + floodHalfPx, xs, ys, runs) > r)
        assertTrue(distanceToTrace(200f, 300f - floodHalfPx, xs, ys, runs) > r)
    }

    @Test fun corridor_widthIsTheStrokerPenAndHalfIsTheExclusionRadius() {
        assertEquals(PAINT_CORRIDOR_DP * DP_PX, corridorWidthPx(DP_PX), 1e-4f)
        assertEquals(corridorWidthPx(DP_PX) / 2f, corridorHalfWidthPx(DP_PX), 1e-4f)
        // Comfortably wider than the 2.2 px trace and its 2.6 px markers, so the halo reads as one.
        assertTrue(corridorHalfWidthPx(DP_PX) > 2.6f)
        assertTrue(abs(corridorHalfWidthPx(1f) - 3.5f) < 1e-4f)
    }
}
