package com.t1dm.inference

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZooTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun descriptor(format: String = ZooDescriptor.FORMAT, zone: Int = 24, rows: Int = 7): String {
        val offsets = (0 until rows).joinToString(",", "[", "]") { i ->
            (0 until zone).joinToString(",", "[", "]") { "${(i - 3) * 5.0}" }
        }
        return """{"format":"$format","id":"zoo-ridge","artifact":"zoo-ridge.xnnpack.pte",
            "engine":"executorch_xnnpack_fp32","model":"ridge_arx","causal":true,"ctx_steps":72,
            "zone_steps":$zone,"gap_fill_interp_max_steps":6,"bg_clamp_mgdl":[1.0,400.0],
            "quantile_levels":[0.05,0.1,0.25,0.5,0.75,0.9,0.95],"band_offsets_mgdl":$offsets}"""
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun `a zoo descriptor parses to its geometry`() {
        val d = ZooDescriptor.parse(JSONObject(descriptor()))
        assertEquals(72, d.ctxSteps)
        assertEquals(24, d.zoneSteps)
        assertTrue(d.causal)
        assertEquals(0.0, d.bandOffsets[3][0], 0.0)
    }

    @Test
    fun `an unknown zoo version is claimed and refused`() {
        val obj = JSONObject(descriptor(format = "t1dm-zoo-2"))
        assertTrue(ZooDescriptor.claims(obj))
        val msg = runCatching { ZooDescriptor.parse(obj) }.exceptionOrNull()?.message
        assertEquals("format t1dm-zoo-2 not supported", msg)
    }

    @Test
    fun `a short offset row is refused`() {
        val msg = runCatching { ZooDescriptor.parse(JSONObject(descriptor(rows = 6))) }.exceptionOrNull()?.message
        assertEquals("band_offsets_mgdl has 6 rows", msg)
    }

    @Test
    fun `a zoo bundle imports beside the transformer path and discover skips it`() {
        val dir = tmp.newFolder("models")
        val store = ModelStore(dir, DescriptorCore())
        val result = store.importZip(
            zip("zoo-ridge.descriptor.json" to descriptor(), "zoo-ridge.xnnpack.pte" to "pte").inputStream(),
        )
        assertEquals(ModelImport.Imported("zoo-ridge"), result)
        assertTrue(File(dir, "zoo-ridge.xnnpack.pte").exists())
        assertTrue(store.discover().isEmpty())
        assertTrue(store.refusedDescriptors.isEmpty())
        val again = store.importZip(
            zip("zoo-ridge.descriptor.json" to descriptor(), "zoo-ridge.xnnpack.pte" to "pte").inputStream(),
        )
        assertEquals(ModelImport.Refused("zoo-ridge already installed"), again)
        assertTrue(store.delete("zoo-ridge"))
        assertFalse(File(dir, "zoo-ridge.xnnpack.pte").exists())
    }
}
