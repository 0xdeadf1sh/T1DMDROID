package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BASELINE_MODEL_ID
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.BaselineFit
import com.t1dm.core.model.displayName
import com.t1dm.core.model.LoraConfig
import com.t1dm.core.model.LoraGuardReport
import com.t1dm.core.model.LoraProgressSink
import com.t1dm.core.model.LoraSample
import com.t1dm.core.model.LoraTrainOpts
import com.t1dm.core.model.LoraTrainResult
import com.t1dm.core.model.LoraWeights
import com.t1dm.core.model.GraphInput
import com.t1dm.core.model.MaskGeometry
import com.t1dm.core.model.MaskSpan
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.PROBE_DOSE_U
import com.t1dm.core.model.ModelLatency
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ModelTelemetry
import com.t1dm.core.model.Precision
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.RunningModel
import com.t1dm.core.model.ThermalStatus
import com.t1dm.inference.backend.GraphIo
import com.t1dm.inference.backend.GraphTensors
import com.t1dm.inference.backend.GraphOutput
import com.t1dm.inference.backend.InferenceBackend
import com.t1dm.inference.backend.LoadedModel
import com.t1dm.inference.backend.StubBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.max

/** Owns the running set, the loaded backend handles and the observable [state]. `Module.load` and
 *  `backend.run` go on the `inference` dispatcher, all Rust pre/post on `default`, and [cycleMutex]
 *  serialises whole cycles so two forwards never overlap on the one APU/CPU command queue. */
class InferenceController(
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
    private val store: ModelStore,
    private val history: BgHistoryProvider,
    private val predictionStore: PredictionStore,
    /** Last MEASURED older than this ⇒ forecast STALE (§3.6-D). */
    private val freshnessThresholdMs: Long = 15 * 60_000L,
    /** Read FRESH each discovery; coerced to ≥1. */
    private val maxRunningProvider: suspend () -> Int = { DEFAULT_MAX_RUNNING },
    /** Reconstructed carb/insulin context channels (SPEC §3.3); null ⇒ `normalize(0)` baseline. */
    private val contextChannels: ContextChannelSource? = null,
    /** The already-logged action still absorbing past the now-boundary (SPEC §3.3), as against
     *  [contextChannels], the past. Null ⇒ `normalize(0)` baseline. */
    private val futureOverrides: FutureOverrideSource? = null,
    /** Read FRESH each cycle. The model's own MIN_CONTEXT is the binding floor
     *  (inference-runtime.md). */
    private val warmupHoursProvider: suspend () -> Double = { DEFAULT_WARMUP_HOURS },
    /** Null ⇒ session-only in-memory counters. */
    private val telemetryStore: TelemetryStore? = null,
    /** BATTERY-sensor °C, read FRESH each cycle; null (gate disabled or unreadable) never gates. No
     *  death-mode check — the one §3.6 rail DEATH does not defeat, a thermal fault being a hardware
     *  risk rather than a glucose alarm. */
    private val thermalProvider: suspend () -> ThermalStatus? = { null },
    /** INFERENCE.md §7.1, read FRESH each cycle. Snapped to an offered detent, so a corrupt setting
     *  never reaches the Rust guard. It MOVES the §3.6-D `last_bg` anchor. */
    private val smoothingWindowProvider: suspend () -> Int = { InferenceControllerDefaults.SAVGOL_WINDOW },
    /** Null ⇒ the counterfactual branch never runs, so every adapter stays `ABSENT`. */
    private val probeInsulin: ProbeInsulinPort? = null,
    /** Runs beside [loaded] and publishes an ordinary [ModelPrediction], but is not IN [loaded]: it
     *  has no descriptor and no artifact, which `SPEC/invariants.md` §4 rule 5 makes one unit. So
     *  [selectedModelInfo] cannot resolve it and the dose path fails closed while it is selected. */
    private val baseline: BaselineRunner? = null,
    /** Re-read every cycle. Null ⇒ every model runs frozen. */
    private val loraStore: LoraStore? = null,
) {
    private val _state = MutableStateFlow(InferenceState())
    val state: StateFlow<InferenceState> = _state.asStateFlow()

    /** Every published prediction was conditioned on the OUTGOING sensor's history, so beside the
     *  new sensor's glucose it describes nothing. The running set, metadata and telemetry survive. */
    fun onCgmSourceChanged() {
        // `update`, not `value = value.copy(...)`: called outside the cycle mutex, so a
        // read-modify-write here races a cycle publishing its results.
        _state.update { it.copy(predictions = emptyList(), lastCycleTsMs = null, lastCause = null) }
    }

    private val stub = StubBackend()
    private val backends = HashMap<BackendId, InferenceBackend>()

    /** Opened lazily and verified against the graph on first use. Only the adapter path reads them. */
    private val heads = HeadCache(native)

    private data class Entry(
        val bundle: ModelBundle,
        val backend: InferenceBackend,
        val handle: LoadedModel,
        val effectiveBackend: BackendId,
        val precision: Precision,
        val real: Boolean,
    )

    private val loaded = LinkedHashMap<String, Entry>()
    /** Every DISCOVERED model, before load. `ModelStore` admits only the XNNPACK engine, so a model
     *  id has one bundle: the artifact the authority runs, or nothing. */
    private val installed = LinkedHashMap<String, ModelBundle>()
    /** Written under [cycleMutex] on the inference thread, read unlocked off the default dispatcher
     *  in the [runFromHistory] preamble. */
    @Volatile
    private var selectedId: String? = null
    private val latencySamples = HashMap<String, ArrayDeque<Double>>()
    /** Durable via [telemetryStore]; loaded once, then in-memory. */
    private val cumulative = HashMap<String, CumulativeTelemetry>()
    private var telemetryLoaded = false
    /** The largest `requiredSteps` window whose coverage has EVER been met; warmup latches
     *  monotonically at or below it, so one dropped slot cannot flap the forecast back into
     *  "collecting context". In-memory only. */
    @Volatile
    private var warmupSatisfiedUpTo = 0
    private val cycleMutex = Mutex()
    /** Latches at [ThermalStatus.thresholdC] and clears only below `thresholdC - resumeMarginC`, so
     *  a temperature hovering on the threshold cannot flap the forecast. In-memory only. */
    @Volatile
    private var thermalBlocked = false

    /** One immutable holder behind one volatile write, so the stamp and its note can never be read
     *  apart. See [overTempNote]. */
    private class ThermalVerdict(val nowMs: Long, val note: String?)

    @Volatile
    private var thermalVerdict: ThermalVerdict? = null

    fun registerBackend(backend: InferenceBackend) { backends[backend.id] = backend }

    /** After an IN-PLACE data wipe the app must re-earn warmup, or the forecast runs on empty
     *  context. */
    suspend fun resetWarmupLatch() = cycleMutex.withLock { warmupSatisfiedUpTo = 0 }

    /** Not serialised on [cycleMutex]: the fit touches no loaded handle and runs long enough that
     *  holding it would stall the forecast. [BaselineRunner] serialises fits against each other. */
    suspend fun fitBaseline(nowMs: Long, minCalWindows: Int): Result<BaselineFit> {
        val b = baseline ?: return Result.failure(IllegalStateException("no baseline runner"))
        val result = b.fit(history, nowMs, minCalWindows)
        if (result.isSuccess) {
            // Republish now so the drill-down shows the new provenance without waiting for a tick.
            _state.value = _state.value.copy(running = runningModels(), baselineModel = b.fitted)
        }
        return result
    }

    suspend fun restoreLast() {
        runCatching { baseline?.restore() }.onFailure { Timber.tag(TAG).w(it, "baseline restore failed") }
        // The baseline's row exists before any cycle or fit; publish it rather than leaving the
        // panel empty until the first forecast lands.
        if (baseline != null) {
            _state.value = _state.value.copy(running = runningModels(), baselineModel = baseline.fitted)
        }
        val last = runCatching { predictionStore.loadLast() }.getOrNull() ?: return
        if (last.isNotEmpty()) {
            // So a cold start does not show a lit forecast against a dark clock. A null belief
            // leaves the clock as it was.
            val sel = last.firstOrNull { it.selected }
            _state.value = _state.value.copy(
                predictions = last.sortedByDescending { it.selected },
                circadianTime = sel?.predictedTime ?: _state.value.circadianTime,
                circadianAnchorMs = sel?.predictedTime?.let { sel.anchorTsMs } ?: _state.value.circadianAnchorMs,
                note = "restored ${last.size} prediction(s) from last run",
            )
        }
    }

    /** Loads every discovered model up to the [maxRunningProvider] cap, falling back to the
     *  [StubBackend] when a `.pte` is absent or its load throws. Native loads run on the
     *  `inference` thread. */
    suspend fun refreshModels() = cycleMutex.withLock { refreshModelsLocked() }

    /** Call only while already holding [cycleMutex], which is not reentrant. Unlocked callers use
     *  the public [refreshModels]. */
    private suspend fun refreshModelsLocked() = withContext(dispatchers.inference) {
        if (!telemetryLoaded) {
            runCatching { telemetryStore?.load() }.getOrNull()?.let { cumulative.putAll(it) }
            telemetryLoaded = true
        }
        val discovered = store.discover()

        // Refresh is rare, so a full close/reload beats diffing and cannot leave a stale handle.
        loaded.values.forEach { runCatching { it.backend.close(it.handle) } }
        loaded.clear()
        installed.clear()
        for (b in discovered) installed[b.id] = b

        val cap = runCatching { maxRunningProvider() }.getOrNull()?.coerceAtLeast(1) ?: DEFAULT_MAX_RUNNING
        val runningIds = installed.keys.take(cap).toList()
        // A fitted baseline is a valid selection that is not in `runningIds` — it has no bundle to
        // discover — so it must survive a refresh, and with no neural model installed it is the only
        // thing left to select.
        selectedId = selectedId
            ?.takeIf { it in runningIds || (it == BASELINE_MODEL_ID && baseline?.fitted != null) }
            ?: runningIds.firstOrNull()
            ?: BASELINE_MODEL_ID.takeIf { baseline?.fitted != null }

        for (id in runningIds) loaded[id] = loadModel(id)

        val truncated = if (installed.size > cap)
            "running $cap of ${installed.size} installed models (cap in Settings → Forecast & models)"
        else null
        val note = when {
            discovered.isEmpty() && store.refused.isNotEmpty() ->
                "${store.refused.size} model(s) on device are built for another compute backend " +
                    "(${store.refused.distinct().joinToString()}) and this build runs none of them"
            discovered.isEmpty() ->
                "no model — add a server and Sync models (Settings → Server)"
            loaded.values.none { it.real } ->
                listOfNotNull(
                    "running on the StubBackend (no working .pte) — real forecast path blocked",
                    truncated,
                ).joinToString(" · ")
            loaded[selectedId]?.effectiveBackend == BackendId.STUB ->
                listOfNotNull(
                    "selected model has no working .pte — running on the StubBackend (real forecast path blocked)",
                    truncated,
                ).joinToString(" · ")
            else -> truncated
        }
        _state.value = _state.value.copy(
            running = runningModels(),
            metas = metasSnapshot(),
            telemetry = telemetrySnapshot(),
            note = note,
        )
        Timber.tag(TAG).i(
            "refreshModels models=%s active=%s",
            installed.keys, loaded[selectedId]?.effectiveBackend,
        )
    }

    /** The fp32 XNNPACK authority when its `.pte` loads, else the [StubBackend], which forecasts a
     *  fixed shape and is never `real` — so a failed load costs the display nothing and fails the
     *  dose path closed (§3.6-E) rather than promoting some other path. */
    private fun loadModel(id: String): Entry {
        val bundle = installed[id] ?: error("no bundle for $id")
        val backend = backends[BackendId.EXECUTORCH_XNNPACK_FP32]
        if (backend != null && bundle.pte.exists()) {
            val handle = runCatching { backend.load(bundle.descriptor, bundle.pte) }
                .onFailure { Timber.tag(TAG).w(it, "load failed for %s; falling back to the stub", id) }
                .getOrNull()
            if (handle != null) {
                return Entry(bundle, backend, handle, BackendId.EXECUTORCH_XNNPACK_FP32, bundle.precision, real = true)
            }
        }
        val handle = stub.load(bundle.descriptor, bundle.pte)
        return Entry(bundle, stub, handle, BackendId.STUB, Precision.FP32, real = false)
    }

    /** Debug-only, not wired in release. */
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

    /** Debug-only, not wired in release: an ELIGIBLE fan ramping [startBg] to [endBg], with a fixed
     *  ±15 mg/dL monotone band so the degeneracy guard passes it. */
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

    /** [real] is false when the [StubBackend] stood in for a missing or failed `.pte`; the
     *  calculator then treats the selected model as "no model" and fails closed (§3.6-E). */
    data class SelectedModelInfo(
        val id: String,
        val descriptor: ModelDescriptor,
        val backend: BackendId,
        val precision: Precision,
        val real: Boolean,
    )

    /** The selected model, whatever is serving it. `:calc` reads [real] and fails closed on the
     *  stub, so the dose path is on the fp32 XNNPACK authority or it does not run (§3.6-E). */
    fun selectedModelInfo(): SelectedModelInfo? {
        val id = selectedId ?: return null
        val e = loaded[id] ?: return null
        return SelectedModelInfo(id, e.bundle.descriptor, e.effectiveBackend, e.precision, e.real)
    }

    /** The DOSING path's provenance (§3.6-E): null unless a real `.pte` is loaded on the authority,
     *  so `:calc` fails closed on the stub and on the classical baseline alike. */
    fun authorityModelInfo(): SelectedModelInfo? =
        selectedModelInfo()?.takeIf { it.real && it.backend == BackendId.EXECUTORCH_XNNPACK_FP32 }

    /** Confined to the `inference` dispatcher and serialised on [cycleMutex] — never two forwards on
     *  the one command queue. Throws when nothing is selected; the caller fails closed. */
    suspend fun runSelected(input: GraphTensors): GraphOutput = cycleMutex.withLock {
        val id = selectedId ?: error("no selected model")
        val e = loaded[id] ?: error("selected model not loaded")
        withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
    }

    /** The DOSE path's forward (§3.6-E). Same confinement as [runSelected]. Throws unless the
     *  authority is what is loaded; the caller fails closed. */
    suspend fun runSelectedAuthority(input: GraphTensors): GraphOutput = cycleMutex.withLock {
        val id = selectedId ?: error("no selected model")
        val e = loaded[id]?.takeIf { it.real && it.effectiveBackend == BackendId.EXECUTORCH_XNNPACK_FP32 }
            ?: error("fp32 XNNPACK authority not loaded for $id")
        withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
    }

    /** [forecast] holds EVERY decoded slot, infill spans included, with `slotPatch` naming where
     *  each sits — not a trailing horizon. Nothing here is stored, pushed or read by an alarm. */
    data class MaskedRun(
        val modelId: String,
        val forecast: Forecast,
        val status: ForecastStatus,
        val nCtx: Int,
        val t: Int,
        val padPatches: Int,
        val firstForecastPatch: Int,
        val gridStartMs: Long,
        val anchorTsMs: Long,
        val synthetic: Boolean,
        val loraAttached: Boolean,
        val latencyMs: Double,
    )

    /** Call whenever an artifact changes under a fixed id: the parity verdict is remembered per
     *  model, so a replaced artifact would otherwise inherit the previous head's. */
    fun evictHead(modelId: String) = heads.evict(modelId)

    fun headState(modelId: String): HeadCache.State? =
        loaded[modelId]?.bundle?.let { heads.stateOf(it) }

    /** [spans] are context-relative patch indices; [withForecast] appends the future zone, and a
     *  pure infill passes false. A non-null [lora] assembles from the head re-run over the spline
     *  states built from `hidden`;
     *  a model whose head is absent or disagrees refuses rather than returning the unadapted fan. */
    suspend fun runMasked(
        modelId: String,
        series: BgSeries,
        channels: ModelChannels,
        future: ModelChannels?,
        spans: List<MaskSpan>,
        withForecast: Boolean,
        lora: LoraWeights?,
        synthetic: Boolean,
    ): MaskedRun = cycleMutex.withLock {
        val entry = loaded[modelId] ?: error("model $modelId is not loaded")
        val desc = entry.bundle.descriptor
        val gi = buildGraphInput(desc, series.mgdl, channels, future, spans, smoothingWindow(), withForecast)
        val t0 = System.nanoTime()
        val out = withContext(dispatchers.inference) { entry.backend.run(entry.handle, GraphIo.tensors(gi)) }
        val latMs = (System.nanoTime() - t0) / 1_000_000.0
        val steps = stepStates(entry.bundle, gi, out, needed = lora != null)
        heads.verify(entry.bundle, steps, out.headRaw, gi.mSlots)

        val headRaw: List<Double> = if (lora == null) {
            out.headRaw.map { it.toDouble() }
        } else {
            val state = heads.stateOf(entry.bundle)
            if (state !is HeadCache.State.Ready) {
                error("model $modelId takes no adapter: ${(state as? HeadCache.State.Unusable)?.why ?: "no head file"}")
            }
            val input = steps ?: error("the graph emitted no hidden state to adapt")
            state.head.setLora(lora)
            try {
                withContext(dispatchers.default) { state.head.forward(input, gi.mSlots) }
            } finally {
                state.head.setLora(null)
            }
        }
        val forecast = withContext(dispatchers.default) {
            native.assembleDecode(desc, headRaw, gi.anchors, gi.slotPatch, gi.nMasked, CARRY_SPREAD)
        }
        val status = withContext(dispatchers.default) { native.forecastDegeneracyCheck(desc, forecast) }
        MaskedRun(
            modelId = modelId,
            forecast = forecast,
            status = status,
            nCtx = gi.nCtx,
            t = gi.t,
            padPatches = gi.t - gi.nCtx - (if (withForecast) predSteps(desc) / desc.patchSize else 0),
            firstForecastPatch = gi.firstForecastPatch,
            gridStartMs = series.gridStartMs,
            anchorTsMs = series.anchorTsMs,
            synthetic = synthetic,
            loraAttached = lora != null,
            latencyMs = latMs,
        )
    }

    /** Windows are returned OLDEST-FIRST, which is what makes the fit's held-out split
     *  chronological. A window whose realised horizon carries a gap is DROPPED: a carried-forward
     *  value is a presentation step (`SPEC/invariants.md` §1), not glucose holding still. */
    suspend fun loraSamples(
        modelId: String,
        maxWindows: Int,
        strideSteps: Int,
        onWindow: ((done: Int, total: Int) -> Unit)? = null,
    ): List<LoraSample> {
        // Captured under the lock and re-checked under it before every forward: a discovery refresh
        // can close the handle mid-replay, and a forward on a closed handle is a native crash.
        val entry = cycleMutex.withLock { loaded[modelId] } ?: error("model $modelId is not loaded")
        val desc = entry.bundle.descriptor
        val ctxSteps = desc.minContextPatches * desc.patchSize
        val predSteps = predSteps(desc)
        val stride = strideSteps.coerceAtLeast(desc.patchSize)
        val want = ctxSteps + predSteps + stride * maxWindows.coerceAtLeast(1)
        val dense = history.recentBgSeries(want, ctxSteps + predSteps) ?: return emptyList()
        // The fit series has its own filter, length and grid origin, so project it onto the context
        // grid by TIMESTAMP: by index it would pair each window with glucose from another moment.
        val measured = history.fitBgSeries(want, ctxSteps + predSteps)
            ?: return emptyList()   // no measured series ⇒ no honest target; never the
                                    // carried-forward one (`SPEC/invariants.md` §1).
        val n = dense.mgdl.size
        val target = DoubleArray(n) { Double.NaN }
        for (i in measured.mgdl.indices) {
            val j = ((measured.gridStartMs - dense.gridStartMs) / GRID_MS).toInt() + i
            if (j in 0 until n) target[j] = measured.mgdl[i]
        }
        val ch = buildDoseChannels(dense)
        // Which slots of `dense` are the model's OWN OUTPUT (`SPEC/invariants.md` §1). `target` is
        // NaN at a sensor gap and at a reconstruction alike, so a test on it cannot tell them apart.
        val reconstructed = runCatching { history.reconstructedSlots(want) }.getOrElse { emptySet() }
        val out = ArrayList<LoraSample>(maxWindows)

        var origin = n - predSteps
        val origins = ArrayList<Int>()
        while (origin - ctxSteps >= 0 && origins.size < maxWindows) {
            origins.add(origin)
            origin -= stride
        }
        val total = origins.size
        var seen = 0
        // As long as the forecast horizon where the context has room, so the three geometries pose
        // the model a comparable question.
        val spanPatches = (predSteps / desc.patchSize).coerceIn(1, desc.maskSpanMax.coerceAtLeast(1))
        val ctxPatches = desc.minContextPatches
        for ((index, o) in origins.asReversed().withIndex()) {  // oldest first: chronological split
            onWindow?.invoke(seen++, total)
            val realized = target.copyOfRange(o, o + predSteps)
            if (realized.any { it.isNaN() }) continue
            val ctxFrom = o - ctxSteps
            val window = ModelChannels(
                ch.carb.copyOfRange(ctxFrom, o),
                ch.insulin.copyOfRange(ctxFrom, o),
                ch.exercise.copyOfRange(ctxFrom, o),
            )
            // The doses that ACTUALLY happened are a training window's announced plan; the no-event
            // baseline would teach the adapter to expect nothing.
            val ahead = ModelChannels(
                ch.carb.copyOfRange(o, o + predSteps),
                ch.insulin.copyOfRange(o, o + predSteps),
                ch.exercise.copyOfRange(o, o + predSteps),
            )
            // A backcast or an infill masks a run INSIDE the context and has no future zone, so its
            // target is that run's own realised glucose and it carries no counterfactual.
            val geometry = LoraGeometryPlan.geometryAt(index)
            val startPatch = LoraGeometryPlan.startPatch(geometry, ctxPatches, spanPatches)
            val isForecastWindow = geometry == MaskGeometry.FORECAST || startPatch == null
            // The ANCHOR must be a real measurement, and only the anchor: it is the one step the
            // pinball target, the baseline `d0` and both guard reads are measured from. Testing the
            // whole context instead reported "0 usable windows" on a record with one sensor change.
            val anchorStep = anchorStepOf(ctxFrom, o, startPatch, spanPatches, desc.patchSize)
            if (anchorStep !in target.indices || target[anchorStep].isNaN()) continue
            // No reconstruction anywhere in the context — the rule itself, not a proxy. A
            // carried-forward slot stays admissible: the model is conditioned on a dense context.
            if (reconstructed.isNotEmpty() &&
                (ctxFrom until o).any { dense.gridStartMs + it.toLong() * GRID_MS in reconstructed }
            ) {
                continue
            }
            val gi = buildGraphInput(
                desc,
                dense.mgdl.copyOfRange(ctxFrom, o),
                window,
                if (isForecastWindow) ahead else null,
                if (isForecastWindow) emptyList() else listOf(MaskSpan(startPatch!!, spanPatches)),
                smoothingWindow(),
                withForecast = isForecastWindow,
            )
            val spanTarget = if (isForecastWindow) {
                realized
            } else {
                val from = ctxFrom + startPatch!! * desc.patchSize
                target.copyOfRange(from, from + spanPatches * desc.patchSize)
            }
            // The branch that reads from inside the context; `realized` was checked above.
            if (spanTarget.any { it.isNaN() }) continue
            // ONE forward per lock acquisition: holding the mutex across a few hundred windows
            // would starve the live 5-minute forecast for as long as a fit runs.
            val run = cycleMutex.withLock {
                if (loaded[modelId] !== entry) return out
                withContext(dispatchers.inference) { entry.backend.run(entry.handle, GraphIo.tensors(gi)) }
            }
            val steps = stepStates(entry.bundle, gi, run, needed = true)
            heads.verify(entry.bundle, steps, run.headRaw, gi.mSlots)
            val hidden = steps ?: return out
            val d = desc.dModel * desc.patchSize

            // The SAME window with one unit of insulin added to the horizon's dose channel, so the
            // response is a property of the dose. FORECAST windows only: the guard reads at the
            // horizon's terminal step, which an infill does not have.
            val stimulus = if (!isForecastWindow) {
                null
            } else {
                probeInsulin?.action(PROBE_DOSE_U, ahead.insulin.size)
            }
            val probed = if (stimulus == null) null else ModelChannels(
                ahead.carb,
                DoubleArray(ahead.insulin.size) { i -> ahead.insulin[i] + stimulus.getOrElse(i) { 0.0 } },
                ahead.exercise,
            )
            val hiddenPert = if (probed == null) {
                null
            } else {
                val giPert = buildGraphInput(
                    desc,
                    dense.mgdl.copyOfRange(ctxFrom, o),
                    window,
                    probed,
                    emptyList(),
                    smoothingWindow(),
                    withForecast = true,
                )
                val runPert = cycleMutex.withLock {
                    if (loaded[modelId] !== entry) return out
                    withContext(dispatchers.inference) {
                        entry.backend.run(entry.handle, GraphIo.tensors(giPert))
                    }
                }
                stepStates(entry.bundle, giPert, runPert, needed = true)
            }

            out.add(
                LoraSample(
                    hidden = hidden.take(gi.nMasked * d),
                    anchors = gi.anchors.take(gi.nMasked),
                    targetBg = spanTarget.toList(),
                    nSlots = gi.nMasked,
                    // Either the WHOLE window or nothing; the crate refuses a truncated pairing.
                    hiddenPert = if (hiddenPert != null && hiddenPert.size >= gi.nMasked * d) {
                        hiddenPert.take(gi.nMasked * d)
                    } else {
                        emptyList()
                    },
                    isForecast = isForecastWindow,
                ),
            )
        }
        onWindow?.invoke(total, total)
        return out
    }

    /** Attaches nothing — the caller decides. */
    suspend fun trainLora(
        modelId: String,
        samples: List<LoraSample>,
        config: LoraConfig,
        opts: LoraTrainOpts,
        progress: LoraProgressSink? = null,
    ): LoraTrainResult {
        val entry = cycleMutex.withLock { loaded[modelId] } ?: error("model $modelId is not loaded")
        val state = heads.stateOf(entry.bundle)
        if (state !is HeadCache.State.Ready) {
            error("model $modelId takes no adapter: ${(state as? HeadCache.State.Unusable)?.why ?: "no head file"}")
        }
        // Deliberately OUTSIDE the cycle mutex: minutes of CPU touching no backend handle. It reads
        // the head's frozen weights and carries its own adapter through the gradient loop.
        return withContext(dispatchers.default) {
            native.loraTrain(state.head, entry.bundle.descriptor, samples, config, opts, progress)
        }
    }

    /** What a STORED adapter does to the model's marginal response to insulin — the measurement an
     *  imported or restored adapter carries no verdict for. The bar comes from
     *  [NativeCore.loraGuardOptsFit], so a probe and a fit cannot disagree about one adapter. */
    suspend fun guardAdapter(
        modelId: String,
        weights: LoraWeights,
        samples: List<LoraSample>,
    ): LoraGuardReport {
        val entry = cycleMutex.withLock { loaded[modelId] } ?: error("model $modelId is not loaded")
        val state = heads.stateOf(entry.bundle)
        if (state !is HeadCache.State.Ready) {
            error("model $modelId takes no adapter: ${(state as? HeadCache.State.Unusable)?.why ?: "no head file"}")
        }
        // Outside the cycle mutex for the reason the fit is: head-only arithmetic, no handle.
        return withContext(dispatchers.default) {
            native.loraGuard(
                state.head,
                entry.bundle.descriptor,
                samples,
                weights,
                native.loraGuardOptsFit(),
            )
        }
    }

    fun descriptorOf(modelId: String): ModelDescriptor? = loaded[modelId]?.bundle?.descriptor

    /** A no-op when [id] is not in the running set. Every running model keeps forecasting;
     *  selection governs only the DISPLAYED forecast, the circadian belief and the dosing authority.
     *  The predictions are re-flagged at once so the panel switches without waiting for a cycle. */
    suspend fun selectModel(id: String) {
        // The write and the re-flag race refreshModelsLocked on the inference thread. Release BEFORE
        // refreshModels(), which re-acquires the non-reentrant mutex.
        cycleMutex.withLock {
            val isFittedBaseline = id == BASELINE_MODEL_ID && baseline?.fitted != null
            if (id !in loaded.keys && !isFittedBaseline) return
            selectedId = id
            val hasTime = loaded[id]?.bundle?.descriptor?.time != null
            val sel = _state.value.predictions.firstOrNull { it.modelId == id }
            _state.value = _state.value.copy(
                running = runningModels(),
                predictions = _state.value.predictions
                    .map { it.copy(selected = it.modelId == id) }
                    .sortedByDescending { it.selected },
                // A model with no time head clears the belief rather than leaving the previous
                // model's on screen, frozen at the instant of the switch.
                circadianTime = if (hasTime) sel?.predictedTime ?: _state.value.circadianTime else null,
                circadianAnchorMs = if (hasTime) {
                    sel?.predictedTime?.let { sel.anchorTsMs } ?: _state.value.circadianAnchorMs
                } else {
                    null
                },
                circadianLowContext = hasTime && _state.value.circadianLowContext,
                selectedHasTimeSection = hasTime,
            )
        }
        refreshModels()
    }

    /** Deletes the pair from disk and purges the in-memory footprint: telemetry, latency window,
     *  backend preference and any standing prediction, so the graph stops drawing a fan for a model
     *  that no longer exists. Returns whether an artifact was actually removed. */
    suspend fun deleteModel(id: String): Boolean = cycleMutex.withLock {
        heads.evict(id)
        // The baseline has no artifact to unlink; discarding the fitted weights is the same act.
        if (id == BASELINE_MODEL_ID) {
            val had = baseline?.fitted != null
            baseline?.clear()
            if (selectedId == BASELINE_MODEL_ID) selectedId = null
            // Stale counters would otherwise be attributed to the next fit under the same id.
            cumulative.remove(id); latencySamples.remove(id)
            runCatching { telemetryStore?.save(HashMap(cumulative)) }
            refreshModelsLocked()
            _state.value = _state.value.copy(
                running = runningModels(),
                baselineModel = null,
                predictions = _state.value.predictions.filterNot { it.modelId == id },
            )
            return@withLock had
        }
        val removed = withContext(dispatchers.inference) { runCatching { store.delete(id) }.getOrDefault(false) }
        cumulative.remove(id); latencySamples.remove(id)
        runCatching { telemetryStore?.save(HashMap(cumulative)) }
        refreshModelsLocked() // already under cycleMutex — the public refreshModels would self-deadlock
        _state.value = _state.value.copy(predictions = _state.value.predictions.filterNot { it.modelId == id })
        removed
    }

    /** The banner note while BLOCKED, else null. A history-fed cycle passes this gate twice, so a
     *  second call with the same [cycleNowMs] reuses the verdict: one temperature read, and ONE
     *  advance of the hysteresis latch. A per-cycle share, deliberately not a wall-clock cache. */
    private suspend fun overTempNote(cycleNowMs: Long): String? {
        thermalVerdict?.takeIf { it.nowMs == cycleNowMs }?.let { return it.note }
        val t = thermalProvider() ?: run {
            thermalBlocked = false
            thermalVerdict = ThermalVerdict(cycleNowMs, null)
            return null
        }
        val block = if (thermalBlocked) t.currentC > t.thresholdC - t.resumeMarginC
                    else t.currentC >= t.thresholdC
        thermalBlocked = block
        val note = if (block) "inference paused — device at %.1f°C ≥ %.1f°C threshold".format(t.currentC, t.thresholdC) else null
        thermalVerdict = ThermalVerdict(cycleNowMs, note)
        return note
    }

    suspend fun runFromHistory(cause: InferenceCause = InferenceCause.GRID_TICK, nowMs: Long) {
        // Size the context and the warmup gate on the SELECTED model's descriptor; with N running
        // models `loaded.values.first()` is the first-discovered, not the selected one. Snapshot
        // under the lock, then release: the calls below each re-acquire the non-reentrant mutex.
        val (descAny, selReal, selHasTime) = cycleMutex.withLock {
            val selEntry = loaded[selectedId]
            val desc = (selEntry ?: loaded.values.firstOrNull())?.bundle?.descriptor
            Triple(desc, selEntry?.real ?: false, selEntry?.bundle?.descriptor?.time != null)
        }
        // A fitted baseline is a running model with no descriptor. Without this fallback a
        // baseline-only device returns here every tick and never forecasts.
        val baselineOnly = descAny == null && baseline?.fitted != null
        if (descAny == null && !baselineOnly) { refreshModels(); return }
        // Before the warmup gate, so the banner is the over-temp one rather than a warmup state;
        // `copy` preserves circadianTime, so the clock stays lit. runCycle is the real chokepoint.
        overTempNote(nowMs)?.let { note ->
            _state.value = _state.value.copy(
                predictions = emptyList(), lastCause = InferenceCause.OVER_TEMPERATURE, note = note,
            )
            Timber.tag(TAG).i(note)
            return
        }
        val minSteps = descAny?.let { it.minContextPatches * it.patchSize }
            ?: NO_DESCRIPTOR_MIN_STEPS
        val maxSteps = descAny?.let { it.maxContextPatches * it.patchSize }
            ?: NO_DESCRIPTOR_MAX_STEPS

        // WARMUP gate (inference-runtime.md): withhold until `warmupHours` of MEASURED context has
        // accrued, floored at the model's MIN_CONTEXT. Distinct from the freshness gate; both stand.
        val minContextHours = minSteps * GRID_MS / MS_PER_HOUR
        val requiredHours = warmupHoursProvider().coerceAtLeast(minContextHours)
        val requiredSteps = Math.round(requiredHours * MS_PER_HOUR / GRID_MS).toInt()
        val measuredSteps = runCatching { history.measuredStepsInWindow(requiredSteps) }.getOrDefault(0)
        // A passive advertisement CGM never fills every slot, so completion needs only
        // WARMUP_COMPLETION_FRACTION coverage, and then latches monotonically.
        val completionSteps = kotlin.math.ceil(requiredSteps * WARMUP_COMPLETION_FRACTION).toInt()
        if (measuredSteps >= completionSteps) warmupSatisfiedUpTo = maxOf(warmupSatisfiedUpTo, requiredSteps)
        val warmedUp = measuredSteps >= completionSteps || requiredSteps <= warmupSatisfiedUpTo
        if (!warmedUp) {
            val measuredHours = measuredSteps * GRID_MS / MS_PER_HOUR
            // The circadian belief is neither a glucose forecast nor a dosing signal and degrades
            // gracefully, so it is published during warmup as low-context. Only when the SELECTED
            // model has a time head, or it would publish another model's belief under the selection.
            val warmupBelief = if (selHasTime) {
                runCatching { circadianDuringWarmup() }.getOrNull()
            } else {
                null
            }
            // The BASELINE is not gated by this window: it reads `nLags` trailing values rather than
            // 96–288 steps, and it cannot reach the calculator, the ISF/ICR probe or the rolled
            // overlay, which all fail closed without a graph. Its own guards still decide.
            val warmupBaseline = runCatching { baselineDuringWarmup(nowMs) }
                .getOrElse { Timber.tag(TAG).w(it, "baseline cycle failed during warmup"); null }
            _state.value = _state.value.copy(
                // Only the baseline's; the neural fan stays suppressed.
                predictions = listOfNotNull(warmupBaseline),
                lastCause = InferenceCause.COLLECTING_CONTEXT,
                warmup = com.t1dm.core.model.WarmupProgress(measuredHours, requiredHours),
                circadianTime = warmupBelief?.first,
                circadianAnchorMs = warmupBelief?.second,
                circadianLowContext = warmupBelief != null,
                realBackendAvailable = selReal,
                selectedHasTimeSection = selHasTime,
                note = "collecting context — %.1f / %.0f h of measured data".format(measuredHours, requiredHours),
            )
            Timber.tag(TAG).i(
                "warmup: %.1f/%.0f h measured — neural suppressed, baseline=%s; circadian=%s",
                measuredHours, requiredHours,
                if (warmupBaseline != null) "published" else "n/a",
                warmupBelief?.let { "%.2fh R=%.3f".format(it.first.predictedHour, it.first.resultantR) } ?: "n/a",
            )
            return
        }

        val series = history.recentBgSeries(maxSteps, minSteps)
        if (series == null) {
            _state.value = _state.value.copy(
                predictions = emptyList(),
                lastCause = InferenceCause.COLLECTING_CONTEXT,
                circadianTime = null,
                circadianAnchorMs = null,
                circadianLowContext = false,
                realBackendAvailable = selReal,
                selectedHasTimeSection = selHasTime,
                note = "collecting context — needs ≥${minSteps / 12} h of BG",
            )
            Timber.tag(TAG).i("cycle skipped: still collecting context")
            return
        }
        runCycle(cause, series, nowMs)
    }

    /** Public so the service can drive a synthetic or manual cycle with no sensor present. */
    suspend fun runCycle(cause: InferenceCause, series: BgSeries, nowMs: Long) = cycleMutex.withLock {
        // A fitted baseline is a running model in its own right, so a device with no `.pte` pushed
        // still has something to publish.
        if (loaded.isEmpty() && baseline?.fitted == null) {
            refreshOrNote()
            if (loaded.isEmpty() && baseline?.fitted == null) return@withLock
        }
        // The UNIVERSAL chokepoint: grid tick, manual and synthetic all funnel through here.
        // `copy` leaves circadianTime untouched, so the clock stays lit while inference is paused.
        overTempNote(nowMs)?.let { note ->
            _state.value = _state.value.copy(
                predictions = emptyList(), lastCause = InferenceCause.OVER_TEMPERATURE, note = note,
            )
            Timber.tag(TAG).i(note)
            return@withLock
        }
        val cycleTs = snapToGrid(nowMs)
        val stale = (nowMs - series.anchorTsMs) > freshnessThresholdMs
        val t0 = System.nanoTime()
        val preds = ArrayList<ModelPrediction>(loaded.size)

        // ONE shared context build across the running set (SPEC §3.3), aligned to the BG grid.
        // Model-independent: the per-descriptor normalization happens in build_context.
        val doseChannels = buildDoseChannels(series)

        // ONE shared PREDICTION-ZONE build (SPEC §3.3), on the SAME curve engine the calculator's
        // baseline roll uses, so a just-logged meal RAISES the forecast rather than vanishing at the
        // boundary. Anchored to the SELECTED model's descriptor for a stable pred-zone length.
        val anchorDesc = (loaded[selectedId] ?: loaded.values.firstOrNull())?.bundle?.descriptor
        val futureChannels = anchorDesc?.let { buildFutureChannels(series, it) }

        // Serial: never two forwards on the one command queue.
        for ((id, entry) in loaded) {
            val pred = runCatching { runOne(entry, id == selectedId, series, doseChannels, futureChannels, cycleTs, stale) }
                .getOrElse {
                    Timber.tag(TAG).w(it, "model %s cycle failed", id); null
                }
            if (pred != null) preds.add(pred)
        }

        // Same anchor and grid, but off the RAW series and its own causal IOB/COB — see
        // [BaselineRunner].
        baseline?.let { b ->
            val pred = runCatching { b.predict(series, cycleTs, selectedId == BASELINE_MODEL_ID, stale) }
                .getOrElse { Timber.tag(TAG).w(it, "baseline cycle failed"); null }
            if (pred != null) {
                preds.add(pred)
                pred.latencyMs?.let { recordLatency(BASELINE_MODEL_ID, it); recordCumulative(BASELINE_MODEL_ID, it) }
            }
        }
        preds.sortByDescending { it.selected }

        val durationMs = ((System.nanoTime() - t0) / 1_000_000.0).toLong()
        val selPred = preds.firstOrNull { it.selected }
        // False for the classical baseline, and for any neural export cut without a time head.
        val selHasTime = loaded[selectedId]?.bundle?.descriptor?.time != null
        _state.value = _state.value.copy(
            running = runningModels(),
            predictions = preds,
            latencies = latencySnapshot(),
            metas = metasSnapshot(),
            telemetry = telemetrySnapshot(),
            lastCycleTsMs = cycleTs,
            lastCause = cause,
            lastCycleDurationMs = durationMs,
            realBackendAvailable = if (selectedId == BASELINE_MODEL_ID) {
                baseline?.fitted != null
            } else {
                loaded[selectedId]?.real ?: false
            },
            // Keep the last belief across a TRANSIENT decode failure rather than blinking the clock
            // off under a live forecast — but never across a switch to a model with no time head, or
            // the belief on screen belongs to a different model.
            circadianTime = if (selHasTime) selPred?.predictedTime ?: _state.value.circadianTime else null,
            circadianAnchorMs = if (selHasTime) {
                selPred?.predictedTime?.let { selPred.anchorTsMs } ?: _state.value.circadianAnchorMs
            } else {
                null
            },
            circadianLowContext = selHasTime && selPred?.predictedTime == null && _state.value.circadianLowContext,
            selectedHasTimeSection = selHasTime,
            baselineModel = baseline?.fitted,
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

    /** Null runs frozen — no adapter attached, or no store wired. NOT a fallback: an attached
     *  adapter that cannot be applied THROWS, dropping that model's prediction for the cycle rather
     *  than mixing two forecasters in one model's history. */
    private suspend fun adaptedHeadRaw(entry: Entry, gi: GraphInput, out: GraphOutput): List<Double>? {
        val store = loraStore ?: return null
        val w = store.attached(entry.bundle.id) ?: return null
        val state = heads.stateOf(entry.bundle)
        if (state !is HeadCache.State.Ready) {
            error(
                "adapter attached to ${entry.bundle.id} but its head is unusable: " +
                    ((state as? HeadCache.State.Unusable)?.why ?: "no head file"),
            )
        }
        val steps = stepStates(entry.bundle, gi, out, needed = true)
            ?: error("adapter attached to ${entry.bundle.id} but the graph emits no hidden state")
        state.head.setLora(w)
        return try {
            withContext(dispatchers.default) { state.head.forward(steps, gi.mSlots) }
        } finally {
            state.head.setLora(null)
        }
    }

    /** The head's per-step input, or null when the export emits no `hidden`. Skipped entirely when
     *  [needed] is false and the head's parity is already settled: the spline is `M·S·D` of work
     *  that a frozen, already-verified cycle never reads. */
    private suspend fun stepStates(
        bundle: ModelBundle,
        gi: GraphInput,
        out: GraphOutput,
        needed: Boolean,
    ): List<Double>? {
        val hidden = out.hidden ?: return null
        if (!needed && !heads.needsVerify(bundle)) return null
        return withContext(dispatchers.default) {
            native.stepStates(bundle.descriptor, hidden.toList(), gi.slotPatch, gi.attnMask.toList())
        }
    }

    /** For the dose path, which must score on the same forecaster the panel draws. `null` is the
     *  frozen model; an ATTACHED adapter that cannot be applied THROWS, so the roll fails closed
     *  rather than scoring a dose on a different model from the displayed one (§3.6-E). */
    suspend fun adaptedHeadRawFor(modelId: String, out: GraphOutput, gi: GraphInput): List<Double>? {
        val store = loraStore ?: return null
        val w = store.attached(modelId) ?: return null
        val entry = cycleMutex.withLock { loaded[modelId] } ?: error("model $modelId is not loaded")
        val steps = stepStates(entry.bundle, gi, out, needed = true)
        // The dose path can be the FIRST caller after a process start, so the head is proved against
        // this very forward rather than assuming a cycle already did it.
        heads.verify(entry.bundle, steps, out.headRaw, gi.mSlots)
        val state = heads.stateOf(entry.bundle)
        if (state !is HeadCache.State.Ready) {
            error("adapter attached to $modelId but its head is unusable: ${(state as? HeadCache.State.Unusable)?.why ?: "no head file"}")
        }
        val input = steps ?: error("adapter attached to $modelId but the graph emits no hidden state")
        state.head.setLora(w)
        return try {
            withContext(dispatchers.default) { state.head.forward(input, gi.mSlots) }
        } finally {
            state.head.setLora(null)
        }
    }

    private suspend fun runOne(
        entry: Entry,
        selected: Boolean,
        series: BgSeries,
        doseChannels: ModelChannels,
        futureChannels: ModelChannels?,
        cycleTs: Long,
        stale: Boolean,
    ): ModelPrediction {
        val desc = entry.bundle.descriptor
        val gi = buildGraphInput(desc, series.mgdl, doseChannels, futureChannels, emptyList(), smoothingWindow())
        val input = GraphIo.tensors(gi)

        val t0 = System.nanoTime()
        val out = withContext(dispatchers.inference) { entry.backend.run(entry.handle, input) }
        val latMs = (System.nanoTime() - t0) / 1_000_000.0
        recordLatency(entry.bundle.id, latMs)
        recordCumulative(entry.bundle.id, latMs)

        heads.verify(entry.bundle, stepStates(entry.bundle, gi, out, needed = false), out.headRaw, gi.mSlots)
        val adapted = adaptedHeadRaw(entry, gi, out)

        // Slice by patch rather than by count, so an added infill span cannot shift which rows the
        // panel, the alarms and the calculator read.
        val forecast: Forecast = withContext(dispatchers.default) {
            val all = native.assembleDecode(
                desc,
                adapted ?: out.headRaw.map { it.toDouble() },
                gi.anchors,
                gi.slotPatch,
                gi.nMasked,
                CARRY_SPREAD,
            )
            native.forecastSlice(all, gi.firstForecastPatch, gi.t)
        }
        val anchorBg = gi.anchors.getOrElse(gi.slotPatch.indexOf(gi.firstForecastPatch)) { Double.NaN }
        val status: ForecastStatus =
            withContext(dispatchers.default) { native.forecastDegeneracyCheck(desc, forecast) }

        // Fail-OPEN to null, so the time probe can never perturb the BG forecast above.
        val predictedTime: PredictedTime? = decodeTimeSafely(desc, out.timeLogits)

        return ModelPrediction(
            modelId = entry.bundle.id,
            cycleTsMs = cycleTs,
            anchorTsMs = series.anchorTsMs,
            sourceId = series.sourceId,
            stepMs = GRID_MS,
            medianBg = forecast.medianBg,
            bandsMgdl = forecast.bandsMgdl,
            nQuantiles = N_QUANTILES,
            lastBg = anchorBg,
            status = status,
            backend = entry.effectiveBackend,
            precision = entry.precision,
            selected = selected,
            stale = stale,
            latencyMs = latMs,
            predictedTime = predictedTime,
        )
    }

    /** Fail-OPEN: null unless the descriptor declares a time section AND the tensor is a matching
     *  flat `(P, nBins)`. Never throws — a time-probe hiccup must not touch the BG forecast. */
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

    /** The series is asked for at the BASELINE's own floor — `nLags` trailing steps — not the neural
     *  minimum, which is the whole reason this runs. Never marked `selected`: selection drives the
     *  calculator and the rolled overlay, which fail closed on a model with no graph. */
    private suspend fun baselineDuringWarmup(nowMs: Long): ModelPrediction? {
        val b = baseline ?: return null
        val lags = b.fitted?.spec?.nLags ?: return null
        val series = history.recentBgSeries(NO_DESCRIPTOR_MAX_STEPS, lags) ?: return null
        return b.predict(
            series,
            snapToGrid(nowMs),
            selected = false,
            stale = (nowMs - series.anchorTsMs) > freshnessThresholdMs,
        )
    }

    private suspend fun circadianDuringWarmup(): Pair<PredictedTime, Long>? {
        val id = selectedId ?: return null
        val entry = loaded[id] ?: return null
        if (!entry.real) return null                       // StubBackend has no circadian probe
        val desc = entry.bundle.descriptor
        if (desc.time == null) return null                 // model has no hour-of-day head
        val minSteps = desc.minContextPatches * desc.patchSize
        val maxSteps = desc.maxContextPatches * desc.patchSize
        val series = runCatching { history.recentBgSeries(maxSteps, minSteps) }.getOrNull() ?: return null
        return cycleMutex.withLock {
            runCatching {
                val doseChannels = buildDoseChannels(series)
                val futureChannels = buildFutureChannels(series, desc)
                val gi = buildGraphInput(
                    desc, series.mgdl, doseChannels, futureChannels, emptyList(), smoothingWindow(),
                )
                val out = withContext(dispatchers.inference) {
                    entry.backend.run(entry.handle, GraphIo.tensors(gi))
                }
                decodeTimeSafely(desc, out.timeLogits)?.let { it to series.anchorTsMs }
            }.getOrElse {
                Timber.tag(TAG).w(it, "warmup circadian forward failed; predicted hour omitted")
                null
            }
        }
    }

    /** Aligned to `series.gridStartMs` (SPEC §3.3). A missing or failed source, or a length
     *  mismatch, falls back to the `normalize(0)` no-dose baseline. */
    private suspend fun buildDoseChannels(series: BgSeries): ModelChannels {
        val n = series.mgdl.size
        val src = contextChannels ?: return ModelChannels.zero(n)
        return runCatching {
            val ch = src.channels(series.gridStartMs, n)
            if (ch.carb.size == n && ch.insulin.size == n && ch.exercise.size == n) ch
            else ModelChannels.zero(n)
        }.getOrElse {
            Timber.tag(TAG).w(it, "context channel build failed; falling back to no-event baseline")
            ModelChannels.zero(n)
        }
    }

    /** Aligned to the grid boundary one step past the last context sample, so the tail continues
     *  seamlessly from the [contextChannels] past; length is the fixed pred zone (P·S). Null ⇒ the
     *  `normalize(0)` no-dose baseline. Same engine as `RollingForecaster` (SPEC §3.3). */
    private suspend fun buildFutureChannels(series: BgSeries, desc: ModelDescriptor): ModelChannels? {
        val src = futureOverrides ?: return null
        val predSteps = predSteps(desc)
        if (predSteps <= 0) return null
        val rollStartMs = series.gridStartMs + series.mgdl.size.toLong() * GRID_MS
        return runCatching {
            src.overrides(rollStartMs, predSteps)
        }.getOrElse {
            Timber.tag(TAG).w(it, "future-override build failed; prediction zone falls back to no-dose baseline")
            null
        }
    }

    /** P·S, mirroring `RollingForecaster`. */
    private fun predSteps(desc: ModelDescriptor): Int =
        (desc.predictionHorizonHours * STEPS_PER_HOUR / desc.patchSize) * desc.patchSize

    /** A null [future] seeds the pred-zone dose slots to `normalize(0)`. Channel order is fixed
     *  carb-insulin-exercise at every slot, context and announced alike, identical to
     *  `RollingForecaster` — no swap. */
    private suspend fun buildGraphInput(
        desc: ModelDescriptor,
        mgdl: DoubleArray,
        ch: ModelChannels,
        future: ModelChannels?,
        maskSpans: List<MaskSpan>,
        smoothingWindow: Int,
        withForecast: Boolean = true,
    ): GraphInput =
        withContext(dispatchers.default) {
            val predSteps = predSteps(desc)
            fun ann(pick: (ModelChannels) -> DoubleArray) =
                if (!withForecast) null
                else future?.let { f -> List(predSteps) { pick(f).getOrElse(it) { 0.0 } } }
            native.buildGraphInput(
                desc,
                mgdl.toList(),
                ch.carb.toList(),
                ch.insulin.toList(),
                ch.exercise.toList(),
                ann { it.carb },
                ann { it.insulin },
                ann { it.exercise },
                maskSpans,
                withForecast = withForecast,
                smoothingWindow = smoothingWindow,
            )
        }

    /** Snapped to an offered detent; read fresh per cycle, so a Settings edit takes on the next
     *  tick. */
    private suspend fun smoothingWindow(): Int =
        InferenceControllerDefaults.nearestSmoothingStop(
            runCatching { smoothingWindowProvider() }.getOrNull() ?: InferenceControllerDefaults.SAVGOL_WINDOW,
        )

    /** The baseline is listed whether or not it has been fitted — it is not discovered from disk, so
     *  hiding it until a fit would leave its own Fit action nowhere to live.
     *  [InferenceState.baselineModel] says which of the two states it is in. */
    private fun runningModels(): List<RunningModel> {
        val neural = loaded.map { (id, e) ->
            RunningModel(id, e.effectiveBackend, e.precision, id == selectedId)
        }
        if (baseline == null) return neural
        return neural + RunningModel(
            modelId = BASELINE_MODEL_ID,
            backend = BackendId.NATIVE_RIDGE_FP64,
            precision = Precision.FP64,
            selected = selectedId == BASELINE_MODEL_ID,
        )
    }

    /** `.pte` filenames, NOT the descriptor `model_id`, which can diverge from the filename for an
     *  adb-pushed model. The sync coordinator keys on this to decide whether a server update would
     *  overwrite a LIVE artifact (stage it) or a new one (apply in place). */
    fun runningArtifactFileNames(): Set<String> = loaded.values.map { it.bundle.pte.name }.toSet()

    private fun recordLatency(id: String, ms: Double) {
        val q = latencySamples.getOrPut(id) { ArrayDeque() }
        q.addLast(ms)
        while (q.size > LATENCY_WINDOW) q.removeFirst()
    }

    private fun recordCumulative(id: String, ms: Double) {
        val cur = cumulative[id] ?: CumulativeTelemetry(0, 0.0)
        cumulative[id] = CumulativeTelemetry(cur.predictions + 1, cur.totalInferenceMs + ms)
    }

    private fun metasSnapshot(): List<ModelMeta> = loaded.values.map { it.bundle.meta }

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

    /** Its sole caller [runCycle] already holds [cycleMutex], so this takes the lock-free path. */
    private suspend fun refreshOrNote() {
        if (loaded.isEmpty()) refreshModelsLocked()
    }

    companion object {
        const val DEFAULT_MAX_RUNNING = 5
        const val TAG = "CycleRunner"
        const val GRID_MS = 300_000L
        const val MS_PER_HOUR = 3_600_000.0
        /** 5-min grid ⇒ 12 steps/hour (mirrors calc HorizonPolicy.STEPS_PER_HOUR). */
        const val STEPS_PER_HOUR = 12
        /** Hours (inference-runtime.md); the setting floors at MIN_CONTEXT = 8 h. */
        const val DEFAULT_WARMUP_HOURS = 24.0
        /** Fraction of the required window covered by MEASURED slots, since a passive advertisement
         *  CGM leaves gaps and a gapless demand made completion flap. */
        const val WARMUP_COMPLETION_FRACTION = 0.85
        const val N_QUANTILES = 7
        /** Empty: the cycle forecast is one ≤2 h window with no seam to carry across. §9's per-level
         *  carry belongs to `:calc`'s RollingForecaster. */
        val CARRY_SPREAD = emptyList<Double>()
        const val LATENCY_WINDOW = 60

        /** 8 h and 24 h of steps, for a cycle with no descriptor to size it from. Deliberately NOT
         *  the neural bounds, which run to days: a baseline-only device must not wait a week on a
         *  gate that is not about it. */
        const val NO_DESCRIPTOR_MIN_STEPS = 96
        const val NO_DESCRIPTOR_MAX_STEPS = 288

        fun snapToGrid(ts: Long): Long = Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        fun percentile(sortedAsc: List<Double>, q: Double): Double {
            if (sortedAsc.isEmpty()) return 0.0
            val idx = (q * (sortedAsc.size - 1)).toInt().coerceIn(0, max(0, sortedAsc.size - 1))
            return sortedAsc[idx]
        }
    }
}
