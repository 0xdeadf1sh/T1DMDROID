package com.t1dm.inference

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** New files under an old id void what the old ones produced; nothing else does. */
class ArtifactReplaceTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a rewritten pte fires once, for its id only`() = runTest {
        val dir = models()
        val fired = mutableListOf<String>()
        val controller = controller(dir, MemoryLedger(), fired)
        controller.restoreLast(FIXTURE_NOW)
        controller.refreshModels()
        assertTrue("first sight only records", fired.isEmpty())

        rewrite(File(dir, "a.xnnpack.pte"))
        controller.refreshModels()
        controller.refreshModels()

        assertEquals(listOf("a"), fired)
        assertEquals(listOf("b"), controller.state.value.predictions.map { it.modelId })
    }

    @Test
    fun `a restart with unchanged files fires nothing`() = runTest {
        val dir = models()
        val ledger = MemoryLedger()
        val fired = mutableListOf<String>()
        controller(dir, ledger, fired).refreshModels()

        controller(dir, ledger, fired).refreshModels()

        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a pte removed and pushed back unchanged is no replacement`() = runTest {
        val dir = models()
        val fired = mutableListOf<String>()
        val controller = controller(dir, MemoryLedger(), fired)
        controller.refreshModels()
        val pte = File(dir, "a.xnnpack.pte")
        val bytes = pte.readBytes()
        val mtime = pte.lastModified()

        pte.delete()
        controller.refreshModels()
        pte.writeBytes(bytes)
        pte.setLastModified(mtime)
        controller.refreshModels()

        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a failed drop is retried on the next refresh`() = runTest {
        val dir = models()
        val fired = mutableListOf<String>()
        var failNext = true
        val controller = InferenceController(
            native = DescriptorCore(),
            dispatchers = FixtureDispatchers(),
            store = ModelStore(dir, DescriptorCore()),
            history = EmptyHistory,
            predictionStore = RecordingPredictions(),
            artifactLedger = MemoryLedger(),
            onArtifactReplaced = { id ->
                fired += id
                if (failNext) {
                    failNext = false
                    error("disk full")
                }
            },
        )
        controller.refreshModels()

        rewrite(File(dir, "a.xnnpack.pte"))
        controller.refreshModels()
        controller.refreshModels()

        assertEquals(listOf("a", "a"), fired)
    }

    private fun models(): File = tmp.newFolder("models").also {
        seedModel(it, "a")
        seedModel(it, "b")
    }

    private fun rewrite(pte: File) {
        val mtime = pte.lastModified()
        pte.writeText("a longer re-export")
        pte.setLastModified(mtime + 60_000L)
    }

    private fun controller(dir: File, ledger: ArtifactLedger, fired: MutableList<String>) = InferenceController(
        native = DescriptorCore(),
        dispatchers = FixtureDispatchers(),
        store = ModelStore(dir, DescriptorCore()),
        history = EmptyHistory,
        predictionStore = RecordingPredictions(listOf(prediction("a"), prediction("b"))),
        artifactLedger = ledger,
        onArtifactReplaced = { fired += it },
    )

    private class MemoryLedger : ArtifactLedger {
        private var all: Map<String, String> = emptyMap()

        override suspend fun load(): Map<String, String> = all

        override suspend fun save(all: Map<String, String>) {
            this.all = HashMap(all)
        }
    }

    private fun prediction(id: String) = ModelPrediction(
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
        selected = id == "a",
        stale = false,
        latencyMs = 12.0,
    )
}
