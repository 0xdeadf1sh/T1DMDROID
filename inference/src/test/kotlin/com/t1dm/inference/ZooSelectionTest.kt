package com.t1dm.inference

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SPEC/zoo.md §5: a zoo model can be the displayed forecast, never the dosing authority. */
class ZooSelectionTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a selected zoo forecast leads the fan and dosing has no authority`() = runTest {
        val controller = controller()
        controller.refreshModels()
        controller.onZoo(setOf("zoo-x"), listOf(prediction("zoo-x")))

        controller.selectModel("zoo-x")
        controller.onZoo(setOf("zoo-x"), listOf(prediction("zoo-x")))

        val state = controller.state.value
        assertEquals("zoo-x", state.selectedModelId)
        assertEquals("zoo-x", state.selectedPrediction?.modelId)
        assertEquals(1, state.predictions.count { it.selected })
        assertNull(controller.authorityModelInfo())
    }

    @Test
    fun `a removed zoo selection falls back to a transformer`() = runTest {
        val controller = controller()
        controller.refreshModels()
        controller.onZoo(setOf("zoo-x"), emptyList())
        controller.selectModel("zoo-x")

        controller.onZoo(emptySet(), emptyList())

        assertEquals("a", controller.state.value.selectedModelId)
    }

    @Test
    fun `a stored zoo choice survives the transformer discovery that precedes the zoo`() = runTest {
        val controller = controller(stored = "zoo-x")
        controller.refreshModels()
        controller.onZoo(setOf("zoo-x"), listOf(prediction("zoo-x")))

        assertEquals("zoo-x", controller.state.value.selectedModelId)
    }

    private fun controller(stored: String? = null): InferenceController {
        val dir = tmp.newFolder("models")
        seedModel(dir, "a")
        return InferenceController(
            native = DescriptorCore(),
            dispatchers = FixtureDispatchers(),
            store = ModelStore(dir, DescriptorCore()),
            history = EmptyHistory,
            predictionStore = RecordingPredictions(),
            selectionStore = object : SelectionStore {
                override suspend fun load(): String? = stored
                override suspend fun save(id: String) {}
            },
        )
    }

    private fun prediction(id: String) = ModelPrediction(
        modelId = id,
        cycleTsMs = FIXTURE_NOW,
        anchorTsMs = FIXTURE_NOW,
        stepMs = 300_000L,
        medianBg = listOf(120.0, 121.0),
        bandsMgdl = List(14) { 120.0 },
        nQuantiles = 7,
        lastBg = 119.0,
        status = ForecastStatus.OK,
        backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        selected = false,
        stale = false,
        latencyMs = 1.0,
    )
}
