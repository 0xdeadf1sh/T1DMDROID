package com.t1dm.cgm

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §9 step 4: the manifest gate — ok, and every way it must refuse with the offending name. */
class Libre3TablesTest {

    private class Entry(val name: String, val bytes: ByteArray) {
        val sha256: String = sha(bytes)
    }

    private fun dir(entries: List<Entry>, manifest: String?): File {
        val dir = Files.createTempDirectory("libre3-tables").toFile()
        dir.deleteOnExit()
        for (e in entries) File(dir, e.name).writeBytes(e.bytes)
        if (manifest != null) File(dir, "manifest.json").writeText(manifest)
        return dir
    }

    private fun manifest(vararg entries: Entry): String = entries.joinToString(
        separator = ",",
        prefix = "{\"files\":[",
        postfix = "]}",
    ) { e -> """{"name":"${e.name}","size":${e.bytes.size},"sha256":"${e.sha256}"}""" }

    @Test
    fun `a complete dir with matching manifest verifies`() {
        val entries = listOf(Entry("sbox_12bit_full.bin", ByteArray(64)), Entry("phone_cert_162b.bin", ByteArray(162)))
        val outcome = Libre3Tables.verify(dir(entries, manifest(*entries.toTypedArray())).absolutePath)
        assertEquals(Libre3Call.Ok(Unit), outcome)
    }

    @Test
    fun `a missing file is named`() {
        val good = Entry("decode_table_lib_237dcc.bin", ByteArray(32))
        val manifest = manifest(good, Entry("missing_table.bin", ByteArray(8)))
        val dir = dir(listOf(good), manifest)
        val outcome = Libre3Tables.verify(dir.absolutePath)
        assertTrue(outcome is Libre3Call.Failed)
        assertTrue((outcome as Libre3Call.Failed).reason.contains("missing_table.bin"))
    }

    @Test
    fun `a size mismatch is named`() {
        val bytes = ByteArray(48)
        val onDisk = bytes + ByteArray(1) // one byte more than the manifest claims
        val dir = dir(listOf(Entry("params_lib_22a1a0.bin", onDisk)), manifest(Entry("params_lib_22a1a0.bin", bytes)))
        val outcome = Libre3Tables.verify(dir.absolutePath)
        assertTrue(outcome is Libre3Call.Failed)
        assertTrue((outcome as Libre3Call.Failed).reason.contains("params_lib_22a1a0.bin"))
        assertTrue((outcome as Libre3Call.Failed).reason.contains("size"))
    }

    @Test
    fun `a hash mismatch is named`() {
        val entry = Entry("phone_cert_162b.bin", ByteArray(162))
        val flipped = entry.sha256.mapIndexed { i, c -> if (i == 0) if (c == '0') '1' else '0' else c }
            .joinToString("")
        val dir = dir(listOf(entry), manifest(entry).replace(entry.sha256, flipped))
        val outcome = Libre3Tables.verify(dir.absolutePath)
        assertTrue(outcome is Libre3Call.Failed)
        assertTrue((outcome as Libre3Call.Failed).reason.contains("phone_cert_162b.bin"))
        assertTrue((outcome as Libre3Call.Failed).reason.contains("sha256"))
    }

    @Test
    fun `uppercase manifest hex verifies`() {
        val entry = Entry("phone_cert_162b.bin", ByteArray(162))
        val upper = manifest(entry).replace(entry.sha256, entry.sha256.uppercase())
        val outcome = Libre3Tables.verify(dir(listOf(entry), upper).absolutePath)
        assertEquals(Libre3Call.Ok(Unit), outcome)
    }

    @Test
    fun `a dir without a manifest is refused`() {
        val entry = Entry("phone_cert_162b.bin", ByteArray(162))
        val outcome = Libre3Tables.verify(dir(listOf(entry), manifest = null).absolutePath)
        assertTrue(outcome is Libre3Call.Failed)
        assertTrue((outcome as Libre3Call.Failed).reason.contains("manifest"))
    }

    @Test
    fun `a malformed manifest is refused`() {
        val outcome = Libre3Tables.verify(dir(emptyList(), manifest = "{not json").absolutePath)
        assertTrue(outcome is Libre3Call.Failed)
    }

    @Test
    fun `an empty files array is refused`() {
        val outcome = Libre3Tables.verify(dir(emptyList(), "{\"files\":[]}").absolutePath)
        assertTrue(outcome is Libre3Call.Failed)
        assertTrue((outcome as Libre3Call.Failed).reason.contains("lists no files"))
    }

    @Test
    fun `a missing dir is refused`() {
        val outcome = Libre3Tables.verify("/no/such/libre3/tables")
        assertTrue(outcome is Libre3Call.Failed)
        assertTrue((outcome as Libre3Call.Failed).reason.contains("missing"))
    }

    private companion object {
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
