package com.t1dm.ui.graph

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.RolledForecast
import com.t1dm.core.model.UnitSpace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The display seam of the on-device band recalibration — `SPEC/inference.md` §8.4. */
class PredOverlayCalibrationTest {

    private val nq = 7
    private val steps = 3

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

        // §8.4 holds the median fixed, so there is no calibrated median to draw.
        assertArrayEquals(raw.median, cal.median, 0f)

        // lo/hi index 0 is the outer band.
        for (i in 1..steps) {
            assertEquals(raw.lo[0][i] - 20f, cal.lo[0][i], 1e-4f)
            assertEquals(raw.hi[0][i] + 20f, cal.hi[0][i], 1e-4f)
        }
        assertNotEquals(raw.lo[0][1], cal.lo[0][1])
    }

    @Test
    fun theAnchorStaysZeroWidthUnderACorrection() {
        // Element 0 is the last measured BG, not a forecast step: no band to recalibrate.
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
        // A delta fitted at another horizon isn't this forecast's; raw fan is the honest fallback.
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


    private fun roll(p: ModelPrediction, tail: Int): RolledForecast {
        val n = steps + tail
        return RolledForecast(
            anchorTsMs = p.anchorTsMs,
            stepMs = p.stepMs,
            medianBg = DoubleArray(n) { 100.0 + it },
            lowerBg = DoubleArray(n) { 85.0 + it },
            upperBg = DoubleArray(n) { 115.0 + it },
            // Ascending τ: .05 .10 .25 .50 .75 .90 .95, nesting inwards around the median.
            bandsMgdl = DoubleArray(n * 7) { i ->
                val step = i / 7
                when (i % 7) {
                    0 -> 85.0; 1 -> 90.0; 2 -> 95.0; 3 -> 100.0; 4 -> 105.0; 5 -> 110.0; else -> 115.0
                } + step
            },
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
    fun aRollWithAFanCarriesTheSameThreePairsTheForecastDoes() {
        val p = prediction()
        val rs = buildRolledSeries(roll(p, tail = 4), UnitSpace.MgDl, null)!!
        assertEquals(3, rs.lo.size)
        assertEquals(3, rs.hi.size)
        // Outer→inner.
        for (i in 0 until rs.median.size) {
            assertTrue(rs.lo[0][i] <= rs.lo[1][i] && rs.lo[1][i] <= rs.lo[2][i])
            assertTrue(rs.hi[0][i] >= rs.hi[1][i] && rs.hi[1][i] >= rs.hi[2][i])
        }
        // Index 0 is lowerBg/upperBg.
        assertEquals(85f, rs.lo[0][0], 0f)
        assertEquals(115f, rs.hi[0][0], 0f)
    }

    @Test
    fun aRollWithoutAFanKeepsItsOneOuterPair() {
        val p = prediction()
        val bare = roll(p, tail = 4).copy(bandsMgdl = DoubleArray(0))
        val rs = buildRolledSeries(bare, UnitSpace.MgDl, null)!!
        assertEquals(1, rs.lo.size)
        assertEquals(85f, rs.lo[0][0], 0f)
        assertEquals(115f, rs.hi[0][0], 0f)
    }

    @Test
    fun theBandOpensAtTheVeryInstantTheFanEnds() {
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

        assertNotEquals(rs.lo[0][rs.bandFromIndex()], cal.lo[0][last])
        assertEquals(cal.lo[0][last], rs.bandOpenLo(seam, 0), 0f)
        assertEquals(cal.hi[0][last], rs.bandOpenHi(seam, 0), 0f)
        assertEquals(85f + steps.toFloat(), rs.lo[0][rs.bandFromIndex() + 1], 0f)
        // Inner pairs open on their own edge: the seam is one uncertainty, not a fan.
        assertEquals(rs.lo[1][rs.bandFromIndex()], rs.bandOpenLo(seam, 1), 0f)
        assertEquals(rs.hi[2][rs.bandFromIndex()], rs.bandOpenHi(seam, 2), 0f)
    }

    @Test
    fun aSeamAtAnotherInstantIsRefusedAndTheRollDrawsItsOwnEdge() {
        // Borrowing an edge at another instant states one model's uncertainty at another's step.
        val p = prediction()
        val rs = buildRolledSeries(roll(p, tail = 4), UnitSpace.MgDl, null)!!
        val i = rs.bandFromIndex()
        val elsewhere = RolledSeam(rs.tsMs[i] + p.stepMs, -1f, -1f)
        assertEquals(rs.lo[0][i], rs.bandOpenLo(elsewhere, 0), 0f)
        assertEquals(rs.hi[0][i], rs.bandOpenHi(elsewhere, 0), 0f)
        assertEquals(rs.lo[0][i], rs.bandOpenLo(null, 0), 0f)
        assertEquals(rs.hi[0][i], rs.bandOpenHi(null, 0), 0f)
    }
}
