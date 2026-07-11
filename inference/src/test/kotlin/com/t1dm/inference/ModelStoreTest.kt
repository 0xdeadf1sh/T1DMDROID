package com.t1dm.inference

import com.t1dm.core.nativecore.StubNativeCore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Bootstraps the :inference host-JVM test source set around [ModelStore.delete]. [delete] never
 * touches the Rust parser (it resolves ids straight off the descriptor JSON), so a bare
 * [StubNativeCore] suffices — the point is the on-disk sweep: EVERY descriptor + `.pte` pair sharing
 * the deleted id goes (a model may ship under several backend variants under one id), the other
 * model's pair survives, and an id that names nothing returns false.
 */
class ModelStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Write a `<name>.descriptor.json` carrying [id] + [artifact], and touch the artifact beside it. */
    private fun seed(dir: File, name: String, id: String, artifact: String) {
        File(dir, "$name.descriptor.json").writeText("""{"id":"$id","artifact":"$artifact"}""")
        File(dir, artifact).writeText("pte-bytes")
    }

    @Test
    fun delete_removes_only_the_matching_id_including_all_variants() {
        val dir = tmp.newFolder("models")
        // idA ("alpha") ships two backend variants sharing the id; idB ("beta") is unrelated.
        seed(dir, "alpha.xnnpack", id = "alpha", artifact = "alpha.xnnpack.pte")
        seed(dir, "alpha.neuron", id = "alpha", artifact = "alpha.neuron.pte")
        seed(dir, "beta", id = "beta", artifact = "beta.xnnpack.pte")

        val store = ModelStore(dir, StubNativeCore())

        assertTrue(store.delete("alpha"))

        assertFalse(File(dir, "alpha.xnnpack.descriptor.json").exists())
        assertFalse(File(dir, "alpha.xnnpack.pte").exists())
        assertFalse(File(dir, "alpha.neuron.descriptor.json").exists())
        assertFalse(File(dir, "alpha.neuron.pte").exists())
        // idB's pair is untouched.
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
}
