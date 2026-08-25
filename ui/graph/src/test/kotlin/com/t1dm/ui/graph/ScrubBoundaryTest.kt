package com.t1dm.ui.graph

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.RolledForecast
import com.t1dm.core.model.UnitSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubBoundaryTest {

    private val STEP = 300_000L
    private val T0 = 1_700_000_000_000L
    private val NQ = 7

    private fun reading(ts: Long) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = 100, trendTenthsPerMin = 0,
        minFromStart = 5, quality = 100, provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL, tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

    private val frame = buildGraphFrame((11 downTo 0).map { reading(T0 - it * STEP) })

    private val validated: PredSeries = run {
        val median = List(24) { 101.0 + it }
        val bands = ArrayList<Double>(24 * NQ)
        for (m in median) bands += listOf(m - 15, m - 10, m - 5, m, m + 5, m + 10, m + 15)
        buildPredSeries(
            ModelPrediction(
                modelId = "m", cycleTsMs = T0, anchorTsMs = T0, stepMs = STEP,
                medianBg = median, bandsMgdl = bands, nQuantiles = NQ, lastBg = 100.0,
                status = ForecastStatus.OK, backend = BackendId.EXECUTORCH_XNNPACK_FP32,
                precision = Precision.FP32, selected = true, stale = false, latencyMs = null,
            ),
            UnitSpace.MgDl, null,
        )!!
    }

    private val roll: RolledSeries = buildRolledSeries(
        RolledForecast(
            anchorTsMs = T0, stepMs = STEP,
            medianBg = DoubleArray(48) { 101.0 + it },
            lowerBg = DoubleArray(48) { 96.0 + it },
            upperBg = DoubleArray(48) { 106.0 + it },
            validatedSteps = 24, requestedHours = 4.0, eligible = true, degenerate = false,
            reason = null, completedRolls = 2, requestedRolls = 2,
        ),
        UnitSpace.MgDl, null,
    )!!

    private fun scrubAt(steps: Double, predictions: List<PredSeries> = listOf(validated)) =
        buildScrub(frame, predictions, null, null, null, roll, T0 + steps * STEP)

    private fun bgRow(sc: GraphScrub) = scrubRows(sc).first()

    private fun stepsRow(sc: GraphScrub) = scrubRows(sc).firstOrNull { it.first == "Steps" }


    @Test fun aWiredStepsFeedAlwaysYieldsARow() {
        val f = buildStepsFrame(intArrayOf(0, 40, StepsFrame.NO_DATA), T0 - 11 * STEP)
        fun at(ms: Double) = buildScrub(frame, listOf(validated), null, f, null, roll, ms)

        assertEquals("Steps" to "40", stepsRow(at((T0 - 10 * STEP).toDouble())))
        // Measured still.
        assertEquals("Steps" to "0", stepsRow(at((T0 - 11 * STEP).toDouble())))
        // Never measured — 0 by the read-out's own choice, not the frame's.
        assertEquals("Steps" to "0", stepsRow(at((T0 - 9 * STEP).toDouble())))
        // Off the grid entirely.
        assertEquals("Steps" to "0", stepsRow(at((T0 - 40 * STEP).toDouble())))
        assertEquals("Steps" to "0", stepsRow(at(T0 + 30.0 * STEP)))
        // The frame itself still knows the difference; only the read-out coerces.
        assertNull(f.stepsAt(T0 - 9 * STEP))
        assertEquals(0, f.stepsAt(T0 - 11 * STEP)!!.toInt())
    }

    @Test fun noStepsFeedOmitsTheRowEntirely() {
        // No feed at all is a different statement from "no steps then".
        assertNull(stepsRow(scrubAt(0.0)))
    }

    @Test fun scrubbingAcrossTheBoundaryReportsContinuously() {
        val before = scrubAt(23.0) // inside the validated forecast
        val seam = scrubAt(24.0) // the last validated step, where the roll's prefix ends
        val after = scrubAt(25.0) // the first extrapolated step

        assertEquals(123f, before.bgValue!!, 1e-3f)
        assertEquals(124f, seam.bgValue!!, 1e-3f)
        assertEquals(125f, after.bgValue!!, 1e-3f)

        // An unbounded scan would answer past the horizon with the horizon's own value.
        assertNotEquals(seam.bgValue, after.bgValue)

        var t = 20.0
        while (t <= 48.0) {
            assertTrue("no reading at step $t", scrubAt(t).bgValue != null)
            t += 0.1
        }
    }

    @Test fun theSeamItselfReadsTheValidatedForecast() {
        val seam = scrubAt(24.0)
        assertFalse(seam.bgExtrapolated)
        assertTrue(seam.inPredZone)
        assertEquals("BG" to "124*", bgRow(seam))

        // The validated series answers to half a step past its last; the roll takes over there.
        assertFalse(scrubAt(24.4).bgExtrapolated)
        assertTrue(scrubAt(24.6).bgExtrapolated)
    }

    @Test fun aValueFromTheRolledTailIsReportedAndFlagged() {
        val after = scrubAt(25.0)
        assertTrue("the field still records which series answered", after.bgExtrapolated)
        assertEquals("BG" to "125*", bgRow(after))
        val last = scrubAt(48.0)
        assertTrue(last.bgExtrapolated)
        assertEquals("BG" to "148*", bgRow(last))
    }

    @Test fun theRollsValidatedPrefixIsNotMarked() {
        // No validated forecast, so the roll answers inside its own validated prefix.
        val warm = scrubAt(10.0, predictions = emptyList())
        assertEquals(110f, warm.bgValue!!, 1e-3f)
        assertFalse(warm.bgExtrapolated)
        assertEquals("BG" to "110*", bgRow(warm))
    }

    @Test fun pastTheEndOfEverythingTheRowWithholds() {
        // Nothing past the roll: withheld rather than extending the last number.
        val beyond = scrubAt(60.0)
        assertNull(beyond.bgValue)
        assertFalse(beyond.bgExtrapolated)
        assertEquals("BG" to "--", bgRow(beyond))

        val noRoll = buildScrub(frame, listOf(validated), null, null, null, null, T0 + 40.0 * STEP)
        assertNull(noRoll.bgValue)
    }

    @Test fun thePastSideIsUnchanged() {
        val past = buildScrub(frame, listOf(validated), null, null, null, roll, T0 - 3.0 * STEP)
        assertEquals(100f, past.bgValue!!, 1e-3f)
        assertFalse(past.inPredZone)
        assertFalse(past.bgExtrapolated)
        assertEquals("BG" to "100", bgRow(past))
    }
}
