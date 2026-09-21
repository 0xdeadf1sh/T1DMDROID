package com.t1dm.inference

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.DescriptorParse
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Model files present and none usable is a refusal, not an empty models dir. */
class RefusedModelNoteTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a refused descriptor is named with its reason, not reported as no model`() = runTest {
        val dir = tmp.newFolder("models")
        File(dir, "wide.descriptor.json").writeText(FIVE_FEATURE_DESCRIPTOR)
        File(dir, "wide.xnnpack.pte").writeText("pte-bytes")

        val controller = controller(dir, RefusingCore(CRATE_REASON))
        controller.refreshModels()

        val note = controller.state.value.note
        assertNotNull(note)
        assertTrue(note!!, note.contains("wide.descriptor.json"))
        assertTrue(note, note.contains("n_input_features 5 != 4"))
        assertFalse(note, note.contains("no model"))
    }

    @Test
    fun `an empty models dir still reports no model`() = runTest {
        val controller = controller(tmp.newFolder("models"), RefusingCore(CRATE_REASON))
        controller.refreshModels()

        assertEquals(
            "no model — adb push a .pte and its descriptor.json",
            controller.state.value.note,
        )
    }

    private fun controller(dir: File, native: StubNativeCore) = InferenceController(
        native = native,
        dispatchers = UnconfinedDispatchers,
        store = ModelStore(dir, native),
        history = NoHistory,
        predictionStore = NoPredictions,
    )

    /** Host tests load no .so, so the crate's refusal text is supplied rather than produced. */
    private class RefusingCore(private val reason: String) : StubNativeCore() {
        override fun parseDescriptor(json: String): ModelDescriptor? = null
        override fun parseDescriptorOrRefusal(json: String) = DescriptorParse(null, reason)
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
        override suspend fun loadLast(): List<ModelPrediction> = emptyList()
    }

    private companion object {
        const val CRATE_REASON =
            "n_input_features 5 != 4; this build reads the four-feature masked-BG input and " +
                "cannot run another architecture"

        const val FIVE_FEATURE_DESCRIPTOR =
            """{"id":"wide","artifact":"wide.xnnpack.pte","arch_version":"risk-v5",""" +
                """"geometry":{"N_INPUT_FEATURES":5,"PATCH_SIZE":6,"PATCH_DIM":30}}"""
    }
}
