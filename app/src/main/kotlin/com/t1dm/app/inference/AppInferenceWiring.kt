package com.t1dm.app.inference

import com.t1dm.cgm.AidexXSourceRegistry
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.inference.ArtifactLedger
import com.t1dm.inference.BG_SERIES_ROW_MARGIN
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.BgSeries
import com.t1dm.inference.CumulativeTelemetry
import com.t1dm.inference.FitSource
import com.t1dm.inference.SelectionStore
import com.t1dm.inference.TelemetryStore
import com.t1dm.inference.assembleBgSeries
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
        return assembleBgSeries(
            repository.recentReadings(srcId, maxSteps + BG_SERIES_ROW_MARGIN),
            srcId.value,
            maxSteps,
            minSteps,
            withReconstructed,
        ) { start, anchor ->
            runCatching {
                repository.infillInRange(start, anchor).associate { it.ts to it.mgdl }
            }.getOrElse { emptyMap() }
        }
    }

    /** Fit series: MEASURED only, uncovered slots NaN not carried forward (SPEC §1). */
    override suspend fun fitBgSeries(maxSteps: Int, minSteps: Int): BgSeries? {
        val srcId = registry.authoritative.value ?: repository.authoritativeSourceId() ?: return null
        return measuredSeries(repository.recentReadings(srcId, maxSteps + BG_SERIES_ROW_MARGIN), srcId, maxSteps, minSteps)
    }

    /** Fills are spliced into the trusted sensor's stream only, so only it reads them. */
    override suspend fun fitSources(maxSteps: Int, minSteps: Int): List<FitSource> {
        val trusted = registry.authoritative.value ?: repository.authoritativeSourceId()
        val ids = listOfNotNull(trusted) + repository.allSourceIds().filter { it != trusted }
        val out = ArrayList<FitSource>(ids.size)
        for (id in ids) {
            val readings = repository.recentReadings(id, maxSteps + BG_SERIES_ROW_MARGIN)
            val withFills = id == trusted
            val dense = assembleBgSeries(readings, id.value, maxSteps, minSteps, withReconstructed = true) { a, b ->
                if (!withFills) emptyMap()
                else runCatching { repository.infillInRange(a, b).associate { it.ts to it.mgdl } }.getOrElse { emptyMap() }
            } ?: continue
            val measured = measuredSeries(readings, id, maxSteps, minSteps) ?: continue
            out.add(FitSource(dense, measured, reconstructedOf(readings, withFills)))
        }
        return out
    }

    private fun measuredSeries(newestFirst: List<CgmReading>, srcId: CgmSourceId, maxSteps: Int, minSteps: Int): BgSeries? {
        val readings = newestFirst.filter {
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
        return reconstructedOf(repository.recentReadings(srcId, maxSteps + BG_SERIES_ROW_MARGIN), withFills = true)
    }

    private suspend fun reconstructedOf(readings: List<CgmReading>, withFills: Boolean): Set<Long> {
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
        val fills = if (withFills) {
            runCatching { repository.infillInRange(oldest, newest) }.getOrElse { emptyList() }
        } else {
            emptyList()
        }
        for (f in fills) {
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
        val readings = repository.recentReadings(srcId, windowSteps + BG_SERIES_ROW_MARGIN)
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

/** One kv row of JSON; an unparseable row reads empty, so every id is a first sight. */
class KvArtifactLedger(private val repository: T1dmRepository) : ArtifactLedger {

    override suspend fun load(): Map<String, String> {
        val raw = repository.getKv(KV_KEY) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrElse {
            Timber.tag("ArtifactLedger").w(it, "unparseable ledger; every model reads as new")
            emptyMap()
        }
    }

    override suspend fun save(all: Map<String, String>) {
        repository.putKv(KV_KEY, JSONObject(all).toString(), System.currentTimeMillis())
    }

    private companion object {
        const val KV_KEY = "inference.artifact_ledger"
    }
}

/** One kv row, outside the config export's keys. */
class KvSelectionStore(private val repository: T1dmRepository) : SelectionStore {

    override suspend fun load(): String? = repository.getKv(KV_KEY)?.ifBlank { null }

    override suspend fun save(id: String) {
        repository.putKv(KV_KEY, id, System.currentTimeMillis())
    }

    private companion object {
        const val KV_KEY = "inference.selected_model"
    }
}
