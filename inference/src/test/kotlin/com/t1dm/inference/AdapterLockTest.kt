package com.t1dm.inference

import com.t1dm.core.common.NativeHead
import com.t1dm.core.model.HeadSpec
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.inference.backend.GraphOutput
import com.t1dm.inference.backend.GraphTensors
import com.t1dm.inference.backend.LoadedModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The head holds one adapter slot; a forward on the queue while the dose path fills it races. */
class AdapterLockTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the dose path sets no adapter while another forward holds the queue`() = runBlocking<Unit> {
        val dir = tmp.newFolder("models")
        seedModel(dir, "a")
        File(dir, HEAD_FILE).writeBytes(ByteArray(4))
        val inRun = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val head = RecordingHead(inRun)
        val scope = CoroutineScope(Dispatchers.Default)
        val inference = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        lateinit var controller: InferenceController
        lateinit var info: InferenceController.SelectedModelInfo
        var rival: Job? = null

        val core = object : DescriptorCore(fixtureDescriptor(fixtureHeadSpec(HEAD_FILE))) {
            override fun headOpen(bytes: ByteArray, spec: HeadSpec): NativeHead = head

            override fun stepStates(
                desc: ModelDescriptor,
                hidden: List<Float>,
                slotPatch: List<Int>,
                attnMask: List<Float>,
            ): List<Double> {
                // Unlocked here, this forward would be inside the backend when the adapter is set.
                if (rival == null) rival = scope.launch { controller.runSelectedAuthority(info, emptyTensors()) }
                entered.await(500, TimeUnit.MILLISECONDS)
                return List(SLOTS) { 0.0 }
            }
        }
        controller = InferenceController(
            native = core,
            dispatchers = FixtureDispatchers(inference = inference, default = Dispatchers.Default),
            store = ModelStore(dir, core),
            history = EmptyHistory,
            predictionStore = RecordingPredictions(),
            loraStore = LoraStore { FIXTURE_ADAPTER },
        )
        controller.registerBackend(object : FakeXnnpack() {
            override fun run(m: LoadedModel, x: GraphTensors): GraphOutput {
                inRun.set(true)
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                inRun.set(false)
                return super.run(m, x)
            }
        })
        controller.refreshModels()
        info = controller.authorityModelInfo()!!

        val adapted = controller.adaptedHeadRawFor(
            info,
            GraphOutput(FloatArray(SLOTS), hidden = FloatArray(SLOTS)),
            emptyGraphInput(),
        )
        release.countDown()
        rival?.join()
        inference.close()

        assertNotNull(adapted)
        assertTrue(head.sets > 0)
        assertEquals(0, head.setsDuringForward)
    }

    private class RecordingHead(private val inRun: AtomicBoolean) : NativeHead {
        @Volatile var sets = 0
        @Volatile var setsDuringForward = 0
        private var lora: LoraWeights? = null

        override fun setLora(w: LoraWeights?) {
            sets++
            if (inRun.get()) setsDuringForward++
            lora = w
        }

        override fun hasLora(): Boolean = lora != null

        override fun forward(stepStates: List<Double>, nSlots: Int): List<Double> = List(stepStates.size) { 0.0 }

        override fun close() = Unit
    }

    private companion object {
        const val HEAD_FILE = "a.head.bin"
        const val SLOTS = 4
    }
}
