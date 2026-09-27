package com.t1dm.inference

import com.t1dm.core.model.InferenceCause
import com.t1dm.inference.backend.StubBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A descriptor with no loadable .pte forecasts nothing: not drawn, stored, pushed or alarmed. */
class StubForecastTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a missing pte publishes and stores nothing and the note names the file`() = runTest {
        val dir = tmp.newFolder("models")
        seedModel(dir, "a", pte = false)
        val store = RecordingPredictions()
        val controller = controller(dir, store)
        controller.refreshModels()

        controller.runCycle(InferenceCause.MANUAL, flatSeries(), FIXTURE_NOW)

        val s = controller.state.value
        assertTrue(s.predictions.isEmpty())
        assertTrue(store.persisted.isEmpty())
        assertFalse(s.realBackendAvailable)
        assertEquals("no forecast — a.xnnpack.pte missing", s.note)
    }

    @Test
    fun `a pte that will not load is named as such`() = runTest {
        val dir = tmp.newFolder("models")
        seedModel(dir, "a")
        val controller = controller(dir, RecordingPredictions())
        controller.refreshModels()

        assertEquals("no forecast — a.xnnpack.pte won't load", controller.state.value.note)
    }

    @Test
    fun `a fill on a stub-served model is refused`() = runTest {
        val dir = tmp.newFolder("models")
        seedModel(dir, "a", pte = false)
        val controller = controller(dir, RecordingPredictions())
        controller.refreshModels()
        val series = flatSeries()

        val err = runCatching {
            controller.runMasked(
                "a", series, ModelChannels.zero(series.mgdl.size), null, emptyList(),
                withForecast = false, lora = null, synthetic = false,
            )
        }.exceptionOrNull()

        assertTrue("$err", err is IllegalStateException)
    }

    @Test
    fun `the stub runs nothing`() {
        val err = runCatching { StubBackend().run(StubBackend().load(fixtureDescriptor(), File("x")), emptyTensors()) }
            .exceptionOrNull()
        assertTrue("$err", err is IllegalStateException)
    }

    private fun controller(dir: File, store: PredictionStore) = InferenceController(
        native = DescriptorCore(),
        dispatchers = FixtureDispatchers(),
        store = ModelStore(dir, DescriptorCore()),
        history = EmptyHistory,
        predictionStore = store,
    )
}
