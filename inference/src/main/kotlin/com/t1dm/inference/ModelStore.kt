package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReferenceMetrics
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/** [precision] comes from the descriptor's top level, which the Rust
 *  `parse_descriptor` (scoped to pre/post) does not read. [descriptorJson] is verbatim. */
data class ModelBundle(
    val id: String,
    val descriptor: ModelDescriptor,
    val pte: File,
    /** Null when the export shipped none, or it is missing. */
    val head: File? = null,
    val precision: Precision,
    val descriptorJson: String,
    /** Display only; never decode-critical. */
    val meta: ModelMeta,
)

/** Loads `.pte` + `descriptor.json` pairs pushed to `getExternalFilesDir("models")`; the `.pte` is
 *  gitignored, never bundled. The descriptor is the sole pre/post source — the app never parses the
 *  `.pt` pickle. */
class ModelStore(
    private val modelsDir: File,
    private val native: NativeCore,
) {
    fun ensureDir(): File = modelsDir.apply { if (!exists()) mkdirs() }

    /** A descriptor that fails to parse, or whose artifact is missing, is skipped, not fatal. */
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
        // No projection step: a second transcription of the schema drops keys silently, and it
        // stays invisible until a forecast decodes wrong. A malformed descriptor skips itself.
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
        // Pushed separately from the descriptor, so it may be absent. The bundle is returned with a
        // non-existent [pte] and the controller routes to StubBackend; the descriptor is still needed.
        val pte = File(dir, artifact)
        if (!pte.exists()) {
            Timber.tag(TAG).w("artifact %s for model %s absent; StubBackend will stand in", artifact, id)
        }
        // Absent, the model still forecasts from the graph's own head_raw; it just takes no adapter.
        val head = desc.head?.let { File(dir, it.file) }?.takeIf { it.exists() }
        if (desc.head != null && head == null) {
            Timber.tag(TAG).w("head file %s for model %s absent; no adapter can attach", desc.head?.file, id)
        }
        val engine = obj.optString("engine", "executorch_xnnpack_fp32")
        if (!isXnnpack(engine)) {
            Timber.tag(TAG).w(
                "descriptor %s declares engine %s; this build runs the XNNPACK CPU delegate only, " +
                    "and its runtime registers no other. Skipping rather than loading an artifact " +
                    "no delegate here can execute.",
                descriptorFile.name, engine,
            )
            return null
        }
        return ModelBundle(
            id = id,
            descriptor = desc,
            pte = pte,
            head = head,
            precision = precisionOf(obj.optString("precision", "fp32")),
            descriptorJson = json,
            meta = metaOf(id, obj, pte),
        )
    }

    /** Sole source of truth, so `discover` and [delete] agree on which artifact an id names. */
    private fun resolveId(descriptorFile: File, obj: JSONObject): String =
        obj.optString("id").ifBlank { descriptorFile.name.removeSuffix(".descriptor.json").ifBlank { "model" } }

    /** Every pair sharing [modelId]: a model may ship several backend-variant descriptors. The
     *  `pending/` staging dir is a subdirectory, so the `isFile` filter leaves it alone. */
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
            // An orphaned head would be paired with whatever next takes the id.
            val headName = runCatching { obj.getJSONObject("head").optString("file") }.getOrNull()
            if (!headName.isNullOrBlank() && File(dir, headName).takeIf { it.exists() }?.delete() == true) {
                removed = true
            }
            if (descriptorFile.delete()) removed = true
        }
        return removed
    }

    /** Display only: outside the Rust pre/post contract, so every absent field degrades to null. */
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

    /** The one engine this build can execute. An unrecognised string is refused rather than
     *  assumed to be this one: a wrongly-admitted artifact loads into the wrong delegate or not at
     *  all, and the stub then forecasts under the model's name. */
    private fun isXnnpack(engine: String): Boolean =
        engine.lowercase() in setOf("executorch_xnnpack_fp32", "executorch_xnnpack")

    private fun precisionOf(p: String): Precision =
        if (p.lowercase().contains("16")) Precision.FP16 else Precision.FP32

    private companion object {
        const val TAG = "ModelStore"
    }
}

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
