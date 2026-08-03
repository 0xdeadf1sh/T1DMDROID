package com.t1dm.ui.graph

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hindsight sweep's data structure.
 *
 * [HindsightFrame] trades a list of per-cycle series for six flat arrays because the cursor→fan
 * lookup runs at pointer rate, and that trade puts the correctness of the whole feature into index
 * arithmetic that nothing else checks. So these assert the two things the flattening can silently get
 * wrong — that a cycle's values come back out where they went in, and that the block stays
 * rectangular when the rows are not — plus the boundedness of the lookup, which is what stops a
 * sweep pinning the nearest stored fan under a cursor hours away from it.
 *
 * Geometry of the fixture: cycles every 5 min from [T0]; cycle `c` forecasts a flat line at
 * `200 + c` with a ±{5,10,15} fan, so a value identifies its cycle unambiguously and a mis-strided
 * read lands on a different number rather than a plausible one.
 */
class HindsightFrameTest {

    private val STEP = 300_000L
    private val T0 = 1_700_000_000_000L
    private val NQ = 7
    private val H = 24

    private fun pred(
        c: Int,
        steps: Int = H,
        nq: Int = NQ,
        status: ForecastStatus = ForecastStatus.OK,
        stepMs: Long = STEP,
    ): ModelPrediction {
        val level = 200.0 + c
        val median = List(steps) { level }
        val bands = ArrayList<Double>(steps * nq)
        // Ascending τ, widest pair outermost — the order §6 fixes and `buildPredSeries` reads.
        repeat(steps) {
            for (k in 0 until nq) bands += level + (k - nq / 2) * 5.0
        }
        return ModelPrediction(
            modelId = "m", cycleTsMs = T0 + c * STEP, anchorTsMs = T0 + c * STEP, stepMs = stepMs,
            medianBg = median, bandsMgdl = bands, nQuantiles = nq, lastBg = level,
            status = status, backend = BackendId.EXECUTORCH_XNNPACK_FP32, precision = Precision.FP32,
            selected = true, stale = false, latencyMs = null,
        )
    }

    private fun frameOf(rows: List<ModelPrediction>) =
        runBlocking { hindsightFrameOf(rows, UnitSpace.MgDl, null) }

    @Test fun eachCyclesValuesSurviveTheFlattening() {
        val f = frameOf((0 until 5).map { pred(it) })!!
        assertEquals(5, f.cycles)
        assertEquals(H + 1, f.span) // the prepended anchor point
        for (c in 0 until 5) {
            assertEquals(T0 + c * STEP, f.anchorMs[c])
            // Step 0 is the anchor (the measured BG the forecast grew from); 1..H the forecast.
            assertEquals(200f + c, f.median[c * f.span], 1e-3f)
            for (i in 1..H) assertEquals(200f + c, f.median[c * f.span + i], 1e-3f)
        }
    }

    @Test fun bandsAreOuterToInnerAndDoNotStrideIntoTheNextCycle() {
        val f = frameOf((0 until 3).map { pred(it) })!!
        for (c in 0 until 3) {
            val level = 200f + c
            // Band 0 is the OUTER pair (.05/.95 ⇒ ±15), 2 the inner (.25/.75 ⇒ ±5). A stride error
            // across cycles would read a neighbour's level and miss by 1, not by a band width.
            for ((b, half) in listOf(0 to 15f, 1 to 10f, 2 to 5f)) {
                val base = (b * f.cycles + c) * f.span
                for (i in 1..H) {
                    assertEquals("lo b=$b c=$c i=$i", level - half, f.lo[base + i], 1e-3f)
                    assertEquals("hi b=$b c=$c i=$i", level + half, f.hi[base + i], 1e-3f)
                }
                // The anchor's fan is zero-width — the forecast fans open OUT of the last reading.
                assertEquals(level, f.lo[base], 1e-3f)
                assertEquals(level, f.hi[base], 1e-3f)
            }
        }
    }

    @Test fun raggedRowsAreDroppedRatherThanReshaped() {
        // A shorter horizon, a different step, and a narrower fan: each is a forecast of something
        // other than what the first row fixed, and none may be stretched to fit beside it.
        val f = frameOf(
            listOf(
                pred(0),
                pred(1, steps = 12),
                pred(2, stepMs = 600_000L),
                pred(3, nq = 5),
                pred(4),
            ),
        )!!
        assertEquals(2, f.cycles)
        assertEquals(T0 + 0 * STEP, f.anchorMs[0])
        assertEquals(T0 + 4 * STEP, f.anchorMs[1])
        // The kept rows' own values, not a neighbour's shifted in by the trim.
        assertEquals(200f, f.median[0 * f.span], 1e-3f)
        assertEquals(204f, f.median[1 * f.span], 1e-3f)
        val outer = (0 * f.cycles + 1) * f.span
        assertEquals(204f - 15f, f.lo[outer + 1], 1e-3f)
        assertEquals(204f + 15f, f.hi[outer + 1], 1e-3f)
    }

    @Test fun aDegenerateCycleIsCarriedAndFlagged() {
        val f = frameOf(listOf(pred(0), pred(1, status = ForecastStatus.COLLAPSED_BAND)))!!
        assertEquals(2, f.cycles)
        assertTrue(!f.degenerate[0])
        assertTrue(f.degenerate[1])
    }

    @Test fun theCursorLookupIsBoundedToHalfACycle() {
        val f = frameOf((0 until 4).map { pred(it) })!!
        // Dead on, and within half a cycle either side.
        assertEquals(2, f.cycleAt((T0 + 2 * STEP).toDouble()))
        assertEquals(2, f.cycleAt((T0 + 2 * STEP).toDouble() + STEP * 0.4))
        assertEquals(2, f.cycleAt((T0 + 2 * STEP).toDouble() - STEP * 0.4))
        // Past the ends: nothing. This is the bound the sweep depends on — unbounded, a cursor a day
        // clear of the newest stored cycle would still drag that cycle's fan around under the finger.
        assertEquals(-1, f.cycleAt(T0 - STEP.toDouble()))
        assertEquals(-1, f.cycleAt((T0 + 4 * STEP).toDouble() + STEP))
    }

    @Test fun aGapInTheCyclesReadsAsAGap() {
        // Inference did not run for an hour. The cursor in the middle of that hole must find nothing
        // rather than the fan on either side of it.
        val f = frameOf(listOf(pred(0), pred(1), pred(14), pred(15)))!!
        assertEquals(4, f.cycles)
        assertEquals(-1, f.cycleAt((T0 + 7 * STEP).toDouble()))
        assertEquals(1, f.cycleAt((T0 + 1 * STEP).toDouble()))
        assertEquals(2, f.cycleAt((T0 + 14 * STEP).toDouble()))
    }

    @Test fun nothingUsableYieldsNoFrameAtAll() {
        assertNull(frameOf(emptyList()))
        assertNull(frameOf(listOf(pred(0, steps = 0))))
        assertNull(frameOf(listOf(pred(0, nq = 3))))
    }

    @Test fun theCatchmentFollowsTheOBSERVEDCadenceNotTheGridStep() {
        // Timed mode at 15 min: the cursor must reach a cycle from anywhere between two of them, or
        // the sweep blinks out for two thirds of every interval.
        val f = frameOf((0 until 4).map { pred(it * 3) })!!
        assertEquals(3 * STEP, f.cadenceMs)
        assertEquals(1, f.cycleAt((T0 + 3 * STEP).toDouble() + STEP * 1.4))
        assertEquals(2, f.cycleAt((T0 + 6 * STEP).toDouble() - STEP * 1.4))
    }
}
