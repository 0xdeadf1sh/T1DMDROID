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
 * Fetches the server's model registry and reconciles it against [modelsDir] so a fresh export
 * becomes discoverable by `ModelStore.discover()` (the `.pte` + `<name>.descriptor.json` pair).
 * Pure JVM — no `:inference` dependency — so the "is this model running?" question is an injected
 * provider ([runningArtifacts]), keeping the coordinator inference-agnostic.
 *
 * Integrity (deliverable 4): a download is written to a `.part`, `fsync`'d, its SHA-256 verified
 * against the response `X-SHA256` (registry hash as fallback), and only then atomically renamed —
 * the `.pte` FIRST, the descriptor LAST — so a model becomes discoverable only once BOTH files are
 * present and the bytes are proven.
 *
 * Auto-download / manual-apply (product decision 2): a NEW or non-running model is applied straight
 * to [modelsDir]; an update to a model the app is CURRENTLY running is staged under [PENDING_DIR]
 * (invisible to ModelStore's non-recursive, files-only scan) and never silently swapped — the app
 * layer promotes it via [applyPending].
 *
 * Fail-closed: the whole-registry pass never throws on a per-model fault; each row is guarded and
 * captured as [ModelSyncOutcome.Failed], and the rest proceed. (A failure to fetch the registry
 * list itself does propagate, so the caller — which wraps `sync()` in `runCatching` — can report
 * "offline".)
 */
class ModelSyncCoordinator(
    private val modelsDir: File,
    private val http: SyncHttpClient,
    /** The on-disk `.pte` FILENAMES of the currently-loaded running set — NOT descriptor ids. This is the
     *  identity that actually answers "would this row overwrite a live model's bytes?": a server registry
     *  id IS the artifact filename, so comparing filenames is robust whether the running model was placed
     *  by this coordinator or adb-pushed with the canonical `<id>.xnnpack.pte` naming (whose descriptor
     *  `id` omits the engine infix, so an id-keyed check would miss it and swap the dosing model). */
    private val runningArtifacts: suspend () -> Set<String> = { emptySet() },
    private val sha256Hex: (ByteArray) -> String = ::defaultSha256,
) {
    /** Serialises whole passes: the startup, profile-saved, and manual-button triggers can all fire, and
     *  two passes must never race on the shared `.part` staging path (an interleaved write could be
     *  renamed live despite its in-memory-buffer hash "passing"). Also guards [applyPending]. */
    private val mutex = Mutex()

    /** One pass: list the registry, then per `.pte` row download/verify/write or stage/skip. */
    suspend fun sync(): ModelSyncSummary = mutex.withLock {
        val running = runningArtifacts()
        val rows = http.listModels()
        ModelSyncSummary(rows.mapNotNull { row -> processRow(row, running) })
    }

    /**
     * Promote a staged update (from [PENDING_DIR]) into the live [modelsDir] — the app layer's
     * manual apply. `.pte` first, descriptor last; the staged copies are consumed by the rename.
     * Returns false if no complete staged pair exists for [localId].
     */
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

    /** Local ids with a complete staged update awaiting a manual [applyPending]. */
    fun pendingModelIds(): Set<String> {
        val pending = File(modelsDir, PENDING_DIR)
        val descs = pending.listFiles { f -> f.isFile && f.name.endsWith(".descriptor.json") } ?: return emptySet()
        return descs.mapNotNull { d ->
            val localId = d.name.removeSuffix(".descriptor.json")
            localId.takeIf { File(pending, "$it.pte").exists() }
        }.toSet()
    }

    private suspend fun processRow(row: ModelDto, running: Set<String>): ModelSyncOutcome? {
        if (!row.id.endsWith(".pte")) return null // ExecuTorch consumer: only `.pte` rows are relevant

        val meta = row.meta as? JsonObject
            ?: return skip(row.id, "meta null/invalid")

        val engine = (meta["engine"] as? JsonPrimitive)?.contentOrNull ?: DEFAULT_ENGINE
        if (engine.lowercase() !in SUPPORTED_ENGINES) return skip(row.id, "unsupported engine $engine")

        // Reject a descriptor ModelStore could not consume BEFORE placing it: its flatten hard-requires a
        // `normalization_stats` object, and a missing one throws there — which (unguarded) would abort
        // discovery of the WHOLE dir, so a bad served descriptor must never be written as discoverable.
        if (meta["normalization_stats"] !is JsonObject) return skip(row.id, "descriptor missing normalization_stats")

        // The local descriptor id we normalize to; the `.pte` filename is derived from it and (since the
        // row id is a `.pte` filename) always equals row.id.
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
        // Up-to-date: a live artifact whose bytes hash to the registry hash needs no download; just
        // ensure the descriptor pair is present so a lone `.pte` still becomes discoverable.
        val livePte = File(modelsDir, pteName)
        if (livePte.exists() && row.sha256.isNotBlank() &&
            sha256Hex(livePte.readBytes()).equals(row.sha256, ignoreCase = true)
        ) {
            if (!File(modelsDir, descName).exists()) writeDescriptor(modelsDir, descName, meta, name, pteName)
            return ModelSyncOutcome.AlreadyCurrent(row.id)
        }

        // Download → write `.part` → fsync → verify BEFORE anything discoverable exists.
        val art = http.downloadModel(row.id)
        val expected = art.sha256?.ifBlank { null } ?: row.sha256.ifBlank { null }

        // Stage (never swap in place) ONLY when this would overwrite a LIVE artifact that a running model
        // is loaded from — i.e. the file already exists (with a different sha; the up-to-date branch above
        // already returned) AND its filename is in the loaded running set. A brand-new model, or one that
        // was only ever on the StubBackend (no live `.pte`), is applied in place — there is nothing to swap.
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

    /** Write the served meta verbatim, but with `id`/`artifact` normalized so ModelStore resolves a
     *  deterministic local id (== [name]) and the exact on-disk `.pte` ([pteName]). */
    private fun writeDescriptor(dir: File, descName: String, meta: JsonObject, name: String, pteName: String) {
        val normalized = JsonObject(meta + mapOf("id" to JsonPrimitive(name), "artifact" to JsonPrimitive(pteName)))
        val part = File(dir, "$descName.part")
        writeAndSync(part, normalized.toString().toByteArray(Charsets.UTF_8))
        atomicRename(part, File(dir, descName))
    }

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

        /** Staging subdir for running-model updates; ModelStore's files-only scan never sees it. */
        const val PENDING_DIR = "pending"

        /** Matches `ModelStore.bundleOf`'s default when a descriptor omits `engine`. */
        private const val DEFAULT_ENGINE = "executorch_xnnpack_fp32"

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

/** SHA-256 of [bytes] as lowercase hex; injectable into the coordinator for tests. */
fun defaultSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Per-model result of one [ModelSyncCoordinator.sync] pass. */
sealed interface ModelSyncOutcome {
    val id: String

    /** Live artifact already matches the registry hash; nothing downloaded. */
    data class AlreadyCurrent(override val id: String) : ModelSyncOutcome

    /** A model the app was not running: downloaded, verified, applied to the live models dir. */
    data class FetchedNew(override val id: String) : ModelSyncOutcome

    /** A running/selected model whose content changed: downloaded + STAGED, awaiting a manual apply
     *  — the dosing model is never silently swapped. */
    data class UpdateDownloaded(override val id: String) : ModelSyncOutcome

    /** Deliberately ignored (null/invalid meta, unsupported engine); non-`.pte` rows are dropped
     *  silently without an outcome. */
    data class Skipped(override val id: String, val reason: String) : ModelSyncOutcome

    /** Attempted but faulted (network, sha mismatch, io); the pass captured it and continued. */
    data class Failed(override val id: String, val reason: String) : ModelSyncOutcome
}

/** The whole-pass result with convenience projections for the caller's human-readable line. */
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
