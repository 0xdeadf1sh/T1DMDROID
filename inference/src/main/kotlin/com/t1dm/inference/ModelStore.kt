package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReferenceMetrics
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * A discovered, ready-to-load model: the parsed pre/post [descriptor] (the §2.4 numeric contract),
 * the on-disk [pte] artifact, and the top-level engine metadata the descriptor JSON also carries
 * ([backendId] / [precision]) which the Rust `parse_descriptor` (scoped to pre/post) does not.
 * [descriptorJson] is retained verbatim for the Phase-3 server round-trip.
 */
data class ModelBundle(
    val id: String,
    val descriptor: ModelDescriptor,
    val pte: File,
    /** The head side file on disk, or null when the export shipped none or it is missing. */
    val head: File? = null,
    val backendId: BackendId,
    val precision: Precision,
    val descriptorJson: String,
    /** Size-reasoning + reference metadata for the Models panel (Phase 7C); never decode-critical. */
    val meta: ModelMeta,
)

/**
 * Loads dev-time `.pte` + `descriptor.json` pairs from a device directory (Phase 2:
 * "loads the dev-time `.pte` + `descriptor.json` from a device path (adb-pushable; do NOT bundle a
 * 27 MB `.pte` into committed assets)"). The `.pte` is gitignored; the exporter drops it plus its
 * descriptor into `models/exported/`, and it is pushed to the app's external files dir
 * (`getExternalFilesDir("models")`, i.e. `/sdcard/Android/data/<pkg>/files/models/`).
 *
 * The **descriptor is the sole pre/post source** — the app never parses the `.pt` pickle. The Rust
 * `parse_descriptor` owns the normalization stats + decode constants; this store additionally reads
 * the top-level `id` / `engine` / `artifact` / `precision` (outside the pre/post contract) to route
 * a model to its backend.
 */
class ModelStore(
    private val modelsDir: File,
    private val native: NativeCore,
) {
    /** Directory the store scans; created if absent so a first `adb push` has a target. */
    fun ensureDir(): File = modelsDir.apply { if (!exists()) mkdirs() }

    /**
     * Scan [modelsDir] for descriptor files (`descriptor.json` or `*.descriptor.json`) and resolve
     * each to a loadable [ModelBundle]. A descriptor that fails to parse, or whose artifact is
     * missing, is skipped with a logged reason rather than aborting discovery.
     */
    fun discover(): List<ModelBundle> {
        val dir = ensureDir()
        val descriptors = dir.listFiles { f ->
            f.isFile && (f.name == "descriptor.json" || f.name.endsWith(".descriptor.json"))
        }?.sortedBy { it.name } ?: emptyList()
        if (descriptors.isEmpty()) {
            Timber.tag(TAG).i("no descriptors in %s (adb push a descriptor.json + .pte)", dir.absolutePath)
            return emptyList()
        }
        return descriptors.mapNotNull { bundleOf(it, dir) }
    }

    private fun bundleOf(descriptorFile: File, dir: File): ModelBundle? {
        val json = runCatching { descriptorFile.readText() }.getOrElse {
            Timber.tag(TAG).w(it, "unreadable descriptor %s", descriptorFile.name); return null
        }
        val obj = runCatching { JSONObject(json) }.getOrElse {
            Timber.tag(TAG).w(it, "descriptor %s is not valid JSON", descriptorFile.name); return null
        }
        // The crate parses the descriptor exactly as the exporter writes it. There is no
        // projection step here on purpose: a projection is a second transcription of the schema,
        // and a key silently dropped by one is invisible until a forecast decodes wrong.
        // A malformed descriptor (e.g. a server-served one missing `normalization_stats`, or one
        // from before the exercise channel) must SKIP ITSELF, not throw out of `discover()`'s
        // mapNotNull and disable discovery of every other model.
        val desc = native.parseDescriptor(json)
        if (desc == null) {
            Timber.tag(TAG).w(
                "descriptor %s failed the pre/post parse (a pre-exercise-channel model is refused " +
                    "here rather than run against an input it never saw); skipping",
                descriptorFile.name,
            )
            return null
        }
        val id = resolveId(descriptorFile, obj)
        val artifact = obj.optString("artifact").ifBlank { "$id.xnnpack.pte" }
        // The `.pte` is gitignored + adb-pushed separately from the (tracked) descriptor, so it may
        // be absent. Return the bundle anyway with a non-existent [pte] File — the controller checks
        // existence and routes to the StubBackend (real path blocked) rather than dropping the
        // descriptor, which every downstream Rust pre/post step needs.
        val pte = File(dir, artifact)
        if (!pte.exists()) {
            Timber.tag(TAG).w("artifact %s for model %s absent; StubBackend will stand in", artifact, id)
        }
        // The head side file is the adapter seam. Absent, the model still forecasts — the graph
        // emits its own head_raw — and simply takes no adapter.
        val head = desc.head?.let { File(dir, it.file) }?.takeIf { it.exists() }
        if (desc.head != null && head == null) {
            Timber.tag(TAG).w("head file %s for model %s absent; no adapter can attach", desc.head?.file, id)
        }
        val engine = obj.optString("engine", "executorch_xnnpack_fp32")
        return ModelBundle(
            id = id,
            descriptor = desc,
            pte = pte,
            head = head,
            backendId = backendOf(engine),
            precision = precisionOf(obj.optString("precision", "fp32")),
            descriptorJson = json,
            meta = metaOf(id, obj, pte),
        )
    }

    /**
     * The model's stable id: the descriptor's top-level `id`, else the descriptor filename with its
     * `.descriptor.json` suffix stripped (a bare `descriptor.json` keeps its name), else `"model"`.
     * Sole source of truth so `discover` and [delete] agree on which artifact a modelId names.
     */
    private fun resolveId(descriptorFile: File, obj: JSONObject): String =
        obj.optString("id").ifBlank { descriptorFile.name.removeSuffix(".descriptor.json").ifBlank { "model" } }

    /**
     * Delete every descriptor + `.pte` pair on disk whose resolved id equals [modelId] (a model may
     * ship under several backend-variant descriptors, all sharing one id). Returns true if anything
     * was removed. The ModelSyncCoordinator `pending/` staging dir is a subdirectory and so is never
     * a descriptor here (the `isFile` filter excludes it) — leave staged updates untouched.
     */
    fun delete(modelId: String): Boolean {
        val dir = ensureDir()
        val descriptors = dir.listFiles { f ->
            f.isFile && (f.name == "descriptor.json" || f.name.endsWith(".descriptor.json"))
        }.orEmpty()
        var removed = false
        for (descriptorFile in descriptors) {
            val parsed = runCatching { JSONObject(descriptorFile.readText()) }
            val obj = parsed.getOrNull()
            if (obj == null) {
                Timber.tag(TAG).w(parsed.exceptionOrNull(), "descriptor %s unreadable/invalid during delete; skipping", descriptorFile.name)
                continue
            }
            if (resolveId(descriptorFile, obj) != modelId) continue
            val artifact = obj.optString("artifact").ifBlank { "$modelId.xnnpack.pte" }
            if (File(dir, artifact).takeIf { it.exists() }?.delete() == true) removed = true
            // The head file belongs to the artifact and goes with it; an orphaned head would
            // otherwise be paired with whatever next takes the id.
            val headName = runCatching { obj.getJSONObject("head").optString("file") }.getOrNull()
            if (!headName.isNullOrBlank() && File(dir, headName).takeIf { it.exists() }?.delete() == true) {
                removed = true
            }
            if (descriptorFile.delete()) removed = true
        }
        return removed
    }

    /**
     * Build the display-only [ModelMeta] from the raw descriptor object + the on-disk artifact.
     * Everything here is OUTSIDE the Rust pre/post contract (`geometry`, the exporter-stamped
     * `model_card`, top-level `arch_version`/`executorch_version`) plus the `stat`'d `.pte` size —
     * so it never touches decode and degrades every absent field to null rather than failing.
     */
    private fun metaOf(id: String, obj: JSONObject, pte: File): ModelMeta {
        val geo = obj.optJSONObject("geometry")
        val card = obj.optJSONObject("model_card")
        val ref = card?.optJSONObject("reference_metrics")
        return ModelMeta(
            modelId = id,
            paramCount = card?.optLongOrNull("param_count"),
            diskBytes = if (pte.exists()) pte.length() else null,
            dModel = geo?.optIntOrNull("D_MODEL"),
            nLayers = geo?.optIntOrNull("N_LAYERS"),
            nHeads = geo?.optIntOrNull("N_HEADS"),
            patchDim = geo?.optIntOrNull("PATCH_DIM"),
            minContextPatches = geo?.optIntOrNull("MIN_CONTEXT_PATCHES"),
            maxContextPatches = geo?.optIntOrNull("MAX_CONTEXT_PATCHES"),
            predictionHorizonHours = obj.optJSONObject("constants")?.optIntOrNull("PREDICTION_HORIZON_HOURS"),
            archVersion = obj.optStringOrNull("arch_version"),
            executorchVersion = obj.optStringOrNull("executorch_version"),
            valStep = card?.optIntOrNull("val_step"),
            reference = ref?.let {
                ReferenceMetrics(
                    horizonsMin = it.optJSONArray("horizons_min").toIntList(),
                    rmseMgdl = it.optJSONArray("rmse_mgdl").toDoubleNullList(),
                    mardPct = it.optJSONArray("mard_pct").toDoubleNullList(),
                    clarkeAPct = it.optJSONArray("clarke_a_pct").toDoubleNullList(),
                    coverage90 = it.optJSONArray("coverage90").toDoubleNullList(),
                    clarkeAbPct = it.optDoubleOrNull("clarke_ab_pct"),
                    todMaeH = it.optDoubleOrNull("tod_mae_h"),
                    todMaeHiconfH = it.optDoubleOrNull("tod_mae_hiconf_h"),
                )
            },
        )
    }

    private fun backendOf(engine: String): BackendId = when (engine.lowercase()) {
        "executorch_xnnpack_fp32", "executorch_xnnpack" -> BackendId.EXECUTORCH_XNNPACK_FP32
        "executorch_neuron_fp16", "executorch_neuron" -> BackendId.EXECUTORCH_NEURON_FP16
        "litert_neuron_fp16", "litert_neuron" -> BackendId.LITERT_NEURON_FP16
        "litert_npu_fp32", "litert_npu_fp16", "litert_npu" -> BackendId.LITERT_NPU
        "executorch_vulkan_fp16" -> BackendId.EXECUTORCH_VULKAN_FP16
        "executorch_vulkan_fp32", "executorch_vulkan" -> BackendId.EXECUTORCH_VULKAN_FP32
        else -> BackendId.EXECUTORCH_XNNPACK_FP32
    }

    private fun precisionOf(p: String): Precision =
        if (p.lowercase().contains("16")) Precision.FP16 else Precision.FP32

    private companion object {
        const val TAG = "ModelStore"
    }
}

// ── null-tolerant JSON accessors (a missing/JSONObject.NULL key ⇒ null, never a default) ──
private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

private fun JSONArray?.toIntList(): List<Int> =
    if (this == null) emptyList() else (0 until length()).map { optInt(it) }

private fun JSONArray?.toDoubleNullList(): List<Double?> =
    if (this == null) emptyList() else (0 until length()).map {
        if (isNull(it)) null else optDouble(it).takeIf { d -> d.isFinite() }
    }
