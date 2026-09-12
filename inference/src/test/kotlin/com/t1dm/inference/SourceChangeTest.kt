package com.t1dm.inference

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `SPEC/invariants.md` §7.1. Caller is `CgmScanService`, on transitions between non-null ids. */
class SourceChangeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a promotion drops the forecast the outgoing sensor conditioned`() = runTest {
        val controller = controller(listOf(prediction("m1", selected = true), prediction("m2", selected = false)))
        controller.restoreLast()
        assertEquals(2, controller.state.value.predictions.size)

        controller.onCgmSourceChanged()

        val s = controller.state.value
        assertTrue("a fan built on the old sensor's history must not outlive it", s.predictions.isEmpty())
        // The stamps go too: they would date the emptiness to the outgoing sensor.
        assertNull(s.lastCycleTsMs)
        assertNull(s.lastCause)
    }

    @Test
    fun `dropping twice is the same as dropping once`() = runTest {
        val controller = controller(listOf(prediction("m1", selected = true)))
        controller.restoreLast()

        controller.onCgmSourceChanged()
        controller.onCgmSourceChanged()

        assertTrue(controller.state.value.predictions.isEmpty())
    }

    /** The circadian belief reads the hour of day, not the sensor. */
    @Test
    fun `the circadian read-out is not dropped with the forecast`() = runTest {
        val controller = controller(listOf(prediction("m1", selected = true)))
        controller.restoreLast()
        val before = controller.state.value.circadianTime

        controller.onCgmSourceChanged()

        assertEquals(before, controller.state.value.circadianTime)
    }

    private fun controller(restored: List<ModelPrediction>) = InferenceController(
        native = StubNativeCore(),
        dispatchers = UnconfinedDispatchers,
        store = ModelStore(tmp.newFolder("models"), StubNativeCore()),
        history = NoHistory,
        predictionStore = FixedPredictions(restored),
    )

    private fun prediction(id: String, selected: Boolean) = ModelPrediction(
        modelId = id,
        cycleTsMs = 1_700_000_000_000L,
        anchorTsMs = 1_700_000_000_000L,
        stepMs = 300_000L,
        medianBg = listOf(120.0, 121.0),
        bandsMgdl = listOf(110.0, 130.0, 111.0, 131.0),
        nQuantiles = 3,
        lastBg = 119.0,
        status = ForecastStatus.OK,
        backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        selected = selected,
        stale = false,
        latencyMs = 12.0,
    )

    private object UnconfinedDispatchers : T1dmDispatchers {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val inference = Dispatchers.Unconfined
        override val game = Dispatchers.Unconfined
    }

    private object NoHistory : BgHistoryProvider {
        override suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries? =
            recentBgSeries(maxSteps, minSteps)

        override suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries? = null
    }

    private class FixedPredictions(private val last: List<ModelPrediction>) : PredictionStore {
        override suspend fun persist(cycleTsMs: Long, predictions: List<ModelPrediction>) = Unit
        override suspend fun loadLast(): List<ModelPrediction> = last
    }
}
