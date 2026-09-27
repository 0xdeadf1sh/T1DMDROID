package com.t1dm.inference

import com.t1dm.core.common.NativeHead
import com.t1dm.core.model.HeadSpec
import com.t1dm.core.model.LoraWeights
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A head proved against one graph says nothing about the graph a reload brings in. */
class HeadReloadTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a reload closes the head and the next one owes parity`() = runTest {
        val dir = tmp.newFolder("models")
        seedModel(dir, "a")
        File(dir, HEAD_FILE).writeBytes(ByteArray(4))
        val opened = mutableListOf<CountingHead>()
        val core = object : DescriptorCore(fixtureDescriptor(fixtureHeadSpec(HEAD_FILE))) {
            override fun headOpen(bytes: ByteArray, spec: HeadSpec): NativeHead = CountingHead().also { opened += it }
        }
        val controller = InferenceController(
            native = core,
            dispatchers = FixtureDispatchers(),
            store = ModelStore(dir, core),
            history = EmptyHistory,
            predictionStore = RecordingPredictions(),
        )
        controller.refreshModels()
        val first = controller.headState("a") as HeadCache.State.Ready

        controller.refreshModels()
        val next = controller.headState("a") as HeadCache.State.Ready

        assertEquals(2, opened.size)
        assertTrue(opened[0].closed)
        assertNotSame(first.head, next.head)
        assertTrue(next.maxDelta.isNaN())
    }

    private class CountingHead : NativeHead {
        var closed = false

        override fun setLora(w: LoraWeights?) = Unit

        override fun hasLora(): Boolean = false

        override fun forward(stepStates: List<Double>, nSlots: Int): List<Double> = emptyList()

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val HEAD_FILE = "a.head.bin"
    }
}
