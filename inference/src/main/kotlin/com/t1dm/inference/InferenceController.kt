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
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ModelTelemetry
import com.t1dm.core.model.Precision
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.RunningModel
import com.t1dm.inference.backend.GraphIo
import com.t1dm.inference.backend.GraphInput
import com.t1dm.inference.backend.GraphOutput
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
    /** Reconstructed carb/insulin context channels (PLAN §3.3); null ⇒ `normalize(0)` baseline. */
    private val contextChannels: ContextChannelSource? = null,
    /** Committed dose tails carried into the PREDICTION ZONE (PLAN §3.3); null ⇒ `normalize(0)`
     *  baseline. Distinct from [contextChannels] (the past): this is the already-logged action that
     *  keeps absorbing past the now-boundary, so the forecast responds to a just-logged dose the way
     *  the calculator's baseline roll does. See [FutureOverrideSource]. */
    private val futureOverrides: FutureOverrideSource? = null,
    /** The user's `warmupHours` setting, read FRESH each cycle (kv-backed). Floored at MIN_CONTEXT
     *  (8 h) inside the gate. inference-runtime.md — the WARMUP gate. */
    private val warmupHoursProvider: suspend () -> Double = { DEFAULT_WARMUP_HOURS },
    /** Durable cumulative per-model inference telemetry (Phase 7C — Models drill-down). Null ⇒
     *  session-only in-memory counters. */
    private val telemetryStore: TelemetryStore? = null,
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
    /** Cumulative per-model telemetry (durable via [telemetryStore]); loaded once, then in-memory. */
    private val cumulative = HashMap<String, CumulativeTelemetry>()
    private var telemetryLoaded = false
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
        if (!telemetryLoaded) {
            runCatching { telemetryStore?.load() }.getOrNull()?.let { cumulative.putAll(it) }
            telemetryLoaded = true
        }
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
        _state.value = _state.value.copy(
            running = runningModels(),
            metas = metasSnapshot(),
            telemetry = telemetrySnapshot(),
            note = note,
        )
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

    /**
     * Debug-only: publish an ELIGIBLE (OK, fresh) forecast whose median ramps linearly from [startBg]
     * to [endBg] over the horizon, so the §3.6-gated predictive surfaces (the always-on notification's
     * "approaching …" line and the full-screen predictive-urgent alert) can be driven to their POSITIVE
     * state without a live descending sensor trace — the exact path HyperOS blocked in Phase 7A. The
     * fan is a fixed ±15 mg/dL monotone band so it passes the degeneracy guard's intent by construction.
     * Not wired in release.
     */
    fun debugPublishForecast(nowMs: Long, startBg: Double, endBg: Double) {
        val id = selectedId ?: loaded.keys.firstOrNull() ?: return
        val entry = loaded[id] ?: return
        val cycleTs = snapToGrid(nowMs)
        val n = 24
        val median = DoubleArray(n) { i ->
            startBg + (endBg - startBg) * (i + 1).toDouble() / n
        }.toList()
        val bands = ArrayList<Double>(n * N_QUANTILES)
        for (i in 0 until n) {
            val m = median[i]
            for (q in 0 until N_QUANTILES) {
                val frac = if (N_QUANTILES <= 1) 0.5 else q.toDouble() / (N_QUANTILES - 1)
                bands.add(m - 15.0 + 30.0 * frac) // ascending-τ, monotone, non-collapsed
            }
        }
        val pred = ModelPrediction(
            modelId = id,
            cycleTsMs = cycleTs,
            anchorTsMs = cycleTs,
            stepMs = GRID_MS,
            medianBg = median,
            bandsMgdl = bands,
            nQuantiles = N_QUANTILES,
            lastBg = startBg,
            status = ForecastStatus.OK,
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
            warmup = null,
            note = "SYNTHETIC eligible forecast (debug) ${startBg.toInt()}→${endBg.toInt()} mg/dL",
        )
        Timber.tag(TAG).w("debugPublishForecast: %s %.0f→%.0f", id, startBg, endBg)
    }

    /**
     * Immutable snapshot of the SELECTED model's provenance for the `:calc` dose advisor
     * ([com.t1dm.core.model] types only, so `:inference` keeps no `:calc` dependency). [real] is
     * false when the [StubBackend] stood in for a missing/failed `.pte` — the calculator treats a
     * non-real selected model as "no model" and fails closed (§3.6-E).
     */
    data class SelectedModelInfo(
        val id: String,
        val descriptor: ModelDescriptor,
        val backend: BackendId,
        val precision: Precision,
        val real: Boolean,
    )

    /** The selected model's provenance, or null when nothing is loaded/selected. */
    fun selectedModelInfo(): SelectedModelInfo? {
        val id = selectedId ?: return null
        val e = loaded[id] ?: return null
        return SelectedModelInfo(id, e.bundle.descriptor, e.effectiveBackend, e.precision, e.real)
    }

    /**
     * Run one forward on the SELECTED model for the dose calculator's rolled search. Confined to the
     * single-thread `inference` dispatcher AND serialised against a live 5-min cycle through
     * [cycleMutex] (§2.3 — never two forwards concurrent on the one APU/CPU command queue). Throws if
     * no model is selected/loaded; the [com.t1dm.calc.RollingForecaster] catches and fails closed.
     */
    suspend fun runSelected(input: GraphInput): GraphOutput = cycleMutex.withLock {
        val id = selectedId ?: error("no selected model")
        val e = loaded[id] ?: error("selected model not loaded")
        withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
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

        // ── WARMUP gate (inference-runtime.md): withhold forecasts until at least `warmupHours` of
        //    MEASURED (non-interpolated) context has accrued, floored at the model's MIN_CONTEXT.
        //    DISTINCT from the per-cycle freshness gate below; both remain in force.
        val minContextHours = minSteps * GRID_MS / MS_PER_HOUR
        val requiredHours = warmupHoursProvider().coerceAtLeast(minContextHours)
        val requiredSteps = Math.round(requiredHours * MS_PER_HOUR / GRID_MS).toInt()
        val measuredSteps = runCatching { history.measuredStepsInWindow(requiredSteps) }.getOrDefault(0)
        if (measuredSteps < requiredSteps) {
            val measuredHours = measuredSteps * GRID_MS / MS_PER_HOUR
            _state.value = _state.value.copy(
                predictions = emptyList(), // clear the overlay while warming
                lastCause = InferenceCause.COLLECTING_CONTEXT,
                warmup = com.t1dm.core.model.WarmupProgress(measuredHours, requiredHours),
                note = "collecting context — %.1f / %.0f h of measured data".format(measuredHours, requiredHours),
            )
            Timber.tag(TAG).i("warmup: %.1f/%.0f h measured — forecasts suppressed", measuredHours, requiredHours)
            return
        }

        val series = history.recentBgSeries(maxSteps, minSteps)
        if (series == null) {
            _state.value = _state.value.copy(
                predictions = emptyList(),
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

        // ONE shared context build across the running set: the carb-appearance (feat 1) + insulin-
        // action (feat 2) channels reconstructed from the logged meals/doses/basal (PLAN §3.3),
        // aligned to the BG grid. Model-independent (the per-desc normalization happens in
        // build_context); a null/failed source falls back to the `normalize(0)` no-dose baseline.
        val doseChannels = buildDoseChannels(series)

        // ONE shared PREDICTION-ZONE build: the COMMITTED dose tails (already-logged meals/doses still
        // absorbing past the now-boundary) reconstructed via the SAME curve engine the calculator's
        // baseline roll uses (PLAN §3.3). Carried into build_context's announced-future slots so a
        // just-logged meal RAISES (and a just-logged insulin LOWERS) the main-view forecast, instead
        // of appearing in the past then vanishing at the boundary (an impossible drop-off ⇒ wrong dip).
        // Model-independent (rollStartMs is the grid boundary; predSteps is the fixed pred zone).
        val futureChannels = buildFutureChannels(series, loaded.values.first().bundle.descriptor)

        // Serial fan-out over the running set (never concurrent on the one command queue).
        for ((id, entry) in loaded) {
            val pred = runCatching { runOne(entry, id == selectedId, series, doseChannels, futureChannels, cycleTs, stale) }
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
            metas = metasSnapshot(),
            telemetry = telemetrySnapshot(),
            lastCycleTsMs = cycleTs,
            lastCause = cause,
            lastCycleDurationMs = durationMs,
            realBackendAvailable = loaded[selectedId]?.real ?: false,
            warmup = null, // a published cycle clears the warmup banner
            note = if (stale) "forecast STALE — last real BG is ${(nowMs - series.anchorTsMs) / 60_000} min old" else null,
        )
        runCatching { predictionStore.persist(cycleTs, preds) }
            .onFailure { Timber.tag(TAG).w(it, "prediction persist failed") }
        runCatching { telemetryStore?.save(HashMap(cumulative)) }
            .onFailure { Timber.tag(TAG).w(it, "telemetry persist failed") }
        val selPt = preds.firstOrNull { it.selected }?.predictedTime
        Timber.tag(TAG).i(
            "cycle cause=%s models=%d dur=%dms selected=%s status=%s predHour=%s",
            cause, preds.size, durationMs, selectedId, preds.firstOrNull { it.selected }?.status,
            selPt?.let { "%.2fh R=%.3f (%d bins)".format(it.predictedHour, it.resultantR, it.nBins) } ?: "n/a",
        )
    }

    private suspend fun runOne(
        entry: Entry,
        selected: Boolean,
        series: BgSeries,
        doseChannels: DoseChannels,
        futureChannels: DoseChannels?,
        cycleTs: Long,
        stale: Boolean,
    ): ModelPrediction {
        val desc = entry.bundle.descriptor
        val ctx = buildContext(desc, series.mgdl, doseChannels, futureChannels)
        val input = GraphIo.graphInput(ctx, desc.negFill)

        val t0 = System.nanoTime()
        val out = withContext(dispatchers.inference) { entry.backend.run(entry.handle, input) }
        val latMs = (System.nanoTime() - t0) / 1_000_000.0
        recordLatency(entry.bundle.id, latMs)
        recordCumulative(entry.bundle.id, latMs)

        val forecast: Forecast = withContext(dispatchers.default) {
            native.assembleDecode(desc, out.headRaw.map { it.toDouble() }, ctx.lastBg, CARRY_SPREAD)
        }
        val status: ForecastStatus =
            withContext(dispatchers.default) { native.forecastDegeneracyCheck(forecast) }

        // Circadian-phase belief (Phase 7A): the co-trained time probe's second `.pte` output,
        // reduced to a predicted hour-of-day in the Rust core. Purely additive — fail-OPEN to null
        // (descriptor lacks a time section, backend returned no slot-1 tensor, or the decode
        // throws) so it can NEVER perturb the BG forecast/degeneracy path above.
        val predictedTime: PredictedTime? = decodeTimeSafely(desc, out.timeLogits)

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
            predictedTime = predictedTime,
        )
    }

    /**
     * Decode the time-probe's slot-1 logits into a circadian-phase belief, fail-OPEN. Returns null
     * unless the descriptor declares a time section AND the backend produced a matching flat
     * `(P, nBins)` tensor; any decode error (mapped by [NativeCore.decodeTime] to null) or a
     * length mismatch also yields null. Never throws — the caller must not let a time-probe hiccup
     * touch the BG forecast.
     */
    private suspend fun decodeTimeSafely(desc: ModelDescriptor, timeLogits: FloatArray?): PredictedTime? {
        val time = desc.time ?: return null
        val logits = timeLogits ?: return null
        if (time.nBins <= 0 || logits.isEmpty() || logits.size % time.nBins != 0) return null
        return runCatching {
            withContext(dispatchers.default) {
                native.decodeTime(logits.map { it.toDouble() }, time.nBins, time.binHours)
            }
        }.getOrElse {
            Timber.tag(TAG).w(it, "time-probe decode failed; predicted hour omitted this cycle")
            null
        }
    }

    /** The two shared context channels for a cycle, index-aligned to the BG grid. */
    private class DoseChannels(val carb: DoubleArray, val insulin: DoubleArray)

    /**
     * Reconstruct the carb-appearance + insulin-action channels ONCE for the cycle from the logged
     * events (PLAN §3.3), aligned to `series.gridStartMs`. Off-main via the source's own dispatcher.
     * A missing/failed source or a length mismatch falls back to the `normalize(0)` no-dose baseline.
     */
    private suspend fun buildDoseChannels(series: BgSeries): DoseChannels {
        val n = series.mgdl.size
        val src = contextChannels ?: return DoseChannels(DoubleArray(n), DoubleArray(n))
        return runCatching {
            val (carb, insulin) = src.channels(series.gridStartMs, n)
            if (carb.size == n && insulin.size == n) DoseChannels(carb, insulin)
            else DoseChannels(DoubleArray(n), DoubleArray(n))
        }.getOrElse {
            Timber.tag(TAG).w(it, "context channel build failed; falling back to no-dose baseline")
            DoseChannels(DoubleArray(n), DoubleArray(n))
        }
    }

    /**
     * Reconstruct the COMMITTED prediction-zone dose tails ONCE for the cycle (PLAN §3.3). Aligned to
     * the grid boundary one step past the last context sample (`gridStartMs + n·STEP`) — so the tail
     * carried here continues seamlessly from the [contextChannels] past. Length = the model's fixed
     * pred zone (P·S). A missing/failed source or mismatch ⇒ `null`, i.e. the `normalize(0)` no-dose
     * baseline (exact pre-Phase-4c behaviour). Uses the SAME `ChannelBuilder.futureOverrides` engine
     * as `RollingForecaster`, so the directional response is identical.
     */
    private suspend fun buildFutureChannels(series: BgSeries, desc: ModelDescriptor): DoseChannels? {
        val src = futureOverrides ?: return null
        val predSteps = predSteps(desc)
        if (predSteps <= 0) return null
        val rollStartMs = series.gridStartMs + series.mgdl.size.toLong() * GRID_MS
        return runCatching {
            val (carb, insulin) = src.overrides(rollStartMs, predSteps)
            DoseChannels(carb, insulin)
        }.getOrElse {
            Timber.tag(TAG).w(it, "future-override build failed; prediction zone falls back to no-dose baseline")
            null
        }
    }

    /** The fixed prediction-zone step count for a descriptor: P·S (mirrors RollingForecaster). */
    private fun predSteps(desc: ModelDescriptor): Int =
        (desc.predictionHorizonHours * STEPS_PER_HOUR / desc.patchSize) * desc.patchSize

    /**
     * Build the normalized context, conditioning feat 1 (carb) / feat 2 (insulin) on the past
     * reconstructed channels [ch] AND the prediction zone on the COMMITTED future tails [future]
     * (PLAN §3.3) — so the main-view forecast reflects logged meals/doses across the now-boundary.
     * `future == null` seeds the pred-zone dose slots to `normalize(0)` (no committed dose / unwired).
     * Feat order is fixed carb-then-insulin at every `native.buildContext` slot (context AND
     * announced), identical to `RollingForecaster` — no swap.
     */
    private suspend fun buildContext(
        desc: ModelDescriptor,
        mgdl: DoubleArray,
        ch: DoseChannels,
        future: DoseChannels?,
    ): BuiltContext =
        withContext(dispatchers.default) {
            val predSteps = predSteps(desc)
            val annCarb = future?.let { f -> List(predSteps) { f.carb.getOrElse(it) { 0.0 } } }
            val annInsulin = future?.let { f -> List(predSteps) { f.insulin.getOrElse(it) { 0.0 } } }
            native.buildContext(desc, mgdl.toList(), ch.carb.toList(), ch.insulin.toList(), annCarb, annInsulin)
        }

    private fun runningModels(): List<RunningModel> = loaded.map { (id, e) ->
        RunningModel(id, e.effectiveBackend, e.precision, id == selectedId)
    }

    private fun recordLatency(id: String, ms: Double) {
        val q = latencySamples.getOrPut(id) { ArrayDeque() }
        q.addLast(ms)
        while (q.size > LATENCY_WINDOW) q.removeFirst()
    }

    private fun recordCumulative(id: String, ms: Double) {
        val cur = cumulative[id] ?: CumulativeTelemetry(0, 0.0)
        cumulative[id] = CumulativeTelemetry(cur.predictions + 1, cur.totalInferenceMs + ms)
    }

    /** The loaded running set's static meta (param count / disk size / arch dims / reference). */
    private fun metasSnapshot(): List<ModelMeta> = loaded.values.map { it.bundle.meta }

    /** Cumulative per-model telemetry for every model that has ever run this install. */
    private fun telemetrySnapshot(): List<ModelTelemetry> = cumulative.map { (id, c) ->
        ModelTelemetry(id, c.predictions, c.totalInferenceMs)
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
        const val MS_PER_HOUR = 3_600_000.0
        /** 5-min grid ⇒ 12 steps/hour (mirrors calc HorizonPolicy.STEPS_PER_HOUR). */
        const val STEPS_PER_HOUR = 12
        /** inference-runtime.md default warmup window (h); the setting floors at MIN_CONTEXT = 8 h. */
        const val DEFAULT_WARMUP_HOURS = 24.0
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
