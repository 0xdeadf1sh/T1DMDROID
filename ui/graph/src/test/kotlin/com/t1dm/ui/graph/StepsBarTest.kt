package com.t1dm.ui.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the Steps overlay's pure half: the frame build and the bar geometry
 * [emitStepBars] emits through its sink. The Canvas draw itself is not reachable from a unit test —
 * which is what the sink exists for, exactly as [CurvePathSink] does for the curve overlay.
 */
class StepsBarTest {

    private val STEP = 300_000L
    private val T0 = 1_700_000_000_000L / STEP * STEP

    /** A recording sink: every bar, in emission order. */
    private class Bars : StepBarSink {
        data class Bar(val left: Float, val top: Float, val right: Float, val bottom: Float) {
            val width: Float get() = right - left
            val height: Float get() = bottom - top
        }

        val bars = mutableListOf<Bar>()
        override fun bar(left: Float, top: Float, right: Float, bottom: Float) {
            bars += Bar(left, top, right, bottom)
        }
    }

    /** Projection: 1 px per minute, origin at [T0] — so one 5-min bucket is 5 px wide. */
    private fun px(scale: Double = 1.0 / 60_000.0) =
        AbsToPx { ms -> ((ms - T0.toDouble()) * scale).toFloat() }

    private fun emit(
        frame: StepsFrame,
        scale: Double = 1.0 / 60_000.0,
        dpPx: Float = 1f,
        lo: Int = 0,
        hi: Int = frame.size - 1,
    ): Bars = Bars().also {
        emitStepBars(frame, px(scale), bandTop = 0f, plotBottom = 100f, lo = lo, hi = hi, dpPx = dpPx, sink = it)
    }

    // ── frame build ──────────────────────────────────────────────────────────────────────────────

    @Test fun emptyInputIsTheEmptyFrame() {
        assertTrue(buildStepsFrame(IntArray(0), T0).isEmpty)
    }

    @Test fun allZeroBucketsIsEmpty() {
        // Rows exist but nobody moved: max is 0, so there is nothing to scale against and no bar to
        // draw. It must report empty rather than divide by that zero.
        assertTrue(buildStepsFrame(IntArray(12), T0).isEmpty)
    }

    @Test fun maxIsThePeakBucket() {
        val f = buildStepsFrame(intArrayOf(0, 40, 7, 120, 3), T0)
        assertEquals(120, f.max)
        assertEquals(5, f.size)
        assertEquals(T0 + 3 * STEP, f.tsAt(3))
    }

    @Test fun clampedIndexPinsOutsideViewportsIntoTheArray() {
        val f = buildStepsFrame(intArrayOf(1, 2, 3), T0)
        assertEquals(0, f.clampedIndexAt(T0 - 10 * STEP.toDouble()))
        assertEquals(2, f.clampedIndexAt(T0 + 99 * STEP.toDouble()))
        assertEquals(1, f.clampedIndexAt((T0 + STEP).toDouble()))
    }

    // ── bar geometry ─────────────────────────────────────────────────────────────────────────────

    @Test fun onlyPositiveBucketsDrawABar() {
        // A sleeping night is mostly zeroes; they must draw nothing at all rather than a baseline smear.
        val bars = emit(buildStepsFrame(intArrayOf(0, 30, 0, 0, 90, 0), T0)).bars
        assertEquals(2, bars.size)
        assertEquals(1f * 5f, bars[0].left, 1e-3f) // bucket 1 → 5 min in → 5 px
        assertEquals(4f * 5f, bars[1].left, 1e-3f)
    }

    @Test fun heightIsProportionalToTheFramePeak() {
        val bars = emit(buildStepsFrame(intArrayOf(50, 100), T0)).bars
        // Band is 100 px with 0.92 headroom: the peak bucket reaches 92, half of it 46.
        assertEquals(92f, bars[1].height, 1e-3f)
        assertEquals(46f, bars[0].height, 1e-3f)
        assertTrue("bars stand on the plot floor", bars.all { it.bottom == 100f })
    }

    @Test fun barsNeverLeaveTheBand() {
        val bars = emit(buildStepsFrame(intArrayOf(1, 500, 250), T0)).bars
        assertTrue("no bar rises above the band top", bars.all { it.top >= 0f })
        assertTrue("no bar hangs below the floor", bars.all { it.bottom <= 100f })
    }

    @Test fun aGapSeparatesAdjacentBars() {
        // Two neighbouring busy buckets must read as two bars, not one block.
        val bars = emit(buildStepsFrame(intArrayOf(10, 10), T0)).bars
        assertEquals(2, bars.size)
        assertTrue("bar is narrower than the 5 px pitch", bars[0].width < 5f)
        assertTrue("the next bar starts clear of this one", bars[1].left > bars[0].right)
    }

    @Test fun theCullBoundsWhatIsEmitted() {
        val f = buildStepsFrame(IntArray(200) { 10 }, T0)
        assertEquals(200, emit(f).bars.size)
        assertEquals(11, emit(f, lo = 20, hi = 30).bars.size)
    }

    // ── the merge: cost bounded by plot width, not by history length ─────────────────────────────

    @Test fun subPixelBucketsMergeInsteadOfEmittingHairlines() {
        // A fortnight of 5-min buckets under a viewport so wide each is a hundredth of a pixel. The
        // unmerged draw would emit 4032 bars a frame; the merge must collapse them to something
        // bounded by the pixels available.
        val f = buildStepsFrame(IntArray(4032) { 10 }, T0)
        val squashed = 1.0 / 60_000.0 / 100.0
        val n = emit(f, scale = squashed).bars.size
        // The bound that matters is a PIXEL bound: never more bars than there are columns to put
        // them in, whatever the history holds. 4032 buckets at this scale span ~201 px.
        val plotPx = 4032 * 300_000.0 * squashed
        assertTrue("emitted $n bars over ${plotPx.toInt()} px", n <= plotPx + 1)
        assertTrue("still drew something", n > 0)
    }

    @Test fun aMergedBarCarriesItsGroupsPeak() {
        // Max, not mean: at a zoom needing the merge, averaging a walk into the idle hours around it
        // would erase the walk. Ten buckets, one of them the frame peak, squeezed under one bar.
        val steps = IntArray(10) { if (it == 4) 100 else 1 }
        val f = buildStepsFrame(steps, T0)
        val bars = emit(f, scale = 1.0 / 60_000.0 / 50.0).bars
        assertEquals("the whole group is one bar", 1, bars.size)
        assertEquals("and it stands at the group's peak", 92f, bars[0].height, 1e-3f)
    }

    @Test fun mergingWidensABarAndDoesNotMoveIt() {
        val f = buildStepsFrame(IntArray(40) { 10 }, T0)
        val fine = emit(f).bars
        val merged = emit(f, scale = 1.0 / 60_000.0 / 10.0).bars
        assertEquals("both start at the first bucket", 0f, fine[0].left, 1e-3f)
        assertEquals("both start at the first bucket", 0f, merged[0].left, 1e-3f)
        assertTrue("merging collapsed bars", merged.size < fine.size)
    }

    @Test fun mergedBarsDoNotRePhaseWhenTheViewportSlides() {
        // A pan must slide the SAME bars across the screen. If the groups were cut from `lo` — which
        // moves with the viewport — a merged band would be re-partitioned on every frame of a drag and
        // would visibly shimmer. Same frame, same scale, cull start walked one bucket at a time: every
        // bar the two views share must sit at the same data-anchored x.
        val f = buildStepsFrame(IntArray(120) { (it * 7) % 13 + 1 }, T0)
        val squashed = 1.0 / 60_000.0 / 10.0
        val base = emit(f, scale = squashed, lo = 0, hi = 119).bars.map { it.left }.toSet()
        for (start in 1..8) {
            for (left in emit(f, scale = squashed, lo = start, hi = 119).bars.map { it.left }) {
                assertTrue("a bar moved to $left when the cull started at $start", left in base)
            }
        }
    }

    @Test fun aDenseGridStillLeavesAVisibleBar() {
        // Where the pitch is narrower than the gap, the gap must not consume the bar entirely.
        val f = buildStepsFrame(IntArray(50) { 10 }, T0)
        val bars = emit(f, dpPx = 3.5f).bars
        assertTrue("every bar has positive width", bars.all { it.width > 0f })
    }
}
