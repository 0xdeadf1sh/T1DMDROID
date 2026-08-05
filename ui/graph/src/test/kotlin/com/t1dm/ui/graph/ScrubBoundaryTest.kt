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

/**
 * The scrub read-out ACROSS the validated/rolled boundary.
 *
 * The panel draws two forecasts end to end — the validated 2 h fan, then the display-only rolled
 * tail — and the read-out samples whichever covers the cursor. Two things are asserted here, and they
 * are inseparable: that dragging across the seam keeps reporting (an unbounded nearest-step scan over
 * the validated series answered every time after its horizon with its last step, which froze the row
 * at the 2 h value and made the rolled fallback beneath it unreachable), and that a value taken from
 * the extrapolated tail is MARKED — the tail is hatched, dashed, bounded and captioned everywhere
 * else on the panel, so a read-out printing it exactly like a validated number would be the one
 * surface contradicting all of them.
 *
 * Geometry of the fixture: readings end at [T0]; the validated forecast covers `T0 + (1..24)·STEP`
 * with medians 101…124; the roll covers `T0 + (1..48)·STEP` with medians 101…148 and a validated
 * prefix of 24, so its step 23 coincides with the forecast's last step and its step 24 is the first
 * extrapolated one. Medians rise by one per step, so a frozen row is arithmetically distinguishable
 * from a tracking one at every sample.
 */
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

    /** The validated 2 h forecast: 24 steps, median 101…124, a plain ±{5,10,15} fan. */
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

    /** The 4 h roll continuing it: 48 steps, median 101…148, the first 24 inside the validated 2 h. */
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

    // ── the Steps row's presence rules ───────────────────────────────────────────────────────────

    @Test fun aWiredStepsFeedAlwaysYieldsARow() {
        // The box must keep a fixed shape as the thumb travels: a line that appeared and vanished
        // with the data would read as a glitch. So with a feed present every cursor gets a Steps row,
        // and an unmeasured bucket or a cursor off the grid reads 0 rather than dropping it.
        val f = buildStepsFrame(intArrayOf(0, 40, StepsFrame.NO_DATA), T0 - 11 * STEP)
        fun at(ms: Double) = buildScrub(frame, listOf(validated), null, f, null, roll, ms)

        assertEquals("Steps" to "40", stepsRow(at((T0 - 10 * STEP).toDouble())))
        // Measured still.
        assertEquals("Steps" to "0", stepsRow(at((T0 - 11 * STEP).toDouble())))
        // Never measured — shown as 0 by the read-out's own choice, not by the frame's.
        assertEquals("Steps" to "0", stepsRow(at((T0 - 9 * STEP).toDouble())))
        // Off the grid entirely, including out in the forecast zone where no step can exist.
        assertEquals("Steps" to "0", stepsRow(at((T0 - 40 * STEP).toDouble())))
        assertEquals("Steps" to "0", stepsRow(at(T0 + 30.0 * STEP)))
        // The frame itself still knows the difference; only the read-out coerces.
        assertNull(f.stepsAt(T0 - 9 * STEP))
        assertEquals(0, f.stepsAt(T0 - 11 * STEP)!!.toInt())
    }

    @Test fun noStepsFeedOmitsTheRowEntirely() {
        // No pedometer wired at all is a different statement from "no steps then", and the panel
        // should not invent a row for a feature it does not have.
        assertNull(stepsRow(scrubAt(0.0)))
    }

    @Test fun scrubbingAcrossTheBoundaryReportsContinuously() {
        val before = scrubAt(23.0) // inside the validated forecast
        val seam = scrubAt(24.0) // exactly the last validated step, where the roll's prefix ends
        val after = scrubAt(25.0) // the first extrapolated step

        assertEquals(123f, before.bgValue!!, 1e-3f)
        assertEquals(124f, seam.bgValue!!, 1e-3f)
        assertEquals(125f, after.bgValue!!, 1e-3f)

        // The freeze this test exists for: an unbounded scan answers every time past the horizon with
        // the horizon's own value, so `after` would equal `seam` and the row would never move again.
        assertNotEquals(seam.bgValue, after.bgValue)

        // No gap anywhere between the two series either: sampled every 30 s from well inside the
        // validated fan to the far end of the roll, the row always has a number.
        var t = 20.0
        while (t <= 48.0) {
            assertTrue("no reading at step $t", scrubAt(t).bgValue != null)
            t += 0.1
        }
    }

    @Test fun theSeamItselfReadsTheValidatedForecast() {
        // The two series meet at T0+24·STEP. The validated one owns that step — it is not extrapolated,
        // and it keeps the plain prediction marker.
        val seam = scrubAt(24.0)
        assertFalse(seam.bgExtrapolated)
        assertTrue(seam.inPredZone)
        assertEquals("BG" to "124*", bgRow(seam))

        // The validated series keeps answering to half a step past its last, exactly as it does between
        // any two of its own steps; the roll takes over from there.
        assertFalse(scrubAt(24.4).bgExtrapolated)
        assertTrue(scrubAt(24.6).bgExtrapolated)
    }

    @Test fun aValueFromTheExtrapolatedTailIsMarked() {
        val after = scrubAt(25.0)
        assertTrue(after.bgExtrapolated)
        assertEquals("BG" to "125~", bgRow(after))
        // …all the way to the end of the roll.
        val last = scrubAt(48.0)
        assertTrue(last.bgExtrapolated)
        assertEquals("BG" to "148~", bgRow(last))
    }

    @Test fun theRollsValidatedPrefixIsNotMarked() {
        // Warmup: no validated forecast exists at all, so the roll answers inside its own validated
        // prefix. Those steps coincide with the 2 h horizon and the overlay draws them as a plain line
        // — marking them would cry wolf over the region that is not extrapolated.
        val warm = scrubAt(10.0, predictions = emptyList())
        assertEquals(110f, warm.bgValue!!, 1e-3f)
        assertFalse(warm.bgExtrapolated)
        assertEquals("BG" to "110*", bgRow(warm))
    }

    @Test fun pastTheEndOfEverythingTheRowWithholds() {
        // Beyond the roll there is no forecast of any kind, and the panel draws nothing there. The
        // read-out says so rather than extending the last number across the empty future view.
        val beyond = scrubAt(60.0)
        assertNull(beyond.bgValue)
        assertFalse(beyond.bgExtrapolated)
        assertEquals("BG" to "--", bgRow(beyond))

        // Same past the validated horizon when no roll has been requested.
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
