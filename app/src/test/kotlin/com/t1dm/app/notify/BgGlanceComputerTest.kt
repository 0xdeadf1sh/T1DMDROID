package com.t1dm.app.notify

import com.t1dm.core.model.AlarmFanEdges
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceTelemetry
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.SensorArrow
import com.t1dm.core.model.WarmupProgress
import kotlinx.coroutines.runBlocking
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

    /** A 3-level fan, median ± [spread]; [edges] reads its outer two. */
    private fun prediction(
        median: List<Double>,
        status: ForecastStatus = ForecastStatus.OK,
        stale: Boolean = false,
        spread: Double = 15.0,
    ) = ModelPrediction(
        modelId = "m", cycleTsMs = now, anchorTsMs = now, stepMs = 300_000L,
        medianBg = median, bandsMgdl = median.flatMap { listOf(it - spread, it, it + spread) }, nQuantiles = 3,
        lastBg = median.first(), status = status, backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        selected = true, stale = stale, latencyMs = null,
    )

    private val edges = AlarmFanEdges(hypoIdx = 0, hyperIdx = 2)

    // idx:                         0      1      2     3     4     5     6
    private val falling = listOf(125.0, 115.0, 105.0, 80.0, 75.0, 72.0, 60.0)

    private fun glance(p: ModelPrediction, fanEdges: AlarmFanEdges? = edges) = BgGlanceComputer.compute(
        GlanceReadings.create(listOf(reading(112))), InferenceState(predictions = listOf(p)), thresholds,
        fanEdges, lossMin = 20, staleMin = 15, nowMs = now, trend = null,
    )

    @Test fun `eligible falling forecast yields approaching and urgent crossings with correct ETAs`() {
        val g = glance(prediction(falling))

        assertTrue(g.forecastEligible)
        assertTrue(g.predictedLowCrossing)
        assertFalse(g.unsure)

        val a = requireNotNull(g.approaching)
        assertEquals(PredictiveCrossing.Kind.HYPO, a.kind)
        assertEquals(70, a.thresholdMgdl)
        assertEquals("median 80 is in range; the lower edge 65 is not", 20, a.etaMin)
        assertEquals(65, a.projectedMgdl)
        assertEquals(PredictiveCrossing.Severity.WARNING, a.severity)

        val u = requireNotNull(g.urgent)
        assertEquals(PredictiveCrossing.Kind.HYPO, u.kind)
        assertEquals(55, u.thresholdMgdl)
        assertEquals(35, u.etaMin) // first edge <55 at idx 6 -> (6+1)*5
    }

    @Test fun `both edges out at the first crossing step reads unsure and raises nothing`() {
        // edges: [60,65,40] and [180,185,160]; step 2 alone would raise an urgent low.
        val g = glance(prediction(listOf(120.0, 125.0, 100.0), spread = 60.0))
        assertTrue(g.forecastEligible)
        assertTrue(g.unsure)
        assertNull(g.approaching)
        assertNull(g.urgent)
        assertTrue(g.predictedLowCrossing)
        assertTrue(g.predictedHighCrossing)
    }

    @Test fun `edges out at different steps take the earlier side`() {
        // edges: [65,85,115] and [135,155,185].
        val g = glance(prediction(listOf(100.0, 120.0, 150.0), spread = 35.0))
        assertFalse(g.unsure)
        assertEquals(PredictiveCrossing.Kind.HYPO, requireNotNull(g.approaching).kind)
        assertEquals(5, g.approaching!!.etaMin)
        assertTrue(g.predictedHighCrossing)
    }

    @Test fun `no edges, or edges off the fan, read the forecast as unavailable`() {
        for (e in listOf(null, AlarmFanEdges(hypoIdx = 0, hyperIdx = 3))) {
            val g = glance(prediction(falling), e)
            assertFalse("edges=$e", g.forecastEligible)
            assertTrue("edges=$e", g.forecastUnavailable)
            assertNull("edges=$e", g.approaching)
            assertNull("edges=$e", g.fcEndMgdl)
        }
    }

    @Test fun `the arrow is the sensor's rate, else a fit over the last fifteen minutes`() = runBlocking<Unit> {
        assertEquals(
            BgDirection(GlanceTrend.FALLING, reported = true),
            directionOf(reading(120), null) { error("a reported rate needs no fit") },
        )
        val rows = listOf(130 to 0L, 120 to 5L, 110 to 10L, 40 to 20L).map { (bg, min) -> reading(bg, min * 60_000L, null) }
        assertEquals("2 mg/dL/min; the 20-min row is outside", BgDirection(GlanceTrend.RISING, reported = false),
            directionOf(rows.first(), null) { rows })
        assertNull(directionOf(null, null) { rows })
    }

    private val slot = 1_700_000_100_000L

    private fun sensor(atMs: Long, arrow: SensorArrow) = CgmSourceTelemetry(sampledAtMs = atMs, arrow = arrow)

    @Test fun `the sensor's own arrow in the reading's slot outranks its rate`() = runBlocking<Unit> {
        val r = reading(120, trend = -18).copy(tsMs = slot)
        assertEquals(BgDirection(GlanceTrend.FLAT, reported = true),
            directionOf(r, sensor(slot + 120_000L, SensorArrow.FLAT)) { error("the sensor named one") })
        assertEquals("the next slot's arrow is not this reading's", BgDirection(GlanceTrend.FALLING, reported = true),
            directionOf(r, sensor(slot + 180_000L, SensorArrow.FLAT)) { error("the rate suffices") })
    }

    @Test fun `an undetermined sensor arrow is fitted, never read off the rate`() = runBlocking<Unit> {
        val rows = listOf(130 to 0L, 120 to 5L, 110 to 10L).map { (bg, min) ->
            reading(bg, trend = -18).copy(tsMs = slot - min * 60_000L)
        }
        assertEquals(BgDirection(GlanceTrend.RISING, reported = false),
            directionOf(rows.first(), sensor(slot, SensorArrow.UNDETERMINED)) { rows })
    }

    @Test fun `no arrow draws none, while the forecast keeps its own trend`() {
        val state = InferenceState(predictions = listOf(prediction(falling)))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(123, trend = null))), state, thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = null)

        assertNull(g.trend)
        assertEquals("", BgFormat.arrow(g.trend))
        assertEquals("123 to 60 over 35 min", GlanceTrend.FALLING, g.fcTrend)
    }

    @Test fun `forecast trend is the per-minute rate over the whole horizon`() {
        for ((end, expected) in listOf(150 to GlanceTrend.FLAT, 190 to GlanceTrend.RISING, 380 to GlanceTrend.RISING_FAST)) {
            val median = List(24) { 120.0 + (end - 120.0) * (it + 1) / 24 }
            val state = InferenceState(predictions = listOf(prediction(median)))
            val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(120))), state, thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = null)
            assertEquals("120 to $end over 120 min", expected, g.fcTrend)
        }
    }

    @Test fun `the arrow given is drawn and the forecast never overrides it`() {
        val state = InferenceState(predictions = listOf(prediction(listOf(200.0, 220.0, 240.0, 260.0))))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(123))), state, thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = GlanceTrend.FALLING)

        assertEquals(GlanceTrend.FALLING, g.trend)
        assertEquals(GlanceTrend.RISING_FAST, g.fcTrend)
    }

    @Test fun `stale reading draws no arrow`() {
        fun at(ageMin: Long) = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(123, ageMin * 60_000L))), InferenceState(), thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = GlanceTrend.FALLING)
        assertEquals(GlanceTrend.FALLING, at(15).trend)
        assertNull(at(16).trend)
        assertEquals("123", at(16).summary)
    }

    @Test fun `the summary draws the arrow it was given, and none for none`() {
        val expected = mapOf(
            GlanceTrend.RISING_FAST to "123 ↑↑", GlanceTrend.RISING to "123 ↑", GlanceTrend.FLAT to "123 →",
            GlanceTrend.FALLING to "123 ↓", GlanceTrend.FALLING_FAST to "123 ↓↓", null to "123",
        )
        for ((trend, summary) in expected) {
            val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(123))), InferenceState(), thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = trend)
            assertEquals("trend=$trend", summary, g.summary)
        }
    }

    @Test fun `stale forecast is ineligible - no predictive fields`() {
        val state = InferenceState(predictions = listOf(prediction(falling, stale = true)))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112))), state, thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = null)
        assertFalse(g.forecastEligible)
        assertNull(g.approaching)
        assertNull(g.urgent)
        assertTrue(g.forecastUnavailable)
    }

    @Test fun `anchor past staleMin is ineligible though unstamped`() {
        val state = InferenceState(predictions = listOf(prediction(falling).copy(anchorTsMs = now - 16 * 60_000L)))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112))), state, thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = null)
        assertFalse(g.forecastEligible)
        assertTrue(g.forecastUnavailable)
        assertNull(g.approaching)
        assertNull(g.urgent)
        assertNull(g.fcEndMgdl)
    }

    @Test fun `stale reading withholds forecast and crossings`() {
        val state = InferenceState(predictions = listOf(prediction(falling)))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112, ageMs = 16 * 60_000L))), state, thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = null)
        assertTrue(g.stale)
        assertFalse(g.forecastEligible)
        assertTrue(g.forecastUnavailable)
        assertFalse(g.predictedLowCrossing)
        assertNull(g.approaching)
        assertNull(g.urgent)
        assertNull(g.fcEndMgdl)
    }

    private fun token(s: GlyStatus): String = when (s) {
        is GlyStatus.Void -> "VOID"
        GlyStatus.Stable -> "STABLE"
        GlyStatus.Unsure -> "UNSURE"
        is GlyStatus.Excursion -> s.kind.name
    }

    @Test fun `notification token equals the status verdict`() {
        val clear = prediction(listOf(120.0, 125.0, 130.0))
        fun agree(case: String, p: ModelPrediction, ageMs: Long? = 60_000L, warmup: Boolean = false, fanEdges: AlarmFanEdges? = edges) {
            val state = InferenceState(
                predictions = listOf(p),
                warmup = if (warmup) WarmupProgress(measuredHours = 3.0, requiredHours = 24.0) else null,
            )
            val rows = listOfNotNull(ageMs?.let { reading(112, it) })
            val g = BgGlanceComputer.compute(GlanceReadings.create(rows), state, thresholds, fanEdges, lossMin = 20, staleMin = 15, nowMs = now, trend = null)
            val s = BgGlanceComputer.status(state, thresholds, fanEdges, now, g.readingAgeMs.takeIf { g.hasReading }, staleMin = 15)
            assertEquals(case, token(s), statusToken(g))
        }
        agree("warmup", clear, warmup = true)
        agree("no reading", clear, ageMs = null)
        agree("reading 16 min", clear, ageMs = 16 * 60_000L)
        agree("anchor 16 min", clear.copy(anchorTsMs = now - 16 * 60_000L))
        agree("stamped stale", clear.copy(stale = true))
        agree("rail pinned", clear.copy(status = ForecastStatus.RAIL_PINNED))
        agree("no edges", clear, fanEdges = null)
        agree("clear", clear)
        agree("unsure", prediction(listOf(120.0, 125.0, 100.0), spread = 60.0))
        agree("out", prediction(falling))
    }

    @Test fun `degenerate forecast is ineligible - no predictive fields`() {
        val state = InferenceState(predictions = listOf(prediction(falling, status = ForecastStatus.NON_FINITE)))
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112))), state, thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = null)
        assertFalse(g.forecastEligible)
        assertNull(g.approaching)
    }

    @Test fun `warmup gate degrades to collecting context - no crossing`() {
        val state = InferenceState(
            predictions = emptyList(),
            warmup = WarmupProgress(measuredHours = 3.0, requiredHours = 24.0),
        )
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112))), state, thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = null)
        assertFalse(g.forecastEligible)
        assertNull(g.approaching)
        assertEquals("collecting context", g.summary)
    }

    private fun status(p: ModelPrediction, readingAgeMs: Long? = 60_000L) = BgGlanceComputer.status(
        InferenceState(predictions = listOf(p)), thresholds, edges, now, readingAgeMs, staleMin = 15,
    )

    @Test fun `status names the first step out and when it falls`() {
        assertEquals(GlyStatus.Stable, status(prediction(listOf(120.0, 125.0, 130.0))))
        assertEquals(
            "lower edge 65 at idx 3",
            GlyStatus.Excursion(PredictiveCrossing.Kind.HYPO, now + 4 * 300_000L),
            status(prediction(falling)),
        )
        assertEquals(GlyStatus.Unsure, status(prediction(listOf(120.0, 125.0, 100.0), spread = 60.0)))
    }

    @Test fun `status is VOID on a stale reading or anchor, never STABLE`() {
        val clear = prediction(listOf(120.0, 125.0, 130.0))
        assertTrue(status(clear, readingAgeMs = null) is GlyStatus.Void)
        assertTrue(status(clear, readingAgeMs = 16 * 60_000L) is GlyStatus.Void)
        assertTrue(status(clear.copy(anchorTsMs = now - 16 * 60_000L)) is GlyStatus.Void)
        assertTrue(status(clear.copy(stale = true)) is GlyStatus.Void)
        assertTrue(status(clear.copy(status = ForecastStatus.RAIL_PINNED)) is GlyStatus.Void)
    }

    @Test fun `signal loss and stale flags track the reading age`() {
        val state = InferenceState()
        val g = BgGlanceComputer.compute(GlanceReadings.create(listOf(reading(112, ageMs = 25 * 60_000L))), state, thresholds, edges, lossMin = 20, staleMin = 15, nowMs = now, trend = null)
        assertTrue(g.signalLoss)
        assertTrue(g.stale)
        assertTrue(g.alarmActive)
    }
}
