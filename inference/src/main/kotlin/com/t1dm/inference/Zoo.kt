package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/** SPEC/zoo.md §2. */
data class ZooDescriptor(
    val id: String,
    val artifact: String,
    val model: String,
    val causal: Boolean,
    val ctxSteps: Int,
    val zoneSteps: Int,
    val interpMaxSteps: Int,
    val clampMin: Double,
    val clampMax: Double,
    /** `[levels][zoneSteps]` mg/dL, levels in SPEC/invariants.md §6 order. */
    val bandOffsets: List<List<Double>>,
) {
    companion object {
        const val FORMAT = "t1dm-zoo-1"
        private const val N_LEVELS = 7
        private const val MEDIAN_LEVEL = 3

        /** Any `t1dm-zoo` format is ours: an unknown version is refused, never misparsed. */
        fun claims(obj: JSONObject): Boolean = obj.optString("format").startsWith("t1dm-zoo")

        /** Throws [IllegalArgumentException] naming the first fault. */
        fun parse(obj: JSONObject): ZooDescriptor {
            require(obj.optString("format") == FORMAT) { "format ${obj.optString("format")} not supported" }
            val engine = obj.optString("engine")
            require(engine == "executorch_xnnpack_fp32") { "engine $engine not supported" }
            val zone = obj.getInt("zone_steps")
            val levels = obj.getJSONArray("quantile_levels")
            require(levels.length() == N_LEVELS && levels.getDouble(MEDIAN_LEVEL) == 0.5) {
                "quantile_levels not the 7-level fan"
            }
            val off = obj.getJSONArray("band_offsets_mgdl")
            require(off.length() == N_LEVELS) { "band_offsets_mgdl has ${off.length()} rows" }
            val offsets = (0 until N_LEVELS).map { i ->
                val row = off.getJSONArray(i)
                require(row.length() == zone) { "band_offsets_mgdl row $i has ${row.length()} steps" }
                (0 until zone).map { row.getDouble(it) }
            }
            val clamp = obj.getJSONArray("bg_clamp_mgdl")
            val d = ZooDescriptor(
                id = obj.getString("id"),
                artifact = obj.getString("artifact"),
                model = obj.optString("model"),
                causal = obj.getBoolean("causal"),
                ctxSteps = obj.getInt("ctx_steps"),
                zoneSteps = zone,
                interpMaxSteps = obj.getInt("gap_fill_interp_max_steps"),
                clampMin = clamp.getDouble(0),
                clampMax = clamp.getDouble(1),
                bandOffsets = offsets,
            )
            require(d.ctxSteps > 0 && d.zoneSteps > 0 && d.clampMin < d.clampMax) { "bad geometry" }
            return d
        }
    }
}

/** One forward: `bg [1,C]`, `insulin [1,C+Z]`, `carb [1,C+Z]` -> `median [1,Z]`; single-thread. */
interface ZooRunner {
    fun run(bg: FloatArray, insulin: FloatArray, carb: FloatArray): FloatArray
    fun close() {}
}

/** Per-slot units (bolus plus basal) and grams over the grid, each dose at its onset slot. */
class RawDoses(val insulinU: DoubleArray, val carbG: DoubleArray)

fun interface RawDoseSource {
    suspend fun slots(gridStartMs: Long, nSteps: Int): RawDoses
}

/** [history] ends at [anchorTsMs], the latest measured reading, NaN where unmeasured. */
class ZooInput(
    val cycleTsMs: Long,
    val anchorTsMs: Long,
    val sourceId: String?,
    val history: DoubleArray,
    val doses: RawDoseSource,
)

/** SPEC/zoo.md §5: forecasts and backtests; selection lives in [InferenceController.onZoo]. */
class ZooController(
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
    private val modelsDir: File,
    private val loader: (ZooDescriptor, File) -> ZooRunner,
) {
    private class Loaded(val desc: ZooDescriptor, val runner: ZooRunner)

    private val mutex = Mutex()
    private val loaded = LinkedHashMap<String, Loaded>()
    private val _models = MutableStateFlow<List<ZooDescriptor>>(emptyList())
    val models: StateFlow<List<ZooDescriptor>> = _models.asStateFlow()

    private val _predictions = MutableStateFlow<List<ModelPrediction>>(emptyList())
    val predictions: StateFlow<List<ModelPrediction>> = _predictions.asStateFlow()

    fun descriptorOf(id: String): ZooDescriptor? = _models.value.firstOrNull { it.id == id }

    suspend fun refresh() = mutex.withLock {
        withContext(dispatchers.inference) {
            loaded.values.forEach { runCatching { it.runner.close() } }
            loaded.clear()
            val files = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".descriptor.json") }.orEmpty()
            for (f in files.sortedBy { it.name }) {
                val obj = runCatching { JSONObject(f.readText()) }.getOrNull() ?: continue
                if (!ZooDescriptor.claims(obj)) continue
                val d = runCatching { ZooDescriptor.parse(obj) }
                    .onFailure { Timber.tag(TAG).w("zoo descriptor %s refused: %s", f.name, it.message) }
                    .getOrNull() ?: continue
                runCatching { loader(d, File(modelsDir, d.artifact)) }
                    .onSuccess { loaded[d.id] = Loaded(d, it) }
                    .onFailure { Timber.tag(TAG).w(it, "zoo model %s failed to load", d.id) }
            }
            _models.value = loaded.values.map { it.desc }
            _predictions.value = _predictions.value.filter { it.modelId in loaded }
        }
    }

    /** History steps a forecast reads: the context plus room for the gap fill to bracket. */
    fun historySteps(): Int = _models.value.maxOfOrNull { it.ctxSteps + it.interpMaxSteps } ?: 0

    suspend fun runLive(input: ZooInput, nowMs: Long, freshnessMs: Long) {
        _predictions.value = _models.value.mapNotNull { forecast(it.id, input) }
            .map { it.copy(stale = nowMs - it.anchorTsMs > freshnessMs) }
    }

    /** Null when the model is gone, the end reading is unmeasured, or the forward threw. */
    suspend fun forecast(modelId: String, input: ZooInput): ModelPrediction? {
        val desc = descriptorOf(modelId) ?: return null
        val window = native.zooBgWindow(input.history, desc.ctxSteps, desc.interpMaxSteps) ?: return null
        val n = desc.ctxSteps + desc.zoneSteps
        val raw = input.doses.slots(input.anchorTsMs - (desc.ctxSteps - 1) * STEP_MS, n)
        fun zoned(src: DoubleArray) = FloatArray(n) { if (desc.causal && it >= desc.ctxSteps) 0f else src[it].toFloat() }
        val ins = zoned(raw.insulinU)
        val carb = zoned(raw.carbG)
        val t0 = System.nanoTime()
        val median = mutex.withLock {
            val runner = loaded[modelId]?.runner ?: return null
            withContext(dispatchers.inference) {
                runCatching { runner.run(window, ins, carb) }
                    .onFailure { Timber.tag(TAG).w(it, "zoo %s forward failed", modelId) }
                    .getOrNull()
            }
        } ?: return null
        val ok = median.size == desc.zoneSteps && median.all { it.isFinite() }
        val line = if (ok) median.map { it.toDouble().coerceIn(desc.clampMin, desc.clampMax) } else emptyList()
        return ModelPrediction(
            modelId = modelId,
            cycleTsMs = input.cycleTsMs,
            anchorTsMs = input.anchorTsMs,
            sourceId = input.sourceId,
            stepMs = STEP_MS,
            medianBg = line,
            bandsMgdl = if (ok) native.zooFan(line, desc.bandOffsets, desc.clampMin, desc.clampMax) else emptyList(),
            nQuantiles = desc.bandOffsets.size,
            lastBg = window.last().toDouble(),
            status = if (ok) ForecastStatus.OK else ForecastStatus.NON_FINITE,
            backend = BackendId.EXECUTORCH_XNNPACK_FP32,
            selected = false,
            stale = false,
            latencyMs = (System.nanoTime() - t0) / 1e6,
        )
    }

    suspend fun <O> backtest(
        modelId: String,
        origins: List<O>,
        inputAt: suspend (O) -> ZooInput?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ModelPrediction> {
        val out = ArrayList<ModelPrediction>()
        origins.forEachIndexed { i, o ->
            inputAt(o)?.let { forecast(modelId, it) }?.let { out += it }
            onProgress(i + 1, origins.size)
        }
        return out
    }

    private companion object {
        const val TAG = "Zoo"
        const val STEP_MS = 300_000L
    }
}
