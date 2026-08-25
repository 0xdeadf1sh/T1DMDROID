package com.t1dm.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Reconciles the server's model registry against [modelsDir]. A download lands `.part` → fsync →
 * verify → rename, `.pte` first and descriptor last, so a model becomes discoverable only once both
 * files are present. An update to a RUNNING model is staged under [PENDING_DIR], never swapped.
 */
class ModelSyncCoordinator(
    private val modelsDir: File,
    private val http: SyncHttpClient,
    /** `.pte` FILENAMES, NOT descriptor ids: an adb-pushed model's descriptor `id` omits the engine
     *  infix, so an id-keyed check would miss it and swap the dosing model. */
    private val runningArtifacts: suspend () -> Set<String> = { emptySet() },
    private val sha256Hex: (ByteArray) -> String = ::defaultSha256,
) {
    /** Serialises whole passes: two would race on the shared `.part` staging path. Also guards
     *  [applyPending]. */
    private val mutex = Mutex()

    suspend fun sync(): ModelSyncSummary = mutex.withLock {
        val running = runningArtifacts()
        val rows = http.listModels()
        ModelSyncSummary(rows.mapNotNull { row -> processRow(row, running) })
    }

    suspend fun applyPending(localId: String): Boolean = mutex.withLock {
        val pending = File(modelsDir, PENDING_DIR)
        val pteName = "$localId.pte"
        val descName = "$localId.descriptor.json"
        val stagedPte = File(pending, pteName)
        val stagedDesc = File(pending, descName)
        if (!stagedPte.exists() || !stagedDesc.exists()) return@withLock false
        atomicRename(stagedPte, File(modelsDir, pteName))      // .pte FIRST
        atomicRename(stagedDesc, File(modelsDir, descName))    // descriptor LAST — the commit point
        true
    }

    fun pendingModelIds(): Set<String> {
        val pending = File(modelsDir, PENDING_DIR)
        val descs = pending.listFiles { f -> f.isFile && f.name.endsWith(".descriptor.json") } ?: return emptySet()
        return descs.mapNotNull { d ->
            val localId = d.name.removeSuffix(".descriptor.json")
            localId.takeIf { File(pending, "$it.pte").exists() }
        }.toSet()
    }

    private suspend fun processRow(row: ModelDto, running: Set<String>): ModelSyncOutcome? {
        if (!row.id.endsWith(".pte")) return null

        val meta = row.meta as? JsonObject
            ?: return skip(row.id, "meta null/invalid")

        val engine = (meta["engine"] as? JsonPrimitive)?.contentOrNull ?: DEFAULT_ENGINE
        if (engine.lowercase() !in SUPPORTED_ENGINES) return skip(row.id, "unsupported engine $engine")

        // A cheap PRE-check, not the contract: the core's parse also requires the exercise channel,
        // the geometry block and the risk transform, and skips at discovery with a logged reason.
        if (meta["normalization_stats"] !is JsonObject) return skip(row.id, "descriptor missing normalization_stats")

        val name = row.id.removeSuffix(".pte")
        val pteName = "$name.pte"
        val descName = "$name.descriptor.json"

        return runCatching { reconcile(row, meta, name, pteName, descName, running) }
            .getOrElse { e ->
                Timber.tag(TAG).w(e, "model %s sync failed", row.id)
                ModelSyncOutcome.Failed(row.id, e.message ?: e::class.simpleName ?: "error")
            }
    }

    private suspend fun reconcile(
        row: ModelDto,
        meta: JsonObject,
        name: String,
        pteName: String,
        descName: String,
        running: Set<String>,
    ): ModelSyncOutcome {
        // Matching bytes still need the descriptor pair, so a lone `.pte` becomes discoverable.
        val livePte = File(modelsDir, pteName)
        if (livePte.exists() && row.sha256.isNotBlank() &&
            sha256Hex(livePte.readBytes()).equals(row.sha256, ignoreCase = true)
        ) {
            if (!File(modelsDir, descName).exists()) writeDescriptor(modelsDir, descName, meta, name, pteName)
            return ModelSyncOutcome.AlreadyCurrent(row.id)
        }

        val art = http.downloadModel(row.id)
        val expected = art.sha256?.ifBlank { null } ?: row.sha256.ifBlank { null }

        // Stage only when this would overwrite a LIVE artifact a running model is loaded from. A new
        // model, or one with no live `.pte`, has nothing to swap and is applied in place.
        val isRunning = livePte.exists() && pteName in running
        val destDir = if (isRunning) File(modelsDir, PENDING_DIR) else modelsDir
        destDir.mkdirs()

        val part = File(destDir, "$pteName.part")
        writeAndSync(part, art.bytes)
        val actual = sha256Hex(art.bytes)
        if (expected == null || !actual.equals(expected, ignoreCase = true)) {
            part.delete()
            val why = if (expected == null) "missing content hash" else "sha mismatch"
            Timber.tag(TAG).w("model %s %s (got %s, expected %s); discarded", row.id, why, actual, expected)
            return ModelSyncOutcome.Failed(row.id, why)
        }

        atomicRename(part, File(destDir, pteName))                 // .pte FIRST
        writeDescriptor(destDir, descName, meta, name, pteName)    // descriptor LAST — the commit point

        return if (isRunning) ModelSyncOutcome.UpdateDownloaded(row.id) else ModelSyncOutcome.FetchedNew(row.id)
    }

    /** The served meta verbatim, but `id` normalized to the LOGICAL id ([logicalIdOf]) so a model's
     *  backend variants group as one. The running-set/dosing identity keys on the `.pte` filename
     *  (see [runningArtifacts]), never this id. */
    private fun writeDescriptor(dir: File, descName: String, meta: JsonObject, name: String, pteName: String) {
        val normalized = JsonObject(meta + mapOf("id" to JsonPrimitive(logicalIdOf(name)), "artifact" to JsonPrimitive(pteName)))
        val part = File(dir, "$descName.part")
        writeAndSync(part, normalized.toString().toByteArray(Charsets.UTF_8))
        atomicRename(part, File(dir, descName))
    }

    private fun logicalIdOf(name: String): String =
        if (name.substringAfterLast('.', "") in ENGINE_INFIXES) name.substringBeforeLast('.') else name

    private fun writeAndSync(dest: File, bytes: ByteArray) {
        FileOutputStream(dest).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
    }

    private fun atomicRename(src: File, dest: File) {
        Files.move(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun skip(id: String, reason: String): ModelSyncOutcome {
        Timber.tag(TAG).i("model %s skipped: %s", id, reason)
        return ModelSyncOutcome.Skipped(id, reason)
    }

    companion object {
        const val TAG = "ModelSync"

        /** ModelStore's files-only scan never sees a subdir. */
        const val PENDING_DIR = "pending"

        /** Matches `ModelStore.bundleOf`'s default when a descriptor omits `engine`. */
        private const val DEFAULT_ENGINE = "executorch_xnnpack_fp32"

        /** Filename infixes (`<logicalId>.<infix>.pte`), distinct from the descriptor `engine`. */
        private val ENGINE_INFIXES = setOf("xnnpack", "vulkan", "neuron", "litert_npu", "npu")

        /** The engine strings `ModelStore.backendOf` recognizes; anything else has no backend. */
        private val SUPPORTED_ENGINES = setOf(
            "executorch_xnnpack_fp32", "executorch_xnnpack",
            "executorch_neuron_fp16", "executorch_neuron",
            "litert_neuron_fp16", "litert_neuron",
            "litert_npu_fp32", "litert_npu_fp16", "litert_npu",
            "executorch_vulkan_fp16",
            "executorch_vulkan_fp32", "executorch_vulkan",
        )
    }
}

/** Lowercase hex. */
fun defaultSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

sealed interface ModelSyncOutcome {
    val id: String

    data class AlreadyCurrent(override val id: String) : ModelSyncOutcome

    data class FetchedNew(override val id: String) : ModelSyncOutcome

    /** Staged, awaiting a manual apply: the dosing model is never silently swapped. */
    data class UpdateDownloaded(override val id: String) : ModelSyncOutcome

    /** A non-`.pte` row is dropped silently instead, with no outcome at all. */
    data class Skipped(override val id: String, val reason: String) : ModelSyncOutcome

    data class Failed(override val id: String, val reason: String) : ModelSyncOutcome
}

data class ModelSyncSummary(val outcomes: List<ModelSyncOutcome> = emptyList()) {
    val fetchedNew: List<String> get() = outcomes.filterIsInstance<ModelSyncOutcome.FetchedNew>().map { it.id }
    val updatesPendingApply: List<String>
        get() = outcomes.filterIsInstance<ModelSyncOutcome.UpdateDownloaded>().map { it.id }
    val alreadyCurrent: List<String> get() = outcomes.filterIsInstance<ModelSyncOutcome.AlreadyCurrent>().map { it.id }
    val skipped: List<Pair<String, String>>
        get() = outcomes.filterIsInstance<ModelSyncOutcome.Skipped>().map { it.id to it.reason }
    val failed: List<Pair<String, String>>
        get() = outcomes.filterIsInstance<ModelSyncOutcome.Failed>().map { it.id to it.reason }
}
