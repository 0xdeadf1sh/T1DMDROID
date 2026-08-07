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

/**
 * `:app` implementation of the `:inference` [BgHistoryProvider] port (Phase 2
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
 * readings exist (the "collecting context" state — the model needs ≥16 patches / 8 h). This provider
 * supplies only the BG series; the carb-appearance / insulin-action context is supplied SEPARATELY by
 * the `ContextChannelSource` / `FutureOverrideSource` wired to the real `ChannelBuilder` curve engine
 * (see `AppContainer`), NOT a `normalize(0)` baseline.
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
     * The series a model is FITTED on: MEASURED readings only, with every uncovered grid slot left
     * as `NaN` rather than carried forward.
     *
     * [recentBgSeries] fills gaps because a model must be CONDITIONED on a dense context. A fit is
     * the opposite case: a carried-forward run is a flat stretch that never happened, and a
     * least-squares fit handed enough of them learns persistence from artifacts. `SPEC/invariants.md`
     * §1 makes a filled value a presentation step and never something treated as measured, so the
     * gaps travel and the core drops the rows that span them.
     *
     * Two further departures from [recentBgSeries], both deliberate: interpolated rows are excluded
     * outright (only real sensor signal is a fit target), and the length is not rounded down to whole
     * patches — that is a neural-input constraint and costs the fit usable rows for nothing.
     */
    override suspend fun fitBgSeries(maxSteps: Int, minSteps: Int): BgSeries? {
        val srcId = registry.active.value ?: repository.activeSourceId() ?: return null
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
        return BgSeries(out, anchorTsMs = anchor, gridStartMs = start)
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

/**
 * `:app` implementation of the `:inference` [BaselineStore]: the fitted classical baseline as one
 * JSON blob in the Room `kv` store. Kept off the schema for the same reason [KvTelemetryStore] is —
 * `:inference` needs no Room dependency, and this is one row read once at startup and written once
 * per manual fit.
 *
 * The weights and the band estimator are stored TOGETHER because they are one model
 * ([BaselineModel]); a load that recovered one without the other would produce forecasts whose
 * fan came from a different fit than the median.
 *
 * Fail-closed on anything unreadable: a corrupt, truncated, or shape-inconsistent blob loads as
 * `null` (no model, and the panel offers a fit) rather than as a model whose weights do not match
 * its declared feature count.
 */
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
            // The shape checks are the point of the version field's company: a blob whose weight
            // count disagrees with its own spec cannot be run, and running it would decode a
            // forecast off whichever features happened to line up.
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

        /** Bumped when the blob's SHAPE changes. An older blob is dropped rather than migrated:
         *  a refit costs one button press and is exact, where a migration would be a guess.
         *  2: the forward carb/insulin blocks joined the feature set, so v1 weights index a
         *  different design row and would decode a plausible, finite, wrong forecast. */
        const val BLOB_VERSION = 2

        const val N_QUANTILES = 7
    }
}
