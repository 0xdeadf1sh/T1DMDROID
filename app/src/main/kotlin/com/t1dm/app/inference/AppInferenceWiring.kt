package com.t1dm.app.inference

import com.t1dm.cgm.AidexXSourceRegistry
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.BgSeries
import com.t1dm.inference.PredictionStore
import com.t1dm.data.T1dmRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/**
 * `:app` implementations of the `:inference` ports (PLAN.private.md Phase 2 deliverable 4). These
 * bind the ExecuTorch-free inference module onto Room + the CGM registry: the shared BG history the
 * cycle conditions on, and the (Room `kv`-backed) prediction persistence.
 */

private const val GRID_MS = 300_000L

/**
 * Projects the active source's grid-aligned readings into the trailing per-5-min-step mg/dL series
 * the model conditions on. WARMUP/INVALID readings are excluded (§3.1 — suppressed from inference);
 * gaps within the covered window are carried forward. Returns `null` when fewer than `minSteps` real
 * readings exist (the "collecting context" state — the model needs ≥16 patches / 8 h). Carb/insulin
 * are the `normalize(0)` baseline downstream this phase; the real curve engine is Phase 4.
 */
class RoomBgHistoryProvider(
    private val repository: T1dmRepository,
    private val registry: AidexXSourceRegistry,
) : BgHistoryProvider {

    override suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries? {
        val srcId = registry.active.value ?: repository.activeSourceId() ?: return null
        val readings = repository.recentReadings(srcId, maxSteps + 12)
            .filter { it.bgMgdl != null && it.flag == ReadingFlag.NORMAL } // excludes warmup + invalid
        if (readings.size < minSteps) return null

        val byTs = TreeMap<Long, Double>()
        for (r in readings) byTs[r.tsMs] = r.bgMgdl!!.toDouble()
        val anchor = byTs.lastKey()
        val earliest = byTs.firstKey()

        var nSteps = ((anchor - earliest) / GRID_MS + 1L).toInt().coerceAtMost(maxSteps)
        nSteps -= nSteps % 6 // whole patches (PATCH_SIZE = 6)
        if (nSteps < minSteps) return null

        val start = anchor - (nSteps - 1L) * GRID_MS
        val out = DoubleArray(nSteps)
        var last = byTs.ceilingEntry(start)?.value ?: byTs.firstEntry()?.value ?: out[0]
        for (i in 0 until nSteps) {
            val v = byTs[start + i * GRID_MS]
            if (v != null) last = v
            out[i] = last
        }
        // Anchor freshness on the last MEASURED reading (interpolated points never reset it, §3.6-D).
        val lastMeasured = readings.lastOrNull { it.provenance == ReadingProvenance.MEASURED }?.tsMs ?: anchor
        return BgSeries(out, lastMeasured)
    }
}

/**
 * Persists a cycle's predictions into the Room `kv` store (a compact JSON blob) and reloads the
 * latest on restart, so the overlay is populated before the first tick and survives process death.
 * The dedicated `prediction` table + `PREDICTIONS` outbox enqueue are Phase 3; the `kv` blob keeps
 * `:inference` free of a schema-migration dependency this phase.
 */
class KvPredictionStore(private val repository: T1dmRepository) : PredictionStore {

    override suspend fun persist(cycleTsMs: Long, predictions: List<ModelPrediction>) {
        val arr = JSONArray()
        for (p in predictions) arr.put(encode(p))
        val root = JSONObject().put("cycleTsMs", cycleTsMs).put("predictions", arr)
        repository.putKv(KEY, root.toString(), System.currentTimeMillis())
    }

    override suspend fun loadLast(): List<ModelPrediction>? {
        val json = repository.getKv(KEY) ?: return null
        return runCatching {
            val arr = JSONObject(json).getJSONArray("predictions")
            (0 until arr.length()).map { decode(arr.getJSONObject(it)) }
        }.getOrNull()
    }

    private fun encode(p: ModelPrediction): JSONObject = JSONObject().apply {
        put("modelId", p.modelId)
        put("cycleTsMs", p.cycleTsMs)
        put("anchorTsMs", p.anchorTsMs)
        put("stepMs", p.stepMs)
        put("nQuantiles", p.nQuantiles)
        put("lastBg", p.lastBg)
        put("status", p.status.name)
        put("backend", p.backend.name)
        put("precision", p.precision.name)
        put("selected", p.selected)
        put("stale", p.stale)
        p.latencyMs?.let { put("latencyMs", it) }
        put("medianBg", JSONArray(p.medianBg))
        put("bandsMgdl", JSONArray(p.bandsMgdl))
    }

    private fun decode(o: JSONObject): ModelPrediction = ModelPrediction(
        modelId = o.getString("modelId"),
        cycleTsMs = o.getLong("cycleTsMs"),
        anchorTsMs = o.getLong("anchorTsMs"),
        stepMs = o.getLong("stepMs"),
        medianBg = o.getJSONArray("medianBg").toDoubleList(),
        bandsMgdl = o.getJSONArray("bandsMgdl").toDoubleList(),
        nQuantiles = o.getInt("nQuantiles"),
        lastBg = o.getDouble("lastBg"),
        status = ForecastStatus.valueOf(o.getString("status")),
        backend = BackendId.valueOf(o.getString("backend")),
        precision = Precision.valueOf(o.getString("precision")),
        selected = o.getBoolean("selected"),
        stale = o.getBoolean("stale"),
        latencyMs = if (o.has("latencyMs")) o.getDouble("latencyMs") else null,
    )

    private fun JSONArray.toDoubleList(): List<Double> = (0 until length()).map { getDouble(it) }

    private companion object {
        const val KEY = "inference.last_predictions"
    }
}
