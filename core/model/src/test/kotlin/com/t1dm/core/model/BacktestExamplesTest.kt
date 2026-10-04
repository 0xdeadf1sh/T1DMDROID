package com.t1dm.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestExamplesTest {

    private val step = 300_000L
    private val hour = 3_600_000L
    private val context = 12 * hour

    /** Two steps, flat at [median]; truth at [truth]; made at [lastBg]. */
    private fun scored(atMs: Long, median: Double, truth: Double, lastBg: Double) =
        ForecastWindow(List(14) { median }, listOf(median, median), listOf(truth, truth), lastBg) to
            ModelPrediction(
                modelId = "m", cycleTsMs = atMs, anchorTsMs = atMs, sourceId = "s", stepMs = step,
                medianBg = listOf(median, median), bandsMgdl = List(14) { median }, nQuantiles = 7,
                lastBg = lastBg, status = ForecastStatus.OK, backend = BackendId.STUB,
                selected = false, stale = false, latencyMs = null,
            )

    private fun windowSet(vararg rows: Pair<ForecastWindow, ModelPrediction>) =
        ForecastWindowSet(rows.map { it.first }, rows.size, 0, 0, rows.map { it.second })

    @Test fun `good ranks by gain over persistence and drops windows persistence beat`() {
        val set = windowSet(
            scored(0, median = 150.0, truth = 150.0, lastBg = 100.0),
            scored(20 * hour, median = 140.0, truth = 150.0, lastBg = 50.0),
            scored(40 * hour, median = 120.0, truth = 100.0, lastBg = 100.0),
        )
        val good = pickBacktestExamples(set, context, 4).good
        assertEquals(listOf(20 * hour, 0L), good.map { it.cycleTsMs })
        assertEquals(10.0, good[0].maeMgdl, 0.0)
        assertEquals(100.0, good[0].persistMaeMgdl, 0.0)
    }

    @Test fun `bad ranks by median error alone`() {
        val set = windowSet(
            scored(0, median = 100.0, truth = 110.0, lastBg = 100.0),
            scored(20 * hour, median = 100.0, truth = 190.0, lastBg = 190.0),
        )
        assertEquals(listOf(20 * hour, 0L), pickBacktestExamples(set, context, 4).bad.map { it.cycleTsMs })
    }

    @Test fun `a pick inside an earlier pick's panel is skipped, one just outside is kept`() {
        val span = context + 2 * step
        val set = windowSet(
            scored(0, median = 100.0, truth = 200.0, lastBg = 100.0),
            scored(step, median = 100.0, truth = 190.0, lastBg = 100.0),
            scored(span - 1, median = 100.0, truth = 180.0, lastBg = 100.0),
            scored(span, median = 100.0, truth = 170.0, lastBg = 100.0),
        )
        assertEquals(listOf(0L, span), pickBacktestExamples(set, context, 4).bad.map { it.cycleTsMs })
    }

    @Test fun `stops at the list size, and refuses a set without its forecasts`() {
        val rows = Array(6) { scored(it * 20 * hour, median = 100.0, truth = 120.0 + it, lastBg = 100.0) }
        assertEquals(2, pickBacktestExamples(windowSet(*rows), context, 2).bad.size)
        val bare = ForecastWindowSet(rows.map { it.first }, rows.size, 0)
        assertTrue(pickBacktestExamples(bare, context, 2).bad.isEmpty())
    }
}
