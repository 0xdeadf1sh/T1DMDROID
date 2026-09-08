package com.t1dm.app.notify

import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.WarmupProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The predictive-crossing ETA math and the §3.6 gate the three glanceable surfaces share. */
class BgGlanceComputerTest {

    private val now = 1_700_000_000_000L
    private val thresholds = AlertThresholds(urgentLowMgdl = 55, lowMgdl = 70, highMgdl = 180, urgentHighMgdl = 250)

    private fun reading(bg: Int?, ageMs: Long = 0L, trend: Int? = -18) = CgmReading(
        sourceId = CgmSourceId("aidexx:TEST"),
        tsMs = now - ageMs,
        bgMgdl = bg,
        trendTenthsPerMin = trend,
        minFromStart = 100,
        quality = 100,
        provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL,
        tzOffsetMin = 0,
        rxWallMs = now - ageMs,
        rssi = -60,
    )

    private fun prediction(median: List<Double>, status: ForecastStatus = ForecastStatus.OK, stale: Boolean = false) =
        ModelPrediction(
            modelId = "m", cycleTsMs = now, anchorTsMs = now, stepMs = 300_000L,
            medianBg = median, bandsMgdl = median.flatMap { listOf(it - 15, it, it + 15) }, nQuantiles = 3,
            lastBg = median.first(), status = status, backend = BackendId.EXECUTORCH_XNNPACK_FP32,
            precision = Precision.FP32, selected = true, stale = stale, latencyMs = null,
        )

    // idx:        0    1   2    3    4    5    6
    private val falling = listOf(110.0, 100.0, 90.0, 68.0, 60.0, 56.0, 50.0)

    @Test fun `eligible falling forecast yields approaching and urgent crossings with correct ETAs`() {
        val state = InferenceState(predictions = listOf(prediction(falling)))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112))), state, thresholds, lossMin = 20, staleMin = 15, nowMs = now)

        assertTrue(g.forecastEligible)
        assertTrue(g.predictedLowCrossing)

        val a = requireNotNull(g.approaching)
        assertEquals(PredictiveCrossing.Kind.HYPO, a.kind)
        assertEquals(70, a.thresholdMgdl)
        assertEquals(20, a.etaMin) // first <70 at idx 3 -> (3+1)*5
        assertEquals(PredictiveCrossing.Severity.WARNING, a.severity)

        val u = requireNotNull(g.urgent)
        assertEquals(PredictiveCrossing.Kind.HYPO, u.kind)
        assertEquals(55, u.thresholdMgdl)
        assertEquals(35, u.etaMin) // first <55 at idx 6 -> (6+1)*5
    }

    @Test fun `a source reporting no rate has no arrow, while the forecast keeps its own trend`() {
        val state = InferenceState(predictions = listOf(prediction(falling)))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(123, trend = null))), state, thresholds, lossMin = 20, staleMin = 15, nowMs = now)

        assertNull(g.trend)
        assertEquals("", BgFormat.arrow(g.trend))
        assertEquals(GlanceTrend.FALLING_FAST, g.fcTrend)
    }

    @Test fun `a measured rate owns the arrow and the forecast never overrides it`() {
        val state = InferenceState(predictions = listOf(prediction(listOf(200.0, 220.0, 240.0, 260.0))))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(123, trend = -18))), state, thresholds, lossMin = 20, staleMin = 15, nowMs = now)

        assertEquals(GlanceTrend.FALLING, g.trend)
        assertEquals(GlanceTrend.RISING_FAST, g.fcTrend)
    }

    /** Bottom bar classifies for itself; it and the glance must never disagree about a reading. */
    @Test fun `the bottom bar and the glance classify one reading identically`() {
        val state = InferenceState(predictions = listOf(prediction(falling)))
        for (tenths in listOf(null, -25, -18, -3, 0, 8, 25)) {
            val r = reading(123, trend = tenths)
            val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(r)), state, thresholds, lossMin = 20, staleMin = 15, nowMs = now)
            assertEquals("tenths=$tenths", g.trend, BgGlanceComputer.measuredTrend(r.trendTenthsPerMin))
        }
    }

    @Test fun `stale forecast is ineligible - no predictive fields`() {
        val state = InferenceState(predictions = listOf(prediction(falling, stale = true)))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112))), state, thresholds, lossMin = 20, staleMin = 15, nowMs = now)
        assertFalse(g.forecastEligible)
        assertNull(g.approaching)
        assertNull(g.urgent)
        assertTrue(g.forecastUnavailable)
    }

    @Test fun `degenerate forecast is ineligible - no predictive fields`() {
        val state = InferenceState(predictions = listOf(prediction(falling, status = ForecastStatus.NON_FINITE)))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112))), state, thresholds, lossMin = 20, staleMin = 15, nowMs = now)
        assertFalse(g.forecastEligible)
        assertNull(g.approaching)
    }

    @Test fun `warmup gate degrades to collecting context - no crossing`() {
        val state = InferenceState(
            predictions = emptyList(),
            warmup = WarmupProgress(measuredHours = 3.0, requiredHours = 24.0),
        )
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112))), state, thresholds, lossMin = 20, staleMin = 15, nowMs = now)
        assertFalse(g.forecastEligible)
        assertNull(g.approaching)
        assertEquals("collecting context", g.summary)
    }

    @Test fun `signal loss and stale flags track the reading age`() {
        val state = InferenceState()
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112, ageMs = 25 * 60_000L))), state, thresholds, lossMin = 20, staleMin = 15, nowMs = now)
        assertTrue(g.signalLoss)
        assertTrue(g.stale)
        assertTrue(g.alarmActive)
    }
}
