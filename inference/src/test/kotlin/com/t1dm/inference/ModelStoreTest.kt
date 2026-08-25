package com.t1dm.inference

import com.t1dm.core.nativecore.StubNativeCore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun seed(dir: File, name: String, id: String, artifact: String) {
        File(dir, "$name.descriptor.json").writeText("""{"id":"$id","artifact":"$artifact"}""")
        File(dir, artifact).writeText("pte-bytes")
    }

    @Test
    fun delete_removes_only_the_matching_id_including_all_variants() {
        val dir = tmp.newFolder("models")
        seed(dir, "alpha.xnnpack", id = "alpha", artifact = "alpha.xnnpack.pte")
        seed(dir, "alpha.neuron", id = "alpha", artifact = "alpha.neuron.pte")
        seed(dir, "beta", id = "beta", artifact = "beta.xnnpack.pte")

        val store = ModelStore(dir, StubNativeCore())

        assertTrue(store.delete("alpha"))

        assertFalse(File(dir, "alpha.xnnpack.descriptor.json").exists())
        assertFalse(File(dir, "alpha.xnnpack.pte").exists())
        assertFalse(File(dir, "alpha.neuron.descriptor.json").exists())
        assertFalse(File(dir, "alpha.neuron.pte").exists())
        assertTrue(File(dir, "beta.descriptor.json").exists())
        assertTrue(File(dir, "beta.xnnpack.pte").exists())
    }

    @Test
    fun delete_absent_id_returns_false_and_disturbs_nothing() {
        val dir = tmp.newFolder("models")
        seed(dir, "beta", id = "beta", artifact = "beta.xnnpack.pte")

        val store = ModelStore(dir, StubNativeCore())

        assertFalse(store.delete("ghost"))
        assertTrue(File(dir, "beta.descriptor.json").exists())
        assertTrue(File(dir, "beta.xnnpack.pte").exists())
    }

    /** An orphaned head would pair with whatever next takes the id, every shape check passing. */
    @Test
    fun delete_takes_the_head_side_file_with_the_artifact() {
        val dir = tmp.newFolder("models")
        File(dir, "alpha.xnnpack.descriptor.json").writeText(
            """{"id":"alpha","artifact":"alpha.xnnpack.pte","head":{"file":"alpha.head.bin"}}"""
        )
        File(dir, "alpha.xnnpack.pte").writeText("pte-bytes")
        File(dir, "alpha.head.bin").writeText("head-bytes")

        assertTrue(ModelStore(dir, StubNativeCore()).delete("alpha"))

        assertFalse(File(dir, "alpha.head.bin").exists())
        assertFalse(File(dir, "alpha.xnnpack.pte").exists())
    }
}
