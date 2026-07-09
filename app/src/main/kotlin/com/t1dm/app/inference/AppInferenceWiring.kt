package com.t1dm.app.inference

import com.t1dm.cgm.AidexXSourceRegistry
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.BgSeries
import com.t1dm.inference.CumulativeTelemetry
import com.t1dm.inference.TelemetryStore
import com.t1dm.data.T1dmRepository
import org.json.JSONObject
import timber.log.Timber
import java.util.TreeMap

/**
 * `:app` implementation of the `:inference` [BgHistoryProvider] port (PLAN.private.md Phase 2
 * deliverable 4): the shared BG history the cycle conditions on, projected off Room + the CGM
 * registry. The Phase-2 `kv`-blob [com.t1dm.inference.PredictionStore] is retired in Phase 3 —
 * [com.t1dm.app.sync.RoomPredictionStore] now persists to the dedicated `prediction` table and
 * enqueues the `PREDICTIONS` outbox batch.
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
        // Anchor freshness on the MOST-RECENT MEASURED reading (interpolated points never reset it,
        // §3.6-D). `readings` is ordered newest-first (DAO `ORDER BY tsMs DESC`), so the freshest
        // measured sample is the FIRST match — `lastOrNull` would pick the oldest and wrongly age the
        // anchor (⇒ perpetual STALE + a fan anchored into the far past, off-screen).
        val lastMeasured = readings.firstOrNull { it.provenance == ReadingProvenance.MEASURED }?.tsMs ?: anchor
        return BgSeries(out, anchorTsMs = lastMeasured, gridStartMs = start)
    }

    /**
     * WARMUP-gate numerator (inference-runtime.md): how many MEASURED (non-interpolated), NORMAL
     * readings sit inside the trailing [windowSteps] grid slots. Interpolated carry-forward and
     * WARMUP/INVALID rows do NOT count — only real sensor signal advances warmup.
     */
    override suspend fun measuredStepsInWindow(windowSteps: Int): Int {
        if (windowSteps <= 0) return 0
        val srcId = registry.active.value ?: repository.activeSourceId() ?: return 0
        val readings = repository.recentReadings(srcId, windowSteps + 12)
            .filter {
                it.bgMgdl != null &&
                    it.flag == ReadingFlag.NORMAL &&
                    it.provenance == ReadingProvenance.MEASURED
            }
        if (readings.isEmpty()) return 0
        val anchor = readings.maxOf { it.tsMs }
        val windowStart = anchor - (windowSteps - 1L) * GRID_MS
        // Distinct 5-min grid slots covered by a measured reading within the trailing window.
        return readings.asSequence()
            .filter { it.tsMs >= windowStart }
            .map { it.tsMs / GRID_MS }
            .distinct()
            .count()
    }
}

/**
 * `:app` implementation of the `:inference` [TelemetryStore] port (Phase 7C — Models drill-down): the
 * CUMULATIVE per-model inference telemetry (#predictions + total backend wall-time) as one JSON blob
 * in the Room `kv` store, keyed [KV_KEY]. Kept off the schema (a single kv row) so `:inference` needs
 * no Room dependency; O(1) read/write per cycle. Malformed/absent ⇒ an empty map (fail-open), so a
 * corrupt row can never break a cycle — the counters simply restart.
 */
class KvTelemetryStore(private val repository: T1dmRepository) : TelemetryStore {

    override suspend fun load(): Map<String, CumulativeTelemetry> {
        val raw = repository.getKv(KV_KEY) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { id ->
                    val o = obj.getJSONObject(id)
                    put(id, CumulativeTelemetry(o.optLong("n"), o.optDouble("ms")))
                }
            }
        }.getOrElse {
            Timber.tag("Telemetry").w(it, "unparseable telemetry blob; resetting counters")
            emptyMap()
        }
    }

    override suspend fun save(all: Map<String, CumulativeTelemetry>) {
        val obj = JSONObject()
        for ((id, c) in all) {
            obj.put(id, JSONObject().put("n", c.predictions).put("ms", c.totalInferenceMs))
        }
        repository.putKv(KV_KEY, obj.toString(), System.currentTimeMillis())
    }

    private companion object {
        const val KV_KEY = "inference.telemetry.cumulative"
    }
}
