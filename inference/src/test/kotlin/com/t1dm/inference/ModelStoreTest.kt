package com.t1dm.inference

import com.t1dm.core.nativecore.StubNativeCore
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    /**
     * The exporter nests the checkpoint's risk transform under `kovatchev`; the Rust parser reads
     * it flat. Dropping it in the projection is invisible — the model still loads, still forecasts,
     * and decodes every risk value against the wrong scale.
     */
    @Test
    fun flatten_carries_the_kovatchev_block_through() {
        val store = ModelStore(tmp.newFolder("models"), StubNativeCore())
        val nested = JSONObject(
            """
            {
              "id": "large-real",
              "normalization_stats": {
                "bg_absolute": {"mean": 0.4285, "std": 1.0598},
                "carb_intake": {"mean": 0.3687, "std": 0.4765},
                "insulin_combined": {"mean": 0.1471, "std": 0.1180}
              },
              "geometry": {"MAX_CONTEXT_PATCHES": 48, "MIN_CONTEXT_PATCHES": 16,
                           "PATCH_SIZE": 6, "N_INPUT_FEATURES": 3},
              "constants": {"ROPE_BASE": 1000, "BG_HEAD_MEDIAN_GLOBAL_DIM": 6,
                            "BG_HEAD_STEP_BASIS_TYPE": "dct", "BG_QUANTILE_SPREAD_MIN": 0.001,
                            "neg_fill": -30000.0, "PREDICTION_HORIZON_HOURS": 2},
              "kovatchev": {"SCALE": 2.2211457449985317, "POWER": 1.084,
                            "OFFSET": 5.540076976170212,
                            "BG_CLAMP_MIN": 40.0, "BG_CLAMP_MAX": 400.0},
              "conformal": {"enabled": false}
            }
            """.trimIndent()
        )

        val k = store.flattenDescriptor(nested).getJSONObject("kovatchev")

        assertEquals(2.2211457449985317, k.getDouble("SCALE"), 0.0)
        assertEquals(1.084, k.getDouble("POWER"), 0.0)
        assertEquals(5.540076976170212, k.getDouble("OFFSET"), 0.0)
        assertEquals(40.0, k.getDouble("BG_CLAMP_MIN"), 0.0)
        assertEquals(400.0, k.getDouble("BG_CLAMP_MAX"), 0.0)
    }

    /** A descriptor that never declared a transform must not acquire a fabricated one here —
     *  the absent key is what makes the Rust parser reject it. */
    @Test
    fun flatten_does_not_invent_a_kovatchev_block() {
        val store = ModelStore(tmp.newFolder("models"), StubNativeCore())
        val nested = JSONObject(
            """{"normalization_stats": {}, "constants": {}, "geometry": {}}"""
        )
        assertFalse(store.flattenDescriptor(nested).has("kovatchev"))
    }

    /** An already-flat descriptor passes through untouched, block included. */
    @Test
    fun flatten_passes_a_flat_descriptor_through_unchanged() {
        val store = ModelStore(tmp.newFolder("models"), StubNativeCore())
        val flat = JSONObject(
            """{"rope_base": 1000, "kovatchev": {"SCALE": 1.509, "POWER": 1.084,
               "OFFSET": 5.381, "BG_CLAMP_MIN": 20.0, "BG_CLAMP_MAX": 500.0}}"""
        )
        assertEquals(1.509, store.flattenDescriptor(flat).getJSONObject("kovatchev").getDouble("SCALE"), 0.0)
    }
}
