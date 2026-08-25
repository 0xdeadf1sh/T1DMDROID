package com.t1dm.app.inference

import com.t1dm.cgm.AidexXSourceRegistry
import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.BaselineSpec
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.inference.BaselineStore
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.BgSeries
import com.t1dm.inference.CumulativeTelemetry
import com.t1dm.inference.TelemetryStore
import com.t1dm.data.T1dmRepository
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.TreeMap

private const val GRID_MS = 300_000L

/** The authoritative source's readings as a trailing per-5-min-step mg/dL series. WARMUP/INVALID
 *  excluded (§3.1); gaps carried forward; null below `minSteps`. BG only — the carb-appearance and
 *  insulin-action channels come from `ContextChannelSource` / `FutureOverrideSource`. */
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
            // The dosing path also refuses a PROMOTED reconstruction: promotion puts a model's own
            // output into `cgm_reading`, and it must not reach the advice derived from it.
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
        // Freshness anchors on the most-recent MEASURED reading (§3.6-D). `readings` is newest-first
        // (DAO `ORDER BY tsMs DESC`), so `lastOrNull` here would pick the oldest and age the anchor.
        val lastMeasured = readings.firstOrNull { it.provenance == ReadingProvenance.MEASURED }?.tsMs ?: anchor
        return BgSeries(out, anchorTsMs = lastMeasured, gridStartMs = start, sourceId = srcId.value)
    }

    /** The fit series: MEASURED only, uncovered grid slots left as `NaN` rather than carried forward
     *  — `SPEC/invariants.md` §1 makes a filled value presentation, never a fit target, so the gaps
     *  travel and the core drops the rows spanning them. Length is NOT rounded to whole patches. */
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

    /** Which trailing slots hold a model's own output. BOTH routes count: promotion writes a
     *  `RECONSTRUCTED` row into `cgm_reading`, and [recentBgSeries] splices an UNPROMOTED `bg_infill`
     *  value into an uncovered slot; a stranded span has only the first. */
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
        // And the CARRY: [series] holds the last value across every uncovered slot, so a
        // reconstruction keeps standing in until something else covers one.
        val out = HashSet<Long>(fromModel)
        var ts = oldest
        var carrying = false
        while (ts <= newest) {
            if (covered.contains(ts)) carrying = fromModel.contains(ts) else if (carrying) out.add(ts)
            ts += GRID_MS
        }
        return out
    }

    /** The warmup-gate numerator: distinct grid slots in the trailing [windowSteps] covered by a
     *  MEASURED, NORMAL reading. Carry-forward and WARMUP/INVALID rows do not advance warmup. */
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

/** Cumulative per-model telemetry as one JSON blob in the Room `kv` store, off the schema so
 *  `:inference` needs no Room dependency. Malformed or absent ⇒ an empty map: a corrupt row restarts
 *  the counters rather than breaking a cycle. */
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

/** The fitted baseline as one JSON blob in the Room `kv` store. Weights and band estimator are one
 *  model and are stored together. Fail-closed: anything corrupt, truncated or shape-inconsistent
 *  loads as `null`, never as a model whose weights do not match its declared feature count. */
class KvBaselineStore(private val repository: T1dmRepository) : BaselineStore {

    override suspend fun load(): BaselineModel? {
        val raw = repository.getKv(KV_KEY)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val o = JSONObject(raw)
            if (o.optInt("v") != BLOB_VERSION) return null
            val s = o.getJSONObject("spec")
            val spec = BaselineSpec(
                nLags = s.getInt("nLags"),
                horizonSteps = s.getInt("horizonSteps"),
                ridgeLambda = s.getDouble("ridgeLambda"),
                useIob = s.getBoolean("useIob"),
                useCob = s.getBoolean("useCob"),
                useForward = s.getBoolean("useForward"),
            )
            val nFeatures = o.getInt("nFeatures")
            val weights = o.getJSONArray("weights").toDoubleList()
            val bandDelta = o.getJSONArray("bandDelta").toDoubleList()
            // A blob whose weight count disagrees with its own spec would decode a forecast off
            // whichever features happened to line up.
            if (weights.size != spec.horizonSteps * (1 + nFeatures)) return null
            if (bandDelta.isNotEmpty() && bandDelta.size != spec.horizonSteps * N_QUANTILES) return null
            if (weights.any { !it.isFinite() } || bandDelta.any { !it.isFinite() }) return null
            BaselineModel(
                spec = spec,
                nFeatures = nFeatures,
                weights = weights,
                bandDelta = bandDelta,
                nTrainRows = o.getInt("nTrainRows"),
                fittedAtMs = o.getLong("fittedAtMs"),
                trainFromMs = o.getLong("trainFromMs"),
                trainToMs = o.getLong("trainToMs"),
            )
        }.getOrElse {
            Timber.tag("Baseline").w(it, "unreadable baseline blob; the model is treated as unfitted")
            null
        }
    }

    override suspend fun save(model: BaselineModel) {
        val obj = JSONObject()
            .put("v", BLOB_VERSION)
            .put(
                "spec",
                JSONObject()
                    .put("nLags", model.spec.nLags)
                    .put("horizonSteps", model.spec.horizonSteps)
                    .put("ridgeLambda", model.spec.ridgeLambda)
                    .put("useIob", model.spec.useIob)
                    .put("useCob", model.spec.useCob)
                    .put("useForward", model.spec.useForward),
            )
            .put("nFeatures", model.nFeatures)
            .put("weights", JSONArray(model.weights))
            .put("bandDelta", JSONArray(model.bandDelta))
            .put("nTrainRows", model.nTrainRows)
            .put("fittedAtMs", model.fittedAtMs)
            .put("trainFromMs", model.trainFromMs)
            .put("trainToMs", model.trainToMs)
        repository.putKv(KV_KEY, obj.toString(), System.currentTimeMillis())
    }

    override suspend fun clear() {
        repository.putKv(KV_KEY, "", System.currentTimeMillis())
    }

    private fun JSONArray.toDoubleList(): List<Double> = List(length()) { getDouble(it) }

    private companion object {
        const val KV_KEY = "inference.baseline.model"

        /** Bumped when the blob's SHAPE changes; an older blob is dropped, not migrated. 2: the
         *  forward carb/insulin blocks joined the feature set, so v1 weights index a different
         *  design row and would decode a plausible, finite, wrong forecast. */
        const val BLOB_VERSION = 2

        const val N_QUANTILES = 7
    }
}
