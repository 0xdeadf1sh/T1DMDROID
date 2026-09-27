package com.t1dm.inference

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A restart keeps the model the user chose, and the drawn fan names the same one. */
class SelectionPersistTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the stored choice wins over the first descriptor`() = runTest {
        val controller = controller(MemorySelection("b"))

        controller.refreshModels()

        assertEquals("b", controller.selectedModelInfo()?.id)
    }

    @Test
    fun `a restored fan is re-flagged to the resolved selection`() = runTest {
        val restored = listOf(prediction("b", selected = true), prediction("a", selected = false))
        val controller = controller(MemorySelection("a"), restored)
        controller.restoreLast(FIXTURE_NOW)

        controller.refreshModels()

        assertEquals("a", controller.state.value.selectedPrediction?.modelId)
        assertEquals(1, controller.state.value.predictions.count { it.selected })
    }

    @Test
    fun `selecting saves the choice`() = runTest {
        val store = MemorySelection(null)
        val controller = controller(store)
        controller.refreshModels()

        controller.selectModel("b")

        assertEquals("b", store.id)
    }

    private fun controller(store: SelectionStore, restored: List<ModelPrediction>? = null): InferenceController {
        val dir = tmp.newFolder("models")
        seedModel(dir, "a")
        seedModel(dir, "b")
        return InferenceController(
            native = DescriptorCore(),
            dispatchers = FixtureDispatchers(),
            store = ModelStore(dir, DescriptorCore()),
            history = EmptyHistory,
            predictionStore = RecordingPredictions(restored),
            selectionStore = store,
        )
    }

    private class MemorySelection(var id: String?) : SelectionStore {
        override suspend fun load(): String? = id

        override suspend fun save(id: String) {
            this.id = id
        }
    }

    private fun prediction(id: String, selected: Boolean) = ModelPrediction(
        modelId = id,
        cycleTsMs = FIXTURE_NOW,
        anchorTsMs = FIXTURE_NOW,
        stepMs = 300_000L,
        medianBg = listOf(120.0, 121.0),
        bandsMgdl = listOf(110.0, 130.0, 111.0, 131.0),
        nQuantiles = 2,
        lastBg = 119.0,
        status = ForecastStatus.OK,
        backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        selected = selected,
        stale = false,
        latencyMs = 12.0,
    )
}
