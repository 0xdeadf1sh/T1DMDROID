package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.BuiltContext
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.ModelLatency
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.RunningModel
import com.t1dm.inference.backend.GraphIo
import com.t1dm.inference.backend.InferenceBackend
import com.t1dm.inference.backend.LoadedModel
import com.t1dm.inference.backend.StubBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.max

/**
 * The Phase-2 inference orchestrator (PLAN.private.md §3.2, Phase 2 deliverable 4). It owns the
 * running set (≤5, default 1), the loaded backend handles, and the observable [state]. A cycle —
 * fired by the 5-min `GridTick` in `CgmScanService`, or manually/synthetically — builds one shared
 * BG history, fans out **serially** over the running set on the single-thread `inference`
 * dispatcher, decodes each `head_raw` in the fp32/fp64 Rust core (`assemble_decode`), gates it
 * through the degeneracy guard (§3.6-B), then publishes + persists the predictions tagged by
 * `model_id`.
 *
 * Everything heavy is off the main thread by construction (§2.3): `Module.load` and `backend.run`
 * on `inference`, all Rust pre/post on `default`, persistence delegated to the `:app`
 * [PredictionStore] (Room `io`). A [Mutex] serialises whole cycles so a slow cycle and a fresh
 * `GridTick` never overlap on the one APU/CPU command queue.
 */
class InferenceController(
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
    private val store: ModelStore,
    private val history: BgHistoryProvider,
    private val predictionStore: PredictionStore,
    /** §3.6-D freshness gate default (Q10): last MEASURED older than this ⇒ forecast STALE. */
    private val freshnessThresholdMs: Long = 15 * 60_000L,
    /** Manual running-set cap (PLAN.private.md §2.3). Default 1 selected model this phase. */
    private val maxRunning: Int = 1,
) {
    private val _state = MutableStateFlow(InferenceState())
    val state: StateFlow<InferenceState> = _state.asStateFlow()

    private val stub = StubBackend()
    private val backends = HashMap<BackendId, InferenceBackend>()

    private data class Entry(
        val bundle: ModelBundle,
        val backend: InferenceBackend,
        val handle: LoadedModel,
        val effectiveBackend: BackendId,
        val precision: Precision,
        val real: Boolean,
    )

    private val loaded = LinkedHashMap<String, Entry>()
    private var selectedId: String? = null
    private val latencySamples = HashMap<String, ArrayDeque<Double>>()
    private val cycleMutex = Mutex()

    /** Register the backends the controller may route to (real XNNPACK + documented NPU stubs). */
    fun registerBackend(backend: InferenceBackend) { backends[backend.id] = backend }

    /** Rehydrate the last persisted predictions so the overlay is populated before the first tick. */
    suspend fun restoreLast() {
        val last = runCatching { predictionStore.loadLast() }.getOrNull() ?: return
        if (last.isNotEmpty()) {
            _state.value = _state.value.copy(
                predictions = last.sortedByDescending { it.selected },
                note = "restored ${last.size} prediction(s) from last run",
            )
        }
    }

    /**
     * (Re)discover models on disk and load the running set. Closes handles that dropped out, loads
     * new ones onto their backend (falling back to the [StubBackend] when the `.pte` is absent or a
     * real load throws), and selects the first model if none is selected. Runs its native loads on
     * the `inference` thread.
     */
    suspend fun refreshModels() = withContext(dispatchers.inference) {
        val bundles = store.discover()
        val keep = bundles.take(maxRunning).associateBy { it.id }

        // Drop models no longer present.
        loaded.keys.filter { it !in keep }.forEach { id ->
            loaded.remove(id)?.let { runCatching { it.backend.close(it.handle) } }
        }
        // Load anything new (or previously failed).
        for ((id, bundle) in keep) {
            if (loaded.containsKey(id)) continue
            loaded[id] = loadEntry(bundle)
        }
        if (selectedId !in loaded.keys) selectedId = loaded.keys.firstOrNull()

        val note = when {
            bundles.isEmpty() ->
                "no model on device — adb push a descriptor.json (+ .pte) to the app models dir"
            loaded.values.none { it.real } ->
                "running on the StubBackend (no working .pte) — real forecast path blocked"
            else -> null
        }
        _state.value = _state.value.copy(running = runningModels(), note = note)
        Timber.tag(TAG).i("refreshModels loaded=%s selected=%s note=%s", loaded.keys, selectedId, note)
    }

    private fun loadEntry(bundle: ModelBundle): Entry {
        val realBackend = backends[bundle.backendId]
        if (bundle.pte.exists() && realBackend != null) {
            val res = runCatching { realBackend.load(bundle.descriptor, bundle.pte) }
            res.getOrNull()?.let { handle ->
                Timber.tag(TAG).i("loaded %s on %s", bundle.id, bundle.backendId)
                return Entry(bundle, realBackend, handle, bundle.backendId, bundle.precision, real = true)
            }
            Timber.tag(TAG).w(res.exceptionOrNull(), "real load of %s failed; StubBackend stands in", bundle.id)
        } else {
            Timber.tag(TAG).w("no .pte / backend for %s; StubBackend stands in", bundle.id)
        }
        val handle = stub.load(bundle.descriptor, bundle.pte)
        return Entry(bundle, stub, handle, BackendId.STUB, Precision.FP32, real = false)
    }

    /**
     * Debug-only: publish a NON_FINITE forecast for the selected model so the overlay/panels can be
     * verified to flag a degenerate forecast as ineligible (PLAN.private.md Phase 2 verify:
     * "force-degenerate intent confirms the fan is flagged and ineligible"). Not wired in release.
     */
    fun debugPublishDegenerate(nowMs: Long) {
        val id = selectedId ?: loaded.keys.firstOrNull() ?: return
        val entry = loaded[id] ?: return
        val cycleTs = snapToGrid(nowMs)
        val nan = List(24) { Double.NaN }
        val pred = ModelPrediction(
            modelId = id,
            cycleTsMs = cycleTs,
            anchorTsMs = cycleTs,
            stepMs = GRID_MS,
            medianBg = nan,
            bandsMgdl = List(24 * N_QUANTILES) { Double.NaN },
            nQuantiles = N_QUANTILES,
            lastBg = Double.NaN,
            status = ForecastStatus.NON_FINITE,
            backend = entry.effectiveBackend,
            precision = entry.precision,
            selected = true,
            stale = false,
            latencyMs = null,
        )
        _state.value = _state.value.copy(
            predictions = listOf(pred),
            lastCycleTsMs = cycleTs,
            lastCause = InferenceCause.MANUAL,
            note = "DEGENERATE forecast forced (debug) — ineligible for rails/alerts",
        )
        Timber.tag(TAG).w("debugPublishDegenerate: forced NON_FINITE forecast for %s", id)
    }

    /** Manually pick the selected (fp32-authoritative) model; a no-op if [id] is not loaded. */
    fun selectModel(id: String) {
        if (id in loaded.keys) {
            selectedId = id
            _state.value = _state.value.copy(running = runningModels())
        }
    }

    /** Fire a cycle off the shared BG history (the `GridTick` path). */
    suspend fun runFromHistory(cause: InferenceCause = InferenceCause.GRID_TICK, nowMs: Long) {
        val descAny = loaded.values.firstOrNull()?.bundle?.descriptor
        if (descAny == null) { refreshOrNote(); return }
        val minSteps = descAny.minContextPatches * GraphIo.PATCH_DIM / 3      // 16·6 = 96 steps (8 h)
        val maxSteps = descAny.maxContextPatches * GraphIo.PATCH_DIM / 3      // 48·6 = 288 steps (24 h)
        val series = history.recentBgSeries(maxSteps, minSteps)
        if (series == null) {
            _state.value = _state.value.copy(
                lastCause = InferenceCause.COLLECTING_CONTEXT,
                note = "collecting context — the model needs ≥${descAny.minContextPatches} patches (8 h) of BG",
            )
            Timber.tag(TAG).i("cycle skipped: still collecting context")
            return
        }
        runCycle(cause, series, nowMs)
    }

    /**
     * Run one full cycle over [series]. Public so the service can drive a synthetic/manual cycle
     * (cold-start verification with a plausible 24 h series — the sensor is not needed).
     */
    suspend fun runCycle(cause: InferenceCause, series: BgSeries, nowMs: Long) = cycleMutex.withLock {
        if (loaded.isEmpty()) { refreshOrNote(); if (loaded.isEmpty()) return@withLock }
        val cycleTs = snapToGrid(nowMs)
        val stale = (nowMs - series.anchorTsMs) > freshnessThresholdMs
        val t0 = System.nanoTime()
        val preds = ArrayList<ModelPrediction>(loaded.size)

        // Serial fan-out over the running set (never concurrent on the one command queue).
        for ((id, entry) in loaded) {
            val pred = runCatching { runOne(entry, id == selectedId, series, cycleTs, stale) }
                .getOrElse {
                    Timber.tag(TAG).w(it, "model %s cycle failed", id); null
                }
            if (pred != null) preds.add(pred)
        }
        preds.sortByDescending { it.selected }

        val durationMs = ((System.nanoTime() - t0) / 1_000_000.0).toLong()
        _state.value = _state.value.copy(
            running = runningModels(),
            predictions = preds,
            latencies = latencySnapshot(),
            lastCycleTsMs = cycleTs,
            lastCause = cause,
            lastCycleDurationMs = durationMs,
            realBackendAvailable = loaded[selectedId]?.real ?: false,
            note = if (stale) "forecast STALE — last real BG is ${(nowMs - series.anchorTsMs) / 60_000} min old" else null,
        )
        runCatching { predictionStore.persist(cycleTs, preds) }
            .onFailure { Timber.tag(TAG).w(it, "prediction persist failed") }
        Timber.tag(TAG).i(
            "cycle cause=%s models=%d dur=%dms selected=%s status=%s",
            cause, preds.size, durationMs, selectedId, preds.firstOrNull { it.selected }?.status,
        )
    }

    private suspend fun runOne(
        entry: Entry,
        selected: Boolean,
        series: BgSeries,
        cycleTs: Long,
        stale: Boolean,
    ): ModelPrediction {
        val desc = entry.bundle.descriptor
        val ctx = buildContext(desc, series.mgdl)
        val input = GraphIo.graphInput(ctx, desc.negFill)

        val t0 = System.nanoTime()
        val out = withContext(dispatchers.inference) { entry.backend.run(entry.handle, input) }
        val latMs = (System.nanoTime() - t0) / 1_000_000.0
        recordLatency(entry.bundle.id, latMs)

        val forecast: Forecast = withContext(dispatchers.default) {
            native.assembleDecode(desc, out.headRaw.map { it.toDouble() }, ctx.lastBg, CARRY_SPREAD)
        }
        val status: ForecastStatus =
            withContext(dispatchers.default) { native.forecastDegeneracyCheck(forecast) }

        return ModelPrediction(
            modelId = entry.bundle.id,
            cycleTsMs = cycleTs,
            anchorTsMs = series.anchorTsMs,
            stepMs = GRID_MS,
            medianBg = forecast.medianBg,
            bandsMgdl = forecast.bandsMgdl,
            nQuantiles = N_QUANTILES,
            lastBg = ctx.lastBg,
            status = status,
            backend = entry.effectiveBackend,
            precision = entry.precision,
            selected = selected,
            stale = stale,
            latencyMs = latMs,
        )
    }

    /** Carb/insulin context is the `normalize(0)` no-dose baseline this phase (Phase 4 = curves). */
    private suspend fun buildContext(desc: ModelDescriptor, mgdl: DoubleArray): BuiltContext =
        withContext(dispatchers.default) {
            val bg = mgdl.toList()
            val zeros = List(mgdl.size) { 0.0 }
            native.buildContext(desc, bg, zeros, zeros, null, null)
        }

    private fun runningModels(): List<RunningModel> = loaded.map { (id, e) ->
        RunningModel(id, e.effectiveBackend, e.precision, id == selectedId)
    }

    private fun recordLatency(id: String, ms: Double) {
        val q = latencySamples.getOrPut(id) { ArrayDeque() }
        q.addLast(ms)
        while (q.size > LATENCY_WINDOW) q.removeFirst()
    }

    private fun latencySnapshot(): List<ModelLatency> = latencySamples.map { (id, q) ->
        val sorted = q.sorted()
        ModelLatency(
            modelId = id,
            runs = sorted.size,
            p50Ms = percentile(sorted, 0.50),
            p95Ms = percentile(sorted, 0.95),
            lastMs = q.lastOrNull() ?: 0.0,
        )
    }

    private suspend fun refreshOrNote() {
        if (loaded.isEmpty()) refreshModels()
    }

    private companion object {
        const val TAG = "CycleRunner"
        const val GRID_MS = 300_000L
        const val N_QUANTILES = 7
        const val CARRY_SPREAD = 0.0 // single-window (≤2 h) this phase; rolling widening is Phase 4
        const val LATENCY_WINDOW = 60

        fun snapToGrid(ts: Long): Long = Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        fun percentile(sortedAsc: List<Double>, q: Double): Double {
            if (sortedAsc.isEmpty()) return 0.0
            val idx = (q * (sortedAsc.size - 1)).toInt().coerceIn(0, max(0, sortedAsc.size - 1))
            return sortedAsc[idx]
        }
    }
}
