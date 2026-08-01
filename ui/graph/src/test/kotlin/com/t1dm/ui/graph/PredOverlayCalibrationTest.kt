package com.t1dm.ui.graph

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.RolledForecast
import com.t1dm.core.model.UnitSpace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The display seam of the on-device band recalibration (`SPEC/inference.md` §8.4).
 *
 * The arithmetic — the side-aware order statistic, the median-fixed monotone apply, the refusal
 * below the minimum calibration count — lives in `t1dm-core::conformal` and is tested there against
 * the definition. What is testable HERE, without the native library, is the property the overlay
 * itself owes: a calibrated fan reaches the FAN and never the LINE, and a correction whose shape
 * disagrees with the forecast is ignored rather than reshaped.
 */
class PredOverlayCalibrationTest {

    private val nq = 7
    private val steps = 3

    /** A three-step forecast whose fan is the median ± {5, 10, 15}. */
    private fun prediction(): ModelPrediction {
        val median = List(steps) { 100.0 + it * 10.0 }
        val bands = ArrayList<Double>(steps * nq)
        for (m in median) {
            bands += listOf(m - 15, m - 10, m - 5, m, m + 5, m + 10, m + 15)
        }
        return ModelPrediction(
            modelId = "m",
            cycleTsMs = 300_000L,
            anchorTsMs = 300_000L,
            stepMs = 300_000L,
            medianBg = median,
            bandsMgdl = bands,
            nQuantiles = nq,
            lastBg = 95.0,
            status = ForecastStatus.OK,
            backend = BackendId.EXECUTORCH_XNNPACK_FP32,
            precision = Precision.FP32,
            selected = true,
            stale = false,
            latencyMs = null,
        )
    }

    /** The same fan with every non-median edge pushed 20 mg/dL outward — a wide, obvious change. */
    private fun widened(p: ModelPrediction): List<Double> =
        p.bandsMgdl.mapIndexed { i, v ->
            when (val k = i % nq) {
                3 -> v
                else -> if (k < 3) v - 20.0 else v + 20.0
            }
        }

    @Test
    fun aCalibratedFanReachesTheFanAndNotTheLine() {
        val p = prediction()
        val raw = buildPredSeries(p, UnitSpace.MgDl, null)!!
        val cal = buildPredSeries(p, UnitSpace.MgDl, null, widened(p))!!

        // The median line is the model's own either way — §8.4 holds it fixed, so there is no
        // calibrated median for this overlay to draw, and the dose calculator scores off the same
        // number the user sees.
        assertArrayEquals(raw.median, cal.median, 0f)

        // The outer band (index 0 of lo/hi) moved by exactly the correction, at every step past the
        // zero-width anchor.
        for (i in 1..steps) {
            assertEquals(raw.lo[0][i] - 20f, cal.lo[0][i], 1e-4f)
            assertEquals(raw.hi[0][i] + 20f, cal.hi[0][i], 1e-4f)
        }
        assertNotEquals(raw.lo[0][1], cal.lo[0][1])
    }

    @Test
    fun theAnchorStaysZeroWidthUnderACorrection() {
        // Element 0 is the last MEASURED BG, not a forecast step: it has no band to recalibrate, and
        // a correction that opened one there would show the fan starting off the trace it continues.
        val p = prediction()
        val cal = buildPredSeries(p, UnitSpace.MgDl, null, widened(p))!!
        val anchor = cal.median[0]
        for (b in 0 until 3) {
            assertEquals(anchor, cal.lo[b][0], 0f)
            assertEquals(anchor, cal.hi[b][0], 0f)
        }
    }

    @Test
    fun aCorrectionOfTheWrongShapeIsIgnoredRatherThanReshaped() {
        // A delta fitted at another horizon is not this forecast's correction. Drawing the raw fan
        // is the honest fallback; drawing a truncated or recycled one would not be.
        val p = prediction()
        val raw = buildPredSeries(p, UnitSpace.MgDl, null)!!
        for (wrong in listOf(emptyList(), widened(p).dropLast(nq), widened(p) + 0.0)) {
            val out = buildPredSeries(p, UnitSpace.MgDl, null, wrong)!!
            assertArrayEquals(raw.lo[0], out.lo[0], 0f)
            assertArrayEquals(raw.hi[0], out.hi[0], 0f)
        }
    }

    @Test
    fun noCorrectionDrawsTheRawFan() {
        val p = prediction()
        val raw = buildPredSeries(p, UnitSpace.MgDl, null)!!
        val none = buildPredSeries(p, UnitSpace.MgDl, null, null)!!
        for (b in 0 until 3) {
            assertArrayEquals(raw.lo[b], none.lo[b], 0f)
            assertArrayEquals(raw.hi[b], none.hi[b], 0f)
        }
    }

    // ── the seam with the rolled overlay ────────────────────────────────────────────────────────
    //
    // The roll's hatched band opens at the validated boundary and the fan ends there, so one instant
    // is drawn twice. Only the fan carries the §8.4 correction, so without a shared vertex the panel
    // states two uncertainties at exactly the x its own dashed rule says the two series join.

    /** A roll over the same anchor and grid: [steps] validated, then an extrapolated tail. */
    private fun roll(p: ModelPrediction, tail: Int): RolledForecast {
        val n = steps + tail
        return RolledForecast(
            anchorTsMs = p.anchorTsMs,
            stepMs = p.stepMs,
            medianBg = DoubleArray(n) { 100.0 + it },
            lowerBg = DoubleArray(n) { 85.0 + it },
            upperBg = DoubleArray(n) { 115.0 + it },
            validatedSteps = steps,
            requestedHours = 1.0,
            eligible = true,
            degenerate = false,
            reason = null,
            completedRolls = 1,
            requestedRolls = 1,
        )
    }

    @Test
    fun theBandOpensAtTheVeryInstantTheFanEnds() {
        // The premise the seam rests on: the fan's terminal point and the roll's last validated step
        // are one absolute time. Where they stop coinciding the seam must be refused, which is what
        // the timestamp guard below does.
        val p = prediction()
        val fan = buildPredSeries(p, UnitSpace.MgDl, null)!!
        val rs = buildRolledSeries(roll(p, tail = 4), UnitSpace.MgDl, null)!!
        assertEquals(fan.tsMs.last(), rs.tsMs[rs.bandFromIndex()])
    }

    @Test
    fun theSharedVertexIsTheFansEvenWhenItCarriesACorrection() {
        val p = prediction()
        val cal = buildPredSeries(p, UnitSpace.MgDl, null, widened(p))!!
        val rs = buildRolledSeries(roll(p, tail = 4), UnitSpace.MgDl, null)!!
        val last = cal.size - 1
        val seam = RolledSeam(cal.tsMs[last], cal.lo[0][last], cal.hi[0][last])

        // Without the seam the two disagree — the defect, restated as a test.
        assertNotEquals(rs.lo[rs.bandFromIndex()], cal.lo[0][last])
        // With it they are one number, and the tail past the boundary is left entirely alone.
        assertEquals(cal.lo[0][last], rs.bandOpenLo(seam), 0f)
        assertEquals(cal.hi[0][last], rs.bandOpenHi(seam), 0f)
        assertEquals(85f + steps.toFloat(), rs.lo[rs.bandFromIndex() + 1], 0f)
    }

    @Test
    fun aSeamAtAnotherInstantIsRefusedAndTheRollDrawsItsOwnEdge() {
        // A model whose horizon differs from the roll's validated prefix does not meet it at the
        // boundary; borrowing its edge there would state one model's uncertainty at another's step.
        val p = prediction()
        val rs = buildRolledSeries(roll(p, tail = 4), UnitSpace.MgDl, null)!!
        val i = rs.bandFromIndex()
        val elsewhere = RolledSeam(rs.tsMs[i] + p.stepMs, -1f, -1f)
        assertEquals(rs.lo[i], rs.bandOpenLo(elsewhere), 0f)
        assertEquals(rs.hi[i], rs.bandOpenHi(elsewhere), 0f)
        assertEquals(rs.lo[i], rs.bandOpenLo(null), 0f)
        assertEquals(rs.hi[i], rs.bandOpenHi(null), 0f)
    }
}
