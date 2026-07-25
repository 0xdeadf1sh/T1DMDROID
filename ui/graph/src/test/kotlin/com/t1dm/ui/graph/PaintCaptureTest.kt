package com.t1dm.ui.graph

import com.t1dm.core.model.PaintStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the AUTHORING half of the annotation layer: the capture buffer's
 * minimum-distance decimation, and the eraser's whole-stroke hit test. Both are pure numerics
 * deliberately kept out of the pointer handler and the Canvas so they can be pinned here (the same
 * split [BgPanelTest] and [PaintLayerTest] follow).
 */
class PaintCaptureTest {

    private val T0 = 1_700_000_000_000L

    /** The plot box these tests project into: 1000 px wide, 400 px tall, offset like the real panel. */
    private val LEFT = 46f
    private val TOP = 24f
    private val HEIGHT = 400f

    private fun capture() = StrokeCapture().apply { begin(T0) }

    /** Feed a straight horizontal run of samples [stepPx] apart, as a slow finger would. */
    private fun StrokeCapture.sweep(n: Int, stepPx: Float, minStepPx: Float, y: Float = 100f): Int {
        var accepted = 0
        for (i in 0 until n) {
            val x = LEFT + i * stepPx
            if (add(x, y, T0 + i * 8L, (y - TOP) / HEIGHT, minStepPx)) accepted++
        }
        return accepted
    }

    // ── item 5: decimation by minimum distance ──────────────────────────────────────────────────

    @Test fun `the first sample is always accepted`() {
        val c = capture()
        assertTrue(c.add(0f, 0f, T0, 0.5f, 100f))
        assertEquals(1, c.size)
    }

    @Test fun `a slow drag is decimated to the step, not to the sample rate`() {
        // 200 samples 0.5 px apart — a finger crawling at ~60 Hz. At a 2 px gate only every 4th
        // survives, and the stroke costs 50 points rather than 200.
        val c = capture()
        val accepted = c.sweep(n = 200, stepPx = 0.5f, minStepPx = 2f)
        assertEquals(accepted, c.size)
        assertEquals(50, c.size)
    }

    @Test fun `a fast drag keeps every sample`() {
        val c = capture()
        c.sweep(n = 40, stepPx = 9f, minStepPx = 2f)
        assertEquals(40, c.size)
    }

    @Test fun `a stationary finger deposits exactly one point`() {
        val c = capture()
        repeat(500) { c.add(120f, 60f, T0 + it, 0.1f, 2f) }
        assertEquals(1, c.size)
    }

    @Test fun `distance is measured from the last ACCEPTED point, so sub-pixel creep cannot accumulate`() {
        // 1.9 px each, gate 2 px: measured pairwise every sample would be rejected forever; measured
        // from the last accepted point, every second one clears the gate.
        val c = capture()
        val accepted = c.sweep(n = 21, stepPx = 1.9f, minStepPx = 2f)
        assertEquals(accepted, c.size)
        assertEquals(11, c.size) // the down sample + every second one after it
    }

    @Test fun `a diagonal move is gated on true distance, not on either axis`() {
        val c = capture()
        c.add(0f, 0f, T0, 0f, 5f)
        // 3-4-5: exactly on the gate, and neither component alone would clear it.
        assertTrue(c.add(3f, 4f, T0 + 10, 0.1f, 5f))
        assertFalse(c.add(6f, 8f, T0 + 20, 0.2f, 5.1f))
    }

    // ── the gate scales with the pen ────────────────────────────────────────────────────────────

    /** The target device's density, so the numbers below are the ones a real stroke is gated at. */
    private val DP = 3.5f

    @Test fun `the gate is the flat floor up to a 16 dp nib and an eighth of the nib beyond`() {
        // The floor governs everything narrower than 16 dp, which is the pencil, the marker and the
        // chalk unchanged; the highlighter's 18 dp is a whisker past the crossover and gains a quarter
        // of a dp, which is a fifth of a pixel at this density.
        assertEquals(2f * DP, paintMinStepPx(2f, DP), 1e-3f)     // fine
        assertEquals(2f * DP, paintMinStepPx(7f, DP), 1e-3f)     // marker
        assertEquals(2f * DP, paintMinStepPx(9f, DP), 1e-3f)     // chalk
        assertEquals(2f * DP, paintMinStepPx(16f, DP), 1e-3f)    // exactly the crossover
        assertEquals(2.25f * DP, paintMinStepPx(18f, DP), 1e-3f) // highlighter
        // The flood pen scales with itself: an eighth of the nib at its default and at the maximum.
        assertEquals(12f * DP, paintMinStepPx(96f, DP), 1e-3f)
        assertEquals(15f * DP, paintMinStepPx(120f, DP), 1e-3f)
    }

    @Test fun `the gate never makes a pen draw as a polygon, at any width`() {
        // Faceting shows when the chord departs visibly from the curve the finger traced. Its sagitta
        // on a turn as tight as the pen itself is step² / 8·width — under half a dp everywhere on this
        // range, which is inside the ink and under the round join, so decimation is felt and never seen.
        for (w in floatArrayOf(2f, 7f, 9f, 18f, 96f, 120f)) {
            val stepDp = paintMinStepPx(w, 1f)
            assertTrue("$w dp pen: step $stepDp dp", stepDp * stepDp / (8f * w) < 0.5f)
        }
    }

    @Test fun `a flood pen costs a small fraction of the points the flat gate would have kept`() {
        // The same finger path — 1000 px at pointer rate — under the fine gate and under the flood
        // pen's. Every point saved is one fewer vertex re-tessellated on EVERY draw pass of the live
        // stroke and of every later pan, which is the whole reason the gate moved inside the gesture.
        val fine = capture()
        fine.sweep(n = 1000, stepPx = 1f, minStepPx = paintMinStepPx(2f, DP))
        val flood = capture()
        flood.sweep(n = 1000, stepPx = 1f, minStepPx = paintMinStepPx(96f, DP))
        assertTrue("${flood.size} vs ${fine.size}", flood.size * 5 < fine.size)
    }

    // ── the lift-off point ──────────────────────────────────────────────────────────────────────

    @Test fun `the final sample is accepted even inside the gate`() {
        val c = capture()
        c.add(0f, 0f, T0, 0f, 4f)
        assertFalse(c.add(1f, 0f, T0 + 5, 0.01f, 4f)) // inside the gate
        assertTrue(c.addFinal(1f, 0f, T0 + 5, 0.01f)) // …but it is where the finger lifted
        assertEquals(2, c.size)
    }

    @Test fun `an exact repeat of the last point is still refused on lift-off`() {
        val c = capture()
        c.add(7f, 7f, T0, 0.3f, 4f)
        assertFalse(c.addFinal(7f, 7f, T0 + 5, 0.3f))
        assertEquals(1, c.size)
    }

    // ── what reaches the store ──────────────────────────────────────────────────────────────────

    @Test fun `a tap yields a one-point stroke, which the renderer draws as a dot`() {
        val c = capture()
        c.add(300f, 200f, T0, 0.44f, 2f)
        val s = c.toStroke("fine", 0xFFFF0000.toInt(), 2f)
        assertNotNull(s)
        assertEquals(1, s!!.size)
        assertEquals(0L, s.id) // the store mints the row id
        assertEquals(T0, s.createdAtMs)
    }

    @Test fun `an untouched capture yields no stroke at all`() {
        assertNull(capture().toStroke("fine", -1, 2f))
    }

    @Test fun `abandoning empties the capture without emitting`() {
        val c = capture()
        c.sweep(n = 30, stepPx = 8f, minStepPx = 2f)
        assertTrue(c.size > 1)
        c.abandon()
        assertEquals(0, c.size)
        assertNull(c.toStroke("fine", -1, 2f))
    }

    @Test fun `the buffer grows past its initial capacity`() {
        val c = capture()
        c.sweep(n = 900, stepPx = 5f, minStepPx = 2f)
        assertEquals(900, c.size)
        assertEquals(900, c.toStroke("fine", -1, 2f)!!.size)
    }

    @Test fun `a pathological stroke stops growing rather than being resampled`() {
        val c = capture()
        repeat(PAINT_MAX_POINTS + 500) { c.add(it * 5f, 0f, T0 + it, 0.5f, 1f) }
        assertEquals(PAINT_MAX_POINTS, c.size)
    }

    @Test fun `captured coordinates are the ones the store keeps`() {
        val c = capture()
        val yFrac = (TOP + 0.25f * HEIGHT - TOP) / HEIGHT
        c.add(LEFT, TOP + 0.25f * HEIGHT, T0 + 60_000L, yFrac, 2f)
        val s = c.toStroke("marker", 0x80FFFFFF.toInt(), 7f)!!
        assertEquals(T0 + 60_000L, s.tsMs[0])
        assertEquals(0.25f, s.yFrac[0], 1e-6f)
        assertEquals("marker", s.tool)
        assertEquals(7f, s.widthDp, 0f)
    }

    // ── the eraser's hit test ───────────────────────────────────────────────────────────────────

    private fun frameOf(vararg strokes: PaintStroke) = buildPaintFrame(strokes.toList())

    /** A horizontal stroke at [yFrac] running from [fromMs] for [n] minutes. */
    private fun bar(id: Long, fromMs: Long, n: Int, yFrac: Float, createdAtMs: Long, widthDp: Float = 4f) =
        PaintStroke(
            id = id, createdAtMs = createdAtMs, tool = "fine", colorArgb = -1, widthDp = widthDp,
            tsMs = LongArray(n) { fromMs + it * 60_000L },
            yFrac = FloatArray(n) { yFrac },
        )

    /** 1000 px of plot spanning one hour ⇒ px per ms. */
    private val PPM = 1000.0 / 3_600_000.0

    private fun hit(f: PaintFrame, xPx: Float, yPx: Float, radiusPx: Float = 10f) =
        hitTestPaint(f, xPx, yPx, radiusPx, T0.toDouble(), PPM, LEFT, TOP, HEIGHT, dpPx = 1f)

    @Test fun `a touch on a stroke erases it`() {
        val f = frameOf(bar(id = 5, fromMs = T0, n = 30, yFrac = 0.5f, createdAtMs = T0))
        // 10 minutes in, on the line: x = LEFT + 10min * PPM, y = TOP + 0.5 * HEIGHT.
        assertEquals(5L, hit(f, LEFT + (600_000.0 * PPM).toFloat(), TOP + 0.5f * HEIGHT))
    }

    @Test fun `a touch nowhere near a stroke erases nothing`() {
        val f = frameOf(bar(id = 5, fromMs = T0, n = 30, yFrac = 0.5f, createdAtMs = T0))
        assertEquals(NO_STROKE, hit(f, LEFT + (600_000.0 * PPM).toFloat(), TOP + 0.9f * HEIGHT))
    }

    @Test fun `the test is against SEGMENTS, so a two-point stroke is erasable in the middle`() {
        // Two points an hour apart: a nearest-POINT test would refuse to erase it anywhere between.
        val f = frameOf(
            PaintStroke(9L, T0, "fine", -1, 4f, longArrayOf(T0, T0 + 3_600_000L), floatArrayOf(0.2f, 0.2f)),
        )
        assertEquals(9L, hit(f, LEFT + 500f, TOP + 0.2f * HEIGHT))
    }

    @Test fun `the TOPMOST stroke wins where two overlap`() {
        val f = frameOf(
            bar(id = 1, fromMs = T0, n = 30, yFrac = 0.5f, createdAtMs = T0),
            bar(id = 2, fromMs = T0, n = 30, yFrac = 0.5f, createdAtMs = T0 + 1000), // painted later
        )
        assertEquals(2L, hit(f, LEFT + (600_000.0 * PPM).toFloat(), TOP + 0.5f * HEIGHT))
    }

    @Test fun `a broad stroke is erasable across its full width`() {
        val thin = frameOf(bar(id = 1, fromMs = T0, n = 30, yFrac = 0.5f, createdAtMs = T0, widthDp = 2f))
        val broad = frameOf(bar(id = 1, fromMs = T0, n = 30, yFrac = 0.5f, createdAtMs = T0, widthDp = 40f))
        val x = LEFT + (600_000.0 * PPM).toFloat()
        val y = TOP + 0.5f * HEIGHT + 18f // 18 px off the centre line
        assertEquals(NO_STROKE, hit(thin, x, y, radiusPx = 4f))
        assertEquals(1L, hit(broad, x, y, radiusPx = 4f))
    }

    @Test fun `the widest pen is erasable across its full half-width and no further`() {
        // dpPx is 1 here, so a 120 dp pen is 60 px of half-width and the tolerance is 60 + the reach.
        val flood = frameOf(bar(id = 1, fromMs = T0, n = 30, yFrac = 0.5f, createdAtMs = T0, widthDp = 120f))
        val x = LEFT + (600_000.0 * PPM).toFloat()
        val centre = TOP + 0.5f * HEIGHT
        assertEquals(1L, hit(flood, x, centre + 55f, radiusPx = 4f)) // still on the ink
        assertEquals(NO_STROKE, hit(flood, x, centre + 70f, radiusPx = 4f)) // past it, and past the reach
    }

    @Test fun `a round cap's over-reach past the ends is the ink it genuinely paints there`() {
        // The segment-distance test measures a half-disc of the pen's radius past the last point. For a
        // ROUND cap that disc is drawn, so the tolerance tracks the visible extent exactly and the
        // eraser's slop stays the constant fingertip at any width. A flat-nibbed pen at 120 dp would
        // paint nothing there and still measure as if it did — 60 dp of phantom hit region.
        val flood = frameOf(bar(id = 1, fromMs = T0, n = 2, yFrac = 0.5f, createdAtMs = T0, widthDp = 120f))
        val endX = LEFT + (60_000.0 * PPM).toFloat()
        val centre = TOP + 0.5f * HEIGHT
        assertEquals(1L, hit(flood, endX + 55f, centre, radiusPx = 4f))
        assertEquals(NO_STROKE, hit(flood, endX + 70f, centre, radiusPx = 4f))
    }

    @Test fun `an empty layer is never hit`() {
        assertEquals(NO_STROKE, hit(PaintFrame.EMPTY, 100f, 100f))
    }

    @Test fun `a one-point stroke is erasable at its dot`() {
        val f = frameOf(PaintStroke(3L, T0, "fine", -1, 4f, longArrayOf(T0 + 600_000L), floatArrayOf(0.3f)))
        assertEquals(3L, hit(f, LEFT + (600_000.0 * PPM).toFloat(), TOP + 0.3f * HEIGHT))
        assertEquals(NO_STROKE, hit(f, LEFT + (600_000.0 * PPM).toFloat() + 60f, TOP + 0.3f * HEIGHT))
    }

    // ── the geometry behind both ────────────────────────────────────────────────────────────────

    @Test fun `point to segment distance clamps to the endpoints`() {
        // Beyond B along the line: the nearest point is B itself, not the infinite line's foot.
        assertEquals(25f, pointSegmentDistanceSq(15f, 0f, 0f, 0f, 10f, 0f), 1e-4f)
        assertEquals(25f, pointSegmentDistanceSq(-5f, 0f, 0f, 0f, 10f, 0f), 1e-4f)
        // Perpendicular, inside the span.
        assertEquals(9f, pointSegmentDistanceSq(5f, 3f, 0f, 0f, 10f, 0f), 1e-4f)
    }

    @Test fun `a degenerate segment behaves as a point`() {
        assertEquals(25f, pointSegmentDistanceSq(3f, 4f, 0f, 0f, 0f, 0f), 1e-4f)
    }
}
