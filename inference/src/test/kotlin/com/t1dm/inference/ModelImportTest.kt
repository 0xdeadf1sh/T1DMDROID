package com.t1dm.inference

import com.t1dm.core.model.DescriptorParse
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.nativecore.StubNativeCore
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

class ModelImportTest {

    @get:Rule val tmp = TemporaryFolder()

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

    private val descriptor = """{"id":"alpha","artifact":"alpha.xnnpack.pte"}"""

    @Test
    fun `a nested zip flattens into the models dir and discovers`() {
        val dir = tmp.newFolder("models")
        val store = ModelStore(dir, DescriptorCore())

        val result = store.importZip(
            zip(
                "export/alpha.descriptor.json" to descriptor,
                "export/alpha.xnnpack.pte" to "pte",
            ).inputStream(),
        )

        assertEquals(ModelImport.Imported("alpha"), result)
        assertEquals(listOf("alpha"), store.discover().map { it.id })
        assertEquals(setOf("alpha.descriptor.json", "alpha.xnnpack.pte"), dir.list()!!.toSet())
    }

    @Test
    fun `a missing artifact installs nothing`() {
        val dir = tmp.newFolder("models")
        val result = ModelStore(dir, DescriptorCore()).importZip(zip("alpha.descriptor.json" to descriptor).inputStream())

        assertEquals(ModelImport.Refused("alpha.xnnpack.pte missing from zip"), result)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun `a traversal entry lands inside the models dir`() {
        val root = tmp.newFolder("root")
        val dir = File(root, "models")
        val store = ModelStore(dir, DescriptorCore())

        store.importZip(
            zip(
                "../../alpha.descriptor.json" to descriptor,
                "../alpha.xnnpack.pte" to "pte",
            ).inputStream(),
        )

        assertEquals(setOf("models"), root.list()!!.toSet())
        assertTrue(File(dir, "alpha.xnnpack.pte").exists())
    }

    @Test
    fun `an installed id is refused and left untouched`() {
        val dir = tmp.newFolder("models")
        val store = ModelStore(dir, DescriptorCore())
        store.importZip(zip("alpha.descriptor.json" to descriptor, "alpha.xnnpack.pte" to "old").inputStream())

        val result = store.importZip(
            zip("other.descriptor.json" to descriptor, "alpha.xnnpack.pte" to "new").inputStream(),
        )

        assertEquals(ModelImport.Refused("alpha already installed"), result)
        assertEquals("old", File(dir, "alpha.xnnpack.pte").readText())
        assertFalse(File(dir, "other.descriptor.json").exists())
    }

    @Test
    fun `a refused descriptor reports the crate's first clause`() {
        val dir = tmp.newFolder("models")
        val core = object : StubNativeCore() {
            override fun parseDescriptor(json: String): ModelDescriptor? = null
            override fun parseDescriptorOrRefusal(json: String) =
                DescriptorParse(null, "n_input_features 5 != 4; see SPEC")
        }

        val result = ModelStore(dir, core).importZip(
            zip("alpha.descriptor.json" to descriptor, "alpha.xnnpack.pte" to "pte").inputStream(),
        )

        assertEquals(ModelImport.Refused("n_input_features 5 != 4"), result)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun `two descriptors are refused`() {
        val dir = tmp.newFolder("models")
        val result = ModelStore(dir, DescriptorCore()).importZip(
            zip("a.descriptor.json" to descriptor, "b.descriptor.json" to descriptor).inputStream(),
        )

        assertEquals(ModelImport.Refused("2 descriptors in zip (need 1)"), result)
    }
}
