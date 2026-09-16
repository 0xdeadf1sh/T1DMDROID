package com.t1dm.inference

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BacktestRefusal
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BacktestRefusalTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a model that is not loaded replays nothing`() = runTest {
        val controller = InferenceController(
            native = StubNativeCore(),
            dispatchers = UnconfinedDispatchers,
            store = ModelStore(tmp.newFolder("models"), StubNativeCore()),
            history = NoHistory,
            predictionStore = NoPredictions,
        )
        controller.refreshModels()
        var inputsBuilt = 0

        val run = controller.backtest("absent", listOf(1L, 2L), { _, _ -> inputsBuilt++; null }) { _, _ -> }

        assertEquals(BacktestRefusal.NOT_LOADED, run.refusal)
        assertTrue(run.forecasts.isEmpty())
        assertEquals(0, inputsBuilt)
    }

    private object UnconfinedDispatchers : T1dmDispatchers {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val inference = Dispatchers.Unconfined
        override val game = Dispatchers.Unconfined
    }

    private object NoHistory : BgHistoryProvider {
        override suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries? = null
        override suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries? = null
    }

    private object NoPredictions : PredictionStore {
        override suspend fun persist(cycleTsMs: Long, predictions: List<ModelPrediction>) = Unit
        override suspend fun loadLast(): List<ModelPrediction>? = null
    }
}
