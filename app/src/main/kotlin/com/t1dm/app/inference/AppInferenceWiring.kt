package com.t1dm.app.inference

import com.t1dm.cgm.AidexXSourceRegistry
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.BgSeries
import com.t1dm.data.T1dmRepository
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
        // Anchor freshness on the last MEASURED reading (interpolated points never reset it, §3.6-D).
        val lastMeasured = readings.lastOrNull { it.provenance == ReadingProvenance.MEASURED }?.tsMs ?: anchor
        return BgSeries(out, lastMeasured)
    }
}
