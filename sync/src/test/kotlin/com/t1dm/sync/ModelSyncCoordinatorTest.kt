package com.t1dm.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reconciliation logic for the model-fetch coordinator, exercised against a real temp [modelsDir]
 * and the real [defaultSha256]. A focused [FakeModelSyncClient] serves the registry + artifact bytes
 * and counts `downloadModel` calls, so the "already-current is a no-op" and "verify before place"
 * invariants are observable without the wire.
 */
class ModelSyncCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A [SyncHttpClient] that serves a scripted registry + per-id artifacts, counting downloads. */
    private class FakeModelSyncClient(
        private val rows: List<ModelDto>,
        private val artifacts: Map<String, ModelArtifact>,
    ) : NoopSyncHttpClient() {
        var downloadCount = 0
        override suspend fun listModels(): List<ModelDto> = rows
        override suspend fun downloadModel(id: String): ModelArtifact {
            downloadCount++
            return artifacts[id] ?: throw IllegalStateException("no scripted artifact for $id")
        }
    }

    private fun meta(engine: String = "executorch_xnnpack_fp32", artifact: String? = null): JsonObject =
        JsonObject(
            buildMap {
                put("engine", JsonPrimitive(engine))
                put("normalization_stats", JsonObject(emptyMap()))
                if (artifact != null) put("artifact", JsonPrimitive(artifact))
            },
        )

    private fun row(id: String, meta: JsonObject?, sha: String) =
        ModelDto(id = id, meta = meta, sha256 = sha)

    private fun coordinator(
        modelsDir: File,
        rows: List<ModelDto>,
        artifacts: Map<String, ModelArtifact>,
        running: Set<String> = emptySet(),
    ): Pair<ModelSyncCoordinator, FakeModelSyncClient> {
        val http = FakeModelSyncClient(rows, artifacts)
        // `running` is the loaded running set's `.pte` FILENAMES (== the registry ids), not descriptor ids.
        return ModelSyncCoordinator(modelsDir, http, runningArtifacts = { running }) to http
    }

    @Test
    fun newModel_writesDescriptorAndPte_pairsForDiscovery() = runBlocking {
        val dir = tmp.newFolder("models")
        val bytes = byteArrayOf(9, 8, 7, 6)
        val sha = defaultSha256(bytes)
        val (coord, http) = coordinator(
            dir,
            rows = listOf(row("m.pte", meta(), sha)),
            artifacts = mapOf("m.pte" to ModelArtifact(bytes, sha)),
        )

        val summary = coord.sync()

        assertEquals(listOf("m.pte"), summary.fetchedNew)
        assertEquals(1, http.downloadCount)
        assertArrayEquals(bytes, File(dir, "m.pte").readBytes())
        val desc = Json.parseToJsonElement(File(dir, "m.descriptor.json").readText()).jsonObject
        assertEquals("m.pte", desc["artifact"]!!.jsonPrimitive.content)  // paired for ModelStore
        assertEquals("m", desc["id"]!!.jsonPrimitive.content)            // deterministic local id
        assertFalse(File(dir, "m.pte.part").exists())                    // no leftover staging
    }

    @Test
    fun shaMismatch_rejected_noFinalPte_noPart() = runBlocking {
        val dir = tmp.newFolder("models")
        val bytes = byteArrayOf(1, 2, 3)
        val (coord, _) = coordinator(
            dir,
            rows = listOf(row("m.pte", meta(), "not-the-real-hash")),
            artifacts = mapOf("m.pte" to ModelArtifact(bytes, "deadbeef")), // X-SHA256 != hash(bytes)
        )

        val summary = coord.sync()

        assertEquals(listOf("m.pte" to "sha mismatch"), summary.failed)
        assertFalse(File(dir, "m.pte").exists())          // never placed where ModelStore loads it
        assertFalse(File(dir, "m.pte.part").exists())     // .part discarded
        assertFalse(File(dir, "m.descriptor.json").exists())
    }

    @Test
    fun metaNull_skips_nothingWritten() = runBlocking {
        val dir = tmp.newFolder("models")
        val (coord, http) = coordinator(
            dir,
            rows = listOf(row("m.pte", null, "sha")),
            artifacts = emptyMap(),
        )

        val summary = coord.sync()

        assertEquals(listOf("m.pte" to "meta null/invalid"), summary.skipped)
        assertEquals(0, http.downloadCount)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun unsupportedEngine_skips() = runBlocking {
        val dir = tmp.newFolder("models")
        val (coord, http) = coordinator(
            dir,
            rows = listOf(row("m.pte", meta(engine = "onnx_cuda"), "sha")),
            artifacts = emptyMap(),
        )

        val summary = coord.sync()

        assertEquals(listOf("m.pte" to "unsupported engine onnx_cuda"), summary.skipped)
        assertEquals(0, http.downloadCount)
    }

    @Test
    fun upToDate_sameSha_isNoOp_noDownload() = runBlocking {
        val dir = tmp.newFolder("models")
        val bytes = byteArrayOf(4, 5, 6, 7)
        val sha = defaultSha256(bytes)
        File(dir, "m.pte").writeBytes(bytes)
        File(dir, "m.descriptor.json").writeText("{}")
        val (coord, http) = coordinator(
            dir,
            rows = listOf(row("m.pte", meta(), sha)),
            artifacts = mapOf("m.pte" to ModelArtifact(bytes, sha)),
        )

        val summary = coord.sync()

        assertEquals(listOf("m.pte"), summary.alreadyCurrent)
        assertEquals(0, http.downloadCount) // the whole point: no re-fetch of an already-current model
    }

    @Test
    fun runningModelUpdate_stagesToPending_leavesLiveBytes() = runBlocking {
        val dir = tmp.newFolder("models")
        val oldBytes = byteArrayOf(1, 1, 1, 1)
        File(dir, "m.pte").writeBytes(oldBytes)
        File(dir, "m.descriptor.json").writeText("{}")
        val newBytes = byteArrayOf(2, 2, 2, 2)
        val newSha = defaultSha256(newBytes)
        val (coord, _) = coordinator(
            dir,
            rows = listOf(row("m.pte", meta(), newSha)),
            artifacts = mapOf("m.pte" to ModelArtifact(newBytes, newSha)),
            running = setOf("m.pte"),                             // the dosing model's loaded .pte filename
        )

        val summary = coord.sync()

        assertEquals(listOf("m.pte"), summary.updatesPendingApply)
        assertArrayEquals(oldBytes, File(dir, "m.pte").readBytes())        // live NOT swapped
        val pending = File(dir, ModelSyncCoordinator.PENDING_DIR)
        assertArrayEquals(newBytes, File(pending, "m.pte").readBytes())    // staged
        assertTrue(File(pending, "m.descriptor.json").exists())
        assertEquals(setOf("m"), coord.pendingModelIds())
    }

    @Test
    fun nonRunningUpdate_appliesInPlace() = runBlocking {
        val dir = tmp.newFolder("models")
        File(dir, "m.pte").writeBytes(byteArrayOf(1, 1, 1, 1))
        File(dir, "m.descriptor.json").writeText("{}")
        val newBytes = byteArrayOf(3, 3, 3, 3)
        val newSha = defaultSha256(newBytes)
        val (coord, _) = coordinator(
            dir,
            rows = listOf(row("m.pte", meta(), newSha)),
            artifacts = mapOf("m.pte" to ModelArtifact(newBytes, newSha)),
            running = emptySet(),
        )

        val summary = coord.sync()

        assertEquals(listOf("m.pte"), summary.fetchedNew)
        assertArrayEquals(newBytes, File(dir, "m.pte").readBytes())        // live replaced
        assertFalse(File(dir, ModelSyncCoordinator.PENDING_DIR).exists())
    }

    @Test
    fun applyPending_promotesStagedToLive() = runBlocking {
        val dir = tmp.newFolder("models")
        val oldBytes = byteArrayOf(1, 1, 1, 1)
        File(dir, "m.pte").writeBytes(oldBytes)
        File(dir, "m.descriptor.json").writeText("{}")
        val newBytes = byteArrayOf(2, 2, 2, 2)
        val newSha = defaultSha256(newBytes)
        val (coord, _) = coordinator(
            dir,
            rows = listOf(row("m.pte", meta(), newSha)),
            artifacts = mapOf("m.pte" to ModelArtifact(newBytes, newSha)),
            running = setOf("m.pte"),
        )
        coord.sync() // stages into pending/

        val applied = coord.applyPending("m")

        assertTrue(applied)
        assertArrayEquals(newBytes, File(dir, "m.pte").readBytes())        // now live
        assertTrue(coord.pendingModelIds().isEmpty())                     // staging consumed
        assertFalse(File(File(dir, ModelSyncCoordinator.PENDING_DIR), "m.pte").exists())
    }

    @Test
    fun applyPending_missingStage_returnsFalse() = runBlocking {
        val dir = tmp.newFolder("models")
        val (coord, _) = coordinator(dir, rows = emptyList(), artifacts = emptyMap())
        assertFalse(coord.applyPending("nope"))
    }

    @Test
    fun descriptorNormalizesArtifactFieldToOnDiskPte() = runBlocking {
        val dir = tmp.newFolder("models")
        val bytes = byteArrayOf(5, 5, 5)
        val sha = defaultSha256(bytes)
        // Served meta's `artifact` disagrees with the id — must be overwritten to the real filename.
        val (coord, _) = coordinator(
            dir,
            rows = listOf(row("m.pte", meta(artifact = "totally-different.pte"), sha)),
            artifacts = mapOf("m.pte" to ModelArtifact(bytes, sha)),
        )

        coord.sync()

        val desc = Json.parseToJsonElement(File(dir, "m.descriptor.json").readText()).jsonObject
        assertEquals("m.pte", desc["artifact"]!!.jsonPrimitive.content)
        assertTrue(File(dir, "m.pte").exists())
        assertFalse(File(dir, "totally-different.pte").exists())
    }

    @Test
    fun perModelFailure_doesNotAbortTheRest() = runBlocking {
        val dir = tmp.newFolder("models")
        val goodBytes = byteArrayOf(7, 7, 7)
        val goodSha = defaultSha256(goodBytes)
        val (coord, _) = coordinator(
            dir,
            rows = listOf(
                row("bad.pte", meta(), "wrong-hash"),         // sha mismatch → Failed
                row("good.pte", meta(), goodSha),             // proceeds regardless
            ),
            artifacts = mapOf(
                "bad.pte" to ModelArtifact(byteArrayOf(0), "deadbeef"),
                "good.pte" to ModelArtifact(goodBytes, goodSha),
            ),
        )

        val summary = coord.sync()

        assertEquals(listOf("bad.pte" to "sha mismatch"), summary.failed)
        assertEquals(listOf("good.pte"), summary.fetchedNew)
        assertArrayEquals(goodBytes, File(dir, "good.pte").readBytes())
    }

    @Test
    fun nonPteRows_droppedSilently() = runBlocking {
        val dir = tmp.newFolder("models")
        val (coord, http) = coordinator(
            dir,
            rows = listOf(row("readme.json", meta(), "sha")),
            artifacts = emptyMap(),
        )

        val summary = coord.sync()

        assertEquals(0, http.downloadCount)
        assertTrue(summary.outcomes.isEmpty())  // no outcome at all — not even a Skipped entry
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun runningIdentityIsArtifactFilename_adbPushedModelUpdate_stages_notSwapped() = runBlocking {
        // Regression (the review's HIGH finding): an adb-pushed running model has descriptor id
        // "t1dmai_best" but its artifact — and the server registry id — is "t1dmai_best.xnnpack.pte".
        // The running-set identity MUST be the .pte FILENAME; keying on the descriptor id would miss and
        // overwrite the live dosing model in place. Here the running set is the loaded .pte filename.
        val dir = tmp.newFolder("models")
        val id = "t1dmai_best.xnnpack.pte"
        val oldBytes = byteArrayOf(1, 1, 1)
        File(dir, id).writeBytes(oldBytes)
        File(dir, "t1dmai_best.xnnpack.descriptor.json").writeText("{}")
        val newBytes = byteArrayOf(2, 2, 2)
        val newSha = defaultSha256(newBytes)
        val (coord, _) = coordinator(
            dir,
            rows = listOf(row(id, meta(), newSha)),
            artifacts = mapOf(id to ModelArtifact(newBytes, newSha)),
            running = setOf(id),                              // the LOADED .pte filename, NOT "t1dmai_best"
        )

        val summary = coord.sync()

        assertEquals(listOf(id), summary.updatesPendingApply)
        assertArrayEquals(oldBytes, File(dir, id).readBytes())        // live dosing model NOT swapped
        assertArrayEquals(newBytes, File(File(dir, ModelSyncCoordinator.PENDING_DIR), id).readBytes())
    }

    @Test
    fun malformedDescriptor_missingNormalizationStats_skipped_nothingPlaced() = runBlocking {
        // A verified-but-unusable descriptor (no normalization_stats) must be rejected BEFORE it is
        // written, so it can never make ModelStore.discover() throw for the whole dir.
        val dir = tmp.newFolder("models")
        val badMeta = JsonObject(mapOf("engine" to JsonPrimitive("executorch_xnnpack_fp32")))
        val (coord, http) = coordinator(
            dir,
            rows = listOf(row("m.pte", badMeta, "sha")),
            artifacts = emptyMap(),
        )

        val summary = coord.sync()

        assertEquals(listOf("m.pte" to "descriptor missing normalization_stats"), summary.skipped)
        assertEquals(0, http.downloadCount)                          // rejected before any download
        assertEquals(0, dir.listFiles()!!.size)
    }
}
