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

private const val GRID_MS = 300_000L

/** Trailing per-5-min mg/dL series; WARMUP/INVALID excluded (§3.1), gaps carried forward. */
class RoomBgHistoryProvider(
    private val repository: T1dmRepository,
    private val registry: AidexXSourceRegistry,
) : BgHistoryProvider {

    override suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries? =
        series(maxSteps, minSteps, withReconstructed = false)

    override suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries? =
        series(maxSteps, minSteps, withReconstructed = true)

    private suspend fun series(maxSteps: Int, minSteps: Int, withReconstructed: Boolean): BgSeries? {
        val srcId = registry.authoritative.value ?: repository.authoritativeSourceId() ?: return null
        val readings = repository.recentReadings(srcId, maxSteps + 12)
            // Dosing excludes PROMOTED: model output must not drive advice derived from it.
            .filter {
                it.bgMgdl != null && it.flag == ReadingFlag.NORMAL &&
                    (withReconstructed || it.provenance != ReadingProvenance.RECONSTRUCTED)
            }
        if (readings.size < minSteps) return null

        val byTs = TreeMap<Long, Double>()
        for (r in readings) byTs[r.tsMs] = r.bgMgdl!!.toDouble()
        val anchor = byTs.lastKey()
        val earliest = byTs.firstKey()

        var nSteps = ((anchor - earliest) / GRID_MS + 1L).toInt().coerceAtMost(maxSteps)
        nSteps -= nSteps % 6 // whole patches (PATCH_SIZE = 6)
        if (nSteps < minSteps) return null

        val start = anchor - (nSteps - 1L) * GRID_MS
        // A fill stands in for a slot the sensor never covered; real readings still win outright.
        val filled = if (!withReconstructed) {
            emptyMap()
        } else {
            runCatching {
                repository.infillInRange(start, anchor).associate { it.ts to it.mgdl }
            }.getOrElse { emptyMap() }
        }
        val out = DoubleArray(nSteps)
        var last = byTs.ceilingEntry(start)?.value ?: byTs.firstEntry()?.value ?: out[0]
        for (i in 0 until nSteps) {
            val ts = start + i * GRID_MS
            val v = byTs[ts] ?: filled[ts]
            if (v != null) last = v
            out[i] = last
        }
        // Anchors on MEASURED (§3.6-D); readings is newest-first, so firstOrNull, not last.
        val lastMeasured = readings.firstOrNull { it.provenance == ReadingProvenance.MEASURED }?.tsMs ?: anchor
        return BgSeries(out, anchorTsMs = lastMeasured, gridStartMs = start, sourceId = srcId.value)
    }

    /** Fit series: MEASURED only, uncovered slots NaN not carried forward (SPEC §1). */
    override suspend fun fitBgSeries(maxSteps: Int, minSteps: Int): BgSeries? {
        val srcId = registry.authoritative.value ?: repository.authoritativeSourceId() ?: return null
        val readings = repository.recentReadings(srcId, maxSteps + 12)
            .filter {
                it.bgMgdl != null &&
                    it.flag == ReadingFlag.NORMAL &&
                    it.provenance == ReadingProvenance.MEASURED
            }
        if (readings.size < minSteps) return null

        val byTs = TreeMap<Long, Double>()
        for (r in readings) byTs[r.tsMs] = r.bgMgdl!!.toDouble()
        val anchor = byTs.lastKey()
        val nSteps = ((anchor - byTs.firstKey()) / GRID_MS + 1L).toInt().coerceAtMost(maxSteps)
        if (nSteps < minSteps) return null

        val start = anchor - (nSteps - 1L) * GRID_MS
        val out = DoubleArray(nSteps) { Double.NaN }
        for (i in 0 until nSteps) byTs[start + i * GRID_MS]?.let { out[i] = it }
        return BgSeries(out, anchorTsMs = anchor, gridStartMs = start, sourceId = srcId.value)
    }

    /** Trailing slots holding model output: promoted RECONSTRUCTED rows plus spliced bg_infill. */
    override suspend fun reconstructedSlots(maxSteps: Int): Set<Long> {
        if (maxSteps <= 0) return emptySet()
        val srcId = registry.authoritative.value ?: repository.authoritativeSourceId() ?: return emptySet()
        val readings = repository.recentReadings(srcId, maxSteps + 12)
        if (readings.isEmpty()) return emptySet()
        val covered = HashSet<Long>()
        val fromModel = HashSet<Long>()
        for (r in readings) {
            if (r.bgMgdl == null || r.flag != ReadingFlag.NORMAL) continue
            covered.add(r.tsMs)
            if (r.provenance == ReadingProvenance.RECONSTRUCTED) fromModel.add(r.tsMs)
        }
        val oldest = readings.minOf { it.tsMs }
        val newest = readings.maxOf { it.tsMs }
        for (f in runCatching { repository.infillInRange(oldest, newest) }.getOrElse { emptyList() }) {
            covered.add(f.ts)
            fromModel.add(f.ts)
        }
        if (fromModel.isEmpty()) return emptySet()
        // Carry: series holds the last value across uncovered slots, so reconstruction persists.
        val out = HashSet<Long>(fromModel)
        var ts = oldest
        var carrying = false
        while (ts <= newest) {
            if (covered.contains(ts)) carrying = fromModel.contains(ts) else if (carrying) out.add(ts)
            ts += GRID_MS
        }
        return out
    }

    /** Warmup-gate numerator: distinct slots covered by a MEASURED, NORMAL reading. */
    override suspend fun measuredStepsInWindow(windowSteps: Int): Int {
        if (windowSteps <= 0) return 0
        val srcId = registry.authoritative.value ?: repository.authoritativeSourceId() ?: return 0
        val readings = repository.recentReadings(srcId, windowSteps + 12)
            .filter {
                it.bgMgdl != null &&
                    it.flag == ReadingFlag.NORMAL &&
                    it.provenance == ReadingProvenance.MEASURED
            }
        if (readings.isEmpty()) return 0
        val anchor = readings.maxOf { it.tsMs }
        val windowStart = anchor - (windowSteps - 1L) * GRID_MS
        return readings.asSequence()
            .filter { it.tsMs >= windowStart }
            .map { it.tsMs / GRID_MS }
            .distinct()
            .count()
    }
}

/** Telemetry as one JSON blob in the kv store; malformed or absent decodes as empty map. */
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
