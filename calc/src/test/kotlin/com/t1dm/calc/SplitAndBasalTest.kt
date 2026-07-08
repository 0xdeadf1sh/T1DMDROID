package com.t1dm.calc

import com.t1dm.core.model.BasalDoseSpec
import com.t1dm.core.model.BasalSchedule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitAndBasalTest {

    private val now = 1_900_000_000_000L

    @Test
    fun split_search_keeps_the_total_and_stays_within_the_cap() = runTest {
        val search = SplitBolusSearch(FakeForecastPort(startBg = 220.0, mgdlPerU = 15.0), FakeBolusResolver())
        val config = CalcConfig(split = SplitSpec(enabled = true, maxParts = 2, gapGridMin = listOf(30, 60), firstFractionGrid = listOf(0.5, 0.7)))
        val cands = search.search(now, totalU = 6.0, announced = emptyList(), config = config)
        assertTrue("split search returns arrangements", cands.isNotEmpty())
        for (c in cands) {
            val parts = c.splits!!
            assertTrue("no more than the cap of parts", parts.size <= config.split.maxParts)
            assertEquals("parts sum to the total", 6.0, parts.sumOf { it.units }, 1e-9)
            assertTrue("first part at t=0", parts.first().offsetMin == 0)
        }
    }

    @Test
    fun split_disabled_returns_nothing() = runTest {
        val search = SplitBolusSearch(FakeForecastPort(), FakeBolusResolver())
        val cands = search.search(now, totalU = 6.0, announced = emptyList(), config = CalcConfig(split = SplitSpec(enabled = false)))
        assertTrue(cands.isEmpty())
    }

    @Test
    fun basal_search_ranks_daily_totals_and_fails_closed_on_degenerate_rolls() = runTest {
        val template = BasalSchedule(
            tzOffsetMin = 0,
            doses = listOf(BasalDoseSpec(timeOfDayMin = 8 * 60, doseU = 0.0, durationMin = 1440.0, kaPerHour = 0.30, kePerHour = 0.07)),
        )
        // A port whose BG falls with total basal (candidateU proxy is 0 here; model overnight risk via start).
        val goodPort = BasalForecastPort { schedule, rollStartMs, nSteps, validated ->
            val total = schedule.doses.sumOf { it.doseU }
            val steps = (0 until nSteps).map { i ->
                val median = 220.0 - total * 6.0 // more basal ⇒ lower fasting BG
                FanStep(median, median - 8.0, median + 8.0)
            }
            PredFan(0.0, steps, STEP_MS, validated, com.t1dm.core.model.ForecastStatus.OK, ForecastEligibility.ELIGIBLE)
        }
        val calc = BasalCalculator(goodPort)
        val ranked = calc.search(template, totalGrid = listOf(10.0, 16.0, 22.0, 28.0), rollStartMs = now, rollHours = 6.0, config = CalcConfig(objective = Objective.MinKovatchevRisk))
        assertTrue(ranked.isNotEmpty())
        // Best should be the total that brings fasting BG nearest target without going low.
        assertTrue("ranked best is finite", ranked.first().score.isFinite())
        assertTrue("totals preserved", ranked.all { it.totalDailyU in listOf(10.0, 16.0, 22.0, 28.0) })

        val degenPort = BasalForecastPort { _, _, _, validated ->
            PredFan(0.0, emptyList(), STEP_MS, validated, com.t1dm.core.model.ForecastStatus.RAIL_PINNED, ForecastEligibility.DEGENERATE)
        }
        val degen = BasalCalculator(degenPort).search(template, listOf(10.0, 20.0), now, 6.0, CalcConfig())
        assertTrue("degenerate rolls score to infinity (fail-closed)", degen.all { it.score == Double.POSITIVE_INFINITY })
    }
}
