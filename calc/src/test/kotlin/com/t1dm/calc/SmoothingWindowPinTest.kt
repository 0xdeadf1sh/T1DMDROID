package com.t1dm.calc

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3.6-F: the decision card discloses the BG input filter (INFERENCE.md §7.1) the recommended fan was
 * BUILT at, so one recommendation must resolve that window exactly once and pin it onto every roll of
 * the grid search. The source here hands back a different detent on every read — a Settings › Graph
 * edit landing while the (cancellable, navigable-away-from) search is in flight — so a second read
 * would surface both as a grid ranked across two `last_bg` anchors and as a card describing its own
 * fan with a window that fan never saw.
 */
class SmoothingWindowPinTest {

    private class RecordingPort(private val inner: ForecastPort) : ForecastPort {
        val windows = ArrayList<Int?>()
        override suspend fun roll(request: ForecastRequest): PredFan {
            windows.add(request.smoothingWindow)
            return inner.roll(request)
        }
    }

    @Test
    fun oneRecommendationPinsASingleWindowOntoEveryRollAndOntoTheCard() = runTest {
        val stops = listOf(1, 7, 13, 19, 25)
        var reads = 0
        val port = RecordingPort(FakeForecastPort(startBg = 230.0, mgdlPerU = 15.0))
        val now = 1_900_000_000_000L
        val advisor = DoseAdvisor(
            bolus = BolusCalculator(port, FakeBolusResolver()),
            anchorSource = { fakeAnchor(now) },
            iobSource = { fakeIob(now) },
            backendSource = { fp32Backend() },
            smoothingWindowSource = { stops[reads++ % stops.size] },
        )

        val r = advisor.recommendBolus(now, emptyList(), CalcConfig())

        assertTrue("the fakes must produce a recommendation", r is AdviceResult.Recommended)
        assertEquals("the window must be resolved once per recommendation", 1, reads)
        assertTrue("the search must have rolled at least the baseline", port.windows.isNotEmpty())
        assertEquals("every candidate must be rolled at the same window", setOf<Int?>(stops[0]), port.windows.toSet())
        assertEquals(stops[0], (r as AdviceResult.Recommended).card.smoothingWindow)
    }
}
