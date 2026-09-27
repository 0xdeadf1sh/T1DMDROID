package com.t1dm.inference

import com.t1dm.inference.backend.GraphOutput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A dose roll runs the model it was built for, or nothing: never another's graph on its input. */
class AuthorityIdentityTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a roll built for one model is refused once another is selected`() = runTest {
        val controller = controller()
        controller.refreshModels()
        val a = controller.authorityModelInfo()!!
        assertEquals("a", a.id)
        controller.runSelectedAuthority(a, emptyTensors())

        controller.selectModel("b")

        assertRefused { controller.runSelectedAuthority(a, emptyTensors()) }
        assertRefused { controller.adaptedHeadRawFor(a, GraphOutput(FloatArray(0)), emptyGraphInput()) }
    }

    @Test
    fun `a reload between rolls refuses the handle taken before it`() = runTest {
        val controller = controller()
        controller.refreshModels()
        val before = controller.authorityModelInfo()!!

        controller.refreshModels()

        assertRefused { controller.runSelectedAuthority(before, emptyTensors()) }
        assertRefused { controller.adaptedHeadRawFor(before, GraphOutput(FloatArray(0)), emptyGraphInput()) }
        controller.runSelectedAuthority(controller.authorityModelInfo()!!, emptyTensors())
    }

    private suspend fun assertRefused(call: suspend () -> Unit) {
        val err = runCatching { call() }.exceptionOrNull()
        assertTrue("$err", err is IllegalStateException)
    }

    private fun controller(): InferenceController {
        val dir = tmp.newFolder("models")
        seedModel(dir, "a")
        seedModel(dir, "b")
        return InferenceController(
            native = DescriptorCore(),
            dispatchers = FixtureDispatchers(),
            store = ModelStore(dir, DescriptorCore()),
            history = EmptyHistory,
            predictionStore = RecordingPredictions(),
            loraStore = LoraStore { FIXTURE_ADAPTER },
        ).apply { registerBackend(FakeXnnpack()) }
    }
}
