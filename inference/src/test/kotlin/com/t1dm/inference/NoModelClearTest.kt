package com.t1dm.inference

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** With nothing installed nothing re-derives the belief; it must not outlive the last model. */
class NoModelClearTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `an empty models dir clears the restored forecast and the circadian belief`() = runTest {
        val controller = controller()
        controller.restoreLast()
        assertTrue(controller.state.value.predictions.isNotEmpty())
        assertNotNull(controller.state.value.circadianTime)

        controller.refreshModels()

        val s = controller.state.value
        assertTrue(s.predictions.isEmpty())
        assertNull(s.circadianTime)
        assertNull(s.circadianAnchorMs)
        assertFalse(s.circadianLowContext)
    }

    @Test
    fun `deleting the only model clears the circadian belief`() = runTest {
        val controller = controller()
        controller.restoreLast()

        controller.deleteModel("m1")

        assertNull(controller.state.value.circadianTime)
        assertNull(controller.state.value.circadianAnchorMs)
    }

    private fun controller() = InferenceController(
        native = StubNativeCore(),
        dispatchers = UnconfinedDispatchers,
        store = ModelStore(tmp.newFolder("models"), StubNativeCore()),
        history = NoHistory,
        predictionStore = FixedPredictions(listOf(prediction())),
    )

    private fun prediction() = ModelPrediction(
        modelId = "m1",
        cycleTsMs = 1_700_000_000_000L,
        anchorTsMs = 1_700_000_000_000L,
        stepMs = 300_000L,
        medianBg = listOf(120.0, 121.0),
        bandsMgdl = listOf(110.0, 130.0, 111.0, 131.0),
        nQuantiles = 3,
        lastBg = 119.0,
        status = ForecastStatus.OK,
        backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        selected = true,
        stale = false,
        latencyMs = 12.0,
        predictedTime = PredictedTime(
            probs = List(12) { 1.0 / 12.0 },
            predictedHour = 7.0,
            resultantR = 0.42,
            nBins = 12,
            binHours = 2.0,
        ),
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
