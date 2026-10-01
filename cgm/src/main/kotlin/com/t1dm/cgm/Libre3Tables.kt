package com.t1dm.cgm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * PLAN_T1DMDROID.md §9 step 4: the manifest gate on the pushed tables dir
 * (`getExternalFilesDir(null)/libre3/tables`). Every pairing attempt verifies first: each
 * manifest-listed file must exist with the recorded size and sha256; anything missing or
 * mismatched is a [Libre3Call.Failed] naming the offending file — pairing refused, UI state
 * names the failure. Fail closed, never coerce. No caching: the dir is the app's to change.
 */
object Libre3Tables {

    fun verify(tablesDir: String): Libre3Call<Unit> = try {
        verifyOrThrow(tablesDir)
        Libre3Call.Ok(Unit)
    } catch (e: TablesRefused) {
        Libre3Call.Failed(e.reason)
    } catch (e: Exception) {
        // An unreadable dir or undecodable manifest is the same verdict: refuse, name the cause.
        Libre3Call.Failed("tables: ${e.message ?: e.javaClass.simpleName}")
    }

    private class TablesRefused(val reason: String) : Exception(reason)

    private fun verifyOrThrow(tablesDir: String) {
        val dir = File(tablesDir)
        if (!dir.isDirectory) throw TablesRefused("tables dir is missing: $tablesDir")
        val manifest = File(dir, MANIFEST_NAME)
        if (!manifest.isFile) throw TablesRefused("$MANIFEST_NAME is missing")
        val root = Json.parseToJsonElement(manifest.readText()) as? JsonObject
            ?: throw TablesRefused("$MANIFEST_NAME is not a JSON object")
        val files = root["files"] as? JsonArray
            ?: throw TablesRefused("$MANIFEST_NAME has no files array")
        if (files.isEmpty()) throw TablesRefused("$MANIFEST_NAME lists no files")
        for (entry in files) {
            val o = entry as? JsonObject ?: throw TablesRefused("files entry is not an object")
            val name = (o["name"] as? JsonPrimitive)?.content
                ?: throw TablesRefused("a files entry has no name")
            val size = (o["size"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: throw TablesRefused("$name has no size")
            val sha256 = (o["sha256"] as? JsonPrimitive)?.content
                ?: throw TablesRefused("$name has no sha256")
            verifyOne(dir, name, size, sha256)
        }
    }

    private fun verifyOne(dir: File, name: String, size: Long, sha256: String) {
        val file = File(dir, name)
        if (!file.isFile) throw TablesRefused("$name is missing")
        val actualSize = file.length()
        if (actualSize != size) throw TablesRefused("$name size $actualSize != manifest $size")
        val actual = sha256(file)
        if (!actual.contentEquals(digestOf(sha256))) throw TablesRefused("$name sha256 mismatch")
    }

    /** Streams the file; nothing held beyond the digest buffer, whatever the region size. */
    private fun sha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    /** Lowercased digest of the manifest's hex, itself case-insensitive. */
    private fun digestOf(hex: String): ByteArray {
        val clean = hex.trim().lowercase()
        if (clean.length != SHA256_HEX_LEN || !clean.all { it.isDigit() || it in 'a'..'f' }) {
            throw TablesRefused("sha256 is not a $SHA256_HEX_LEN-char hex string")
        }
        return ByteArray(SHA256_BYTES) { i ->
            ((Character.digit(clean[2 * i], 16) shl 4) or Character.digit(clean[2 * i + 1], 16)).toByte()
        }
    }

    private const val MANIFEST_NAME = "manifest.json"

    private const val SHA256_HEX_LEN = 64
    private const val SHA256_BYTES = 32

    /** Hashing buffer; sbox_12bit_full.bin is 2 MiB and must never be held whole. */
    private const val BUFFER_BYTES = 64 * 1024
}
