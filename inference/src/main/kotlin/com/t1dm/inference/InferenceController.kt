package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendId
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

/** Owns running set, backend handles, state; cycleMutex serialises cycles off one APU/CPU queue. */
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
    /** Already-logged action absorbing past the now-boundary (SPEC §3.3); null=normalize(0). */
    private val futureOverrides: FutureOverrideSource? = null,
    /** Read FRESH each cycle; MIN_CONTEXT is the binding floor (inference-runtime.md). */
    private val warmupHoursProvider: suspend () -> Double = { DEFAULT_WARMUP_HOURS },
    /** Null ⇒ session-only in-memory counters. */
    private val telemetryStore: TelemetryStore? = null,
    /** Battery-sensor °C, read FRESH; null never gates. No DEATH check, thermal is a hw risk. */
    private val thermalProvider: suspend () -> ThermalStatus? = { null },
    /** INFERENCE.md §7.1, read FRESH; snapped to a detent, a corrupt setting never reaches Rust. */
    private val smoothingWindowProvider: suspend () -> Int = { InferenceControllerDefaults.SAVGOL_WINDOW },
    /** Null ⇒ the counterfactual branch never runs, so every adapter stays `ABSENT`. */
    private val probeInsulin: ProbeInsulinPort? = null,
    /** Re-read every cycle. Null ⇒ every model runs frozen. */
    private val loraStore: LoraStore? = null,
) {
    private val _state = MutableStateFlow(InferenceState())
    val state: StateFlow<InferenceState> = _state.asStateFlow()

    /** Every prediction was conditioned on the OUTGOING sensor's history; running set survives. */
    fun onCgmSourceChanged() {
        // update not value=value.copy(): called outside the cycle mutex, races a publishing cycle.
        _state.update { it.copy(predictions = emptyList(), lastCycleTsMs = null, lastCause = null) }
    }

    private val stub = StubBackend()
    private val backends = HashMap<BackendId, InferenceBackend>()

    /** Opened lazily, verified against the graph on first use; only the adapter path reads them. */
    private val heads = HeadCache(native)

    private data class Entry(
        val bundle: ModelBundle,
        val backend: InferenceBackend,
        val handle: LoadedModel,
        val effectiveBackend: BackendId,
        val real: Boolean,
    )

    private val loaded = LinkedHashMap<String, Entry>()
    /** Every DISCOVERED model, before load; ModelStore admits only XNNPACK, one bundle per id. */
    private val installed = LinkedHashMap<String, ModelBundle>()
    /** Written under cycleMutex on inference thread, read unlocked in runFromHistory's preamble. */
    @Volatile
    private var selectedId: String? = null
    private val latencySamples = HashMap<String, ArrayDeque<Double>>()
    /** Durable via [telemetryStore]; loaded once, then in-memory. */
    private val cumulative = HashMap<String, CumulativeTelemetry>()
    private var telemetryLoaded = false
    /** Largest requiredSteps window EVER met; warmup latches monotonically, in-memory only. */
    @Volatile
    private var warmupSatisfiedUpTo = 0
    private val cycleMutex = Mutex()
    /** Latches at thresholdC, clears below thresholdC-resumeMarginC to avoid flapping; memory. */
    @Volatile
    private var thermalBlocked = false

    /** One immutable holder behind one volatile write, so stamp and note never read apart. */
    private class ThermalVerdict(val nowMs: Long, val note: String?)

    @Volatile
    private var thermalVerdict: ThermalVerdict? = null

    fun registerBackend(backend: InferenceBackend) { backends[backend.id] = backend }

    /** After an IN-PLACE data wipe, re-earn warmup, or the forecast runs on empty context. */
    suspend fun resetWarmupLatch() = cycleMutex.withLock { warmupSatisfiedUpTo = 0 }

    suspend fun restoreLast() {
        val last = runCatching { predictionStore.loadLast() }.getOrNull() ?: return
        if (last.isNotEmpty()) {
            // Cold start won't show a lit forecast on a dark clock; null belief leaves it be.
            val sel = last.firstOrNull { it.selected }
            _state.value = _state.value.copy(
                predictions = last.sortedByDescending { it.selected },
                circadianTime = sel?.predictedTime ?: _state.value.circadianTime,
                circadianAnchorMs = sel?.predictedTime?.let { sel.anchorTsMs } ?: _state.value.circadianAnchorMs,
                note = "restored ${last.size} prediction(s) from last run",
            )
        }
    }

    /** Loads discovered models up to maxRunningProvider, falls to StubBackend on bad .pte. */
    suspend fun refreshModels() = cycleMutex.withLock { refreshModelsLocked() }

    /** Call only while holding cycleMutex (not reentrant); unlocked callers use refreshModels. */
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
        selectedId = selectedId?.takeIf { it in runningIds } ?: runningIds.firstOrNull()

        for (id in runningIds) loaded[id] = loadModel(id)

        val truncated = if (installed.size > cap)
            "running $cap of ${installed.size} installed models (cap in Settings → Forecast & models)"
        else null
        val note = when {
            discovered.isEmpty() && store.refused.isNotEmpty() ->
                "${store.refused.size} model(s) on device are built for another compute backend " +
                    "(${store.refused.distinct().joinToString()}) and this build runs none of them"
            discovered.isEmpty() ->
                "no model — adb push a .pte and its descriptor.json"
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
        // runFromHistory returns at descAny == null before its own clear; nothing else drops these.
        val noModel = installed.isEmpty()
        _state.value = _state.value.copy(
            running = runningModels(),
            metas = metasSnapshot(),
            telemetry = telemetrySnapshot(),
            note = note,
            predictions = if (noModel) emptyList() else _state.value.predictions,
            circadianTime = if (noModel) null else _state.value.circadianTime,
            circadianAnchorMs = if (noModel) null else _state.value.circadianAnchorMs,
            circadianLowContext = if (noModel) false else _state.value.circadianLowContext,
        )
        Timber.tag(TAG).i(
            "refreshModels models=%s active=%s",
            installed.keys, loaded[selectedId]?.effectiveBackend,
        )
    }

    /** fp32 XNNPACK authority when .pte loads, else StubBackend (never real, dose fails closed). */
    private fun loadModel(id: String): Entry {
        val bundle = installed[id] ?: error("no bundle for $id")
        val backend = backends[BackendId.EXECUTORCH_XNNPACK_FP32]
        if (backend != null && bundle.pte.exists()) {
            val handle = runCatching { backend.load(bundle.descriptor, bundle.pte) }
                .onFailure { Timber.tag(TAG).w(it, "load failed for %s; falling back to the stub", id) }
                .getOrNull()
            if (handle != null) {
                return Entry(bundle, backend, handle, BackendId.EXECUTORCH_XNNPACK_FP32, real = true)
            }
        }
        val handle = stub.load(bundle.descriptor, bundle.pte)
        return Entry(bundle, stub, handle, BackendId.STUB, real = false)
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

    /** Debug-only: an ELIGIBLE fan ramping startBg to endBg, ±15 mg/dL band passes degeneracy. */
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

    /** real is false when StubBackend stood in for a bad .pte; calc then fails closed. */
    data class SelectedModelInfo(
        val id: String,
        val descriptor: ModelDescriptor,
        val backend: BackendId,
        val real: Boolean,
    )

    /** Selected model, whatever serves it; :calc fails closed on the stub (§3.6-E). */
    fun selectedModelInfo(): SelectedModelInfo? {
        val id = selectedId ?: return null
        val e = loaded[id] ?: return null
        return SelectedModelInfo(id, e.bundle.descriptor, e.effectiveBackend, e.real)
    }

    /** Dosing path's provenance (§3.6-E): null unless a real .pte is loaded on the authority. */
    fun authorityModelInfo(): SelectedModelInfo? =
        selectedModelInfo()?.takeIf { it.real && it.backend == BackendId.EXECUTORCH_XNNPACK_FP32 }

    /** Confined to inference dispatcher, serialised on cycleMutex; throws when nothing selected. */
    suspend fun runSelected(input: GraphTensors): GraphOutput = cycleMutex.withLock {
        val id = selectedId ?: error("no selected model")
        val e = loaded[id] ?: error("selected model not loaded")
        withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
    }

    /** Dose path's forward (§3.6-E), same confinement as runSelected; throws unless loaded. */
    suspend fun runSelectedAuthority(input: GraphTensors): GraphOutput = cycleMutex.withLock {
        val id = selectedId ?: error("no selected model")
        val e = loaded[id]?.takeIf { it.real && it.effectiveBackend == BackendId.EXECUTORCH_XNNPACK_FP32 }
            ?: error("fp32 XNNPACK authority not loaded for $id")
        withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
    }

    /** forecast holds EVERY decoded slot incl. infill spans, slotPatch names position; unstored. */
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

    /** Call whenever an artifact changes under a fixed id; else it inherits the prior verdict. */
    fun evictHead(modelId: String) = heads.evict(modelId)

    fun headState(modelId: String): HeadCache.State? =
        loaded[modelId]?.bundle?.let { heads.stateOf(it) }

    /** spans are context-relative patch indices; withForecast appends the future zone. */
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

    /** Windows returned OLDEST-FIRST for a chronological split; gapped horizon window DROPPED. */
    suspend fun loraSamples(
        modelId: String,
        maxWindows: Int,
        strideSteps: Int,
        onWindow: ((done: Int, total: Int) -> Unit)? = null,
    ): List<LoraSample> {
        // Captured under the lock, re-checked before every forward: a refresh can close it mid-run.
        val entry = cycleMutex.withLock { loaded[modelId] } ?: error("model $modelId is not loaded")
        val desc = entry.bundle.descriptor
        val ctxSteps = desc.minContextPatches * desc.patchSize
        val predSteps = predSteps(desc)
        val stride = strideSteps.coerceAtLeast(desc.patchSize)
        val want = ctxSteps + predSteps + stride * maxWindows.coerceAtLeast(1)
        val dense = history.recentBgSeries(want, ctxSteps + predSteps) ?: return emptyList()
        // Fit series has its own filter/length/origin; project by TIMESTAMP, not index.
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
        // Which slots of dense are the model's OWN OUTPUT (SPEC §1); target can't distinguish them.
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
        // As long as the forecast horizon fits in context, geometries pose a comparable question.
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
            // Doses that ACTUALLY happened are the announced plan; no-event baseline teaches none.
            val ahead = ModelChannels(
                ch.carb.copyOfRange(o, o + predSteps),
                ch.insulin.copyOfRange(o, o + predSteps),
                ch.exercise.copyOfRange(o, o + predSteps),
            )
            // A backcast/infill masks a run INSIDE context, no future zone; target is its own.
            val geometry = LoraGeometryPlan.geometryAt(index)
            val startPatch = LoraGeometryPlan.startPatch(geometry, ctxPatches, spanPatches)
            val isForecastWindow = geometry == MaskGeometry.FORECAST || startPatch == null
            // ANCHOR must be a real measurement; pinball target, baseline d0, guard measure it.
            val anchorStep = anchorStepOf(ctxFrom, o, startPatch, spanPatches, desc.patchSize)
            if (anchorStep !in target.indices || target[anchorStep].isNaN()) continue
            // No reconstruction anywhere in context, the rule itself; carried-forward is fine.
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
            // ONE forward per lock; holding it across hundreds of windows starves the forecast.
            val run = cycleMutex.withLock {
                if (loaded[modelId] !== entry) return out
                withContext(dispatchers.inference) { entry.backend.run(entry.handle, GraphIo.tensors(gi)) }
            }
            val steps = stepStates(entry.bundle, gi, run, needed = true)
            heads.verify(entry.bundle, steps, run.headRaw, gi.mSlots)
            val hidden = steps ?: return out
            val d = desc.dModel * desc.patchSize

            // SAME window with one insulin unit added; FORECAST only, guard needs terminal step.
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
        // Outside the cycle mutex: minutes of CPU touching no handle, own adapter through the loop.
        return withContext(dispatchers.default) {
            native.loraTrain(state.head, entry.bundle.descriptor, samples, config, opts, progress)
        }
    }

    /** What a STORED adapter does to insulin's marginal response; bar from loraGuardOptsFit. */
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

    /** No-op when id isn't running; selection governs DISPLAYED forecast and dosing authority. */
    suspend fun selectModel(id: String) {
        // Write/re-flag race refreshModelsLocked; release BEFORE refreshModels (non-reentrant).
        cycleMutex.withLock {
            if (id !in loaded.keys) return
            selectedId = id
            val hasTime = loaded[id]?.bundle?.descriptor?.time != null
            val sel = _state.value.predictions.firstOrNull { it.modelId == id }
            _state.value = _state.value.copy(
                running = runningModels(),
                predictions = _state.value.predictions
                    .map { it.copy(selected = it.modelId == id) }
                    .sortedByDescending { it.selected },
                // A model with no time head clears the belief, not leave the previous one frozen.
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

    /** Deletes pair from disk, purges telemetry/latency/prediction; returns whether removed. */
    suspend fun deleteModel(id: String): Boolean = cycleMutex.withLock {
        heads.evict(id)
        val removed = withContext(dispatchers.inference) { runCatching { store.delete(id) }.getOrDefault(false) }
        cumulative.remove(id); latencySamples.remove(id)
        runCatching { telemetryStore?.save(HashMap(cumulative)) }
        refreshModelsLocked() // already under cycleMutex; public refreshModels would deadlock
        _state.value = _state.value.copy(predictions = _state.value.predictions.filterNot { it.modelId == id })
        removed
    }

    /** Banner note while BLOCKED, else null; same cycleNowMs reuses the verdict, one temp read. */
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
        // Size context/warmup on the SELECTED descriptor, not loaded.values.first(); snapshot.
        val (descAny, selReal, selHasTime) = cycleMutex.withLock {
            val selEntry = loaded[selectedId]
            val desc = (selEntry ?: loaded.values.firstOrNull())?.bundle?.descriptor
            Triple(desc, selEntry?.real ?: false, selEntry?.bundle?.descriptor?.time != null)
        }
        if (descAny == null) { refreshModels(); return }
        // Before warmup gate: banner is over-temp not warmup; copy keeps circadianTime lit.
        overTempNote(nowMs)?.let { note ->
            _state.value = _state.value.copy(
                predictions = emptyList(), lastCause = InferenceCause.OVER_TEMPERATURE, note = note,
            )
            Timber.tag(TAG).i(note)
            return
        }
        val minSteps = descAny.minContextPatches * descAny.patchSize
        val maxSteps = descAny.maxContextPatches * descAny.patchSize

        // WARMUP gate: withhold until warmupHours MEASURED, floored at MIN_CONTEXT; distinct gate.
        val minContextHours = minSteps * GRID_MS / MS_PER_HOUR
        val requiredHours = warmupHoursProvider().coerceAtLeast(minContextHours)
        val requiredSteps = Math.round(requiredHours * MS_PER_HOUR / GRID_MS).toInt()
        val measuredSteps = runCatching { history.measuredStepsInWindow(requiredSteps) }.getOrDefault(0)
        // Passive-advert CGM never fills every slot; completion needs only a fraction of coverage.
        val completionSteps = kotlin.math.ceil(requiredSteps * WARMUP_COMPLETION_FRACTION).toInt()
        if (measuredSteps >= completionSteps) warmupSatisfiedUpTo = maxOf(warmupSatisfiedUpTo, requiredSteps)
        val warmedUp = measuredSteps >= completionSteps || requiredSteps <= warmupSatisfiedUpTo
        if (!warmedUp) {
            val measuredHours = measuredSteps * GRID_MS / MS_PER_HOUR
            // Circadian belief degrades gracefully, published low-context if SELECTED has time.
            val warmupBelief = if (selHasTime) {
                runCatching { circadianDuringWarmup() }.getOrNull()
            } else {
                null
            }
            _state.value = _state.value.copy(
                predictions = emptyList(),
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
                "warmup: %.1f/%.0f h measured — forecast suppressed; circadian=%s",
                measuredHours, requiredHours,
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
        if (loaded.isEmpty()) {
            refreshOrNote()
            if (loaded.isEmpty()) return@withLock
        }
        // UNIVERSAL chokepoint: tick, manual, synthetic funnel here; copy keeps circadianTime lit.
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

        // ONE shared context build (SPEC §3.3); per-descriptor norm happens in build_context.
        val doseChannels = buildDoseChannels(series)

        // ONE shared PREDICTION-ZONE build (SPEC §3.3), same curve engine; anchored on SELECTED.
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

        preds.sortByDescending { it.selected }

        val durationMs = ((System.nanoTime() - t0) / 1_000_000.0).toLong()
        val selPred = preds.firstOrNull { it.selected }
        // False for any neural export cut without a time head.
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
            realBackendAvailable = loaded[selectedId]?.real ?: false,
            // Keeps last belief across a TRANSIENT failure, not across a no-time-head switch.
            circadianTime = if (selHasTime) selPred?.predictedTime ?: _state.value.circadianTime else null,
            circadianAnchorMs = if (selHasTime) {
                selPred?.predictedTime?.let { selPred.anchorTsMs } ?: _state.value.circadianAnchorMs
            } else {
                null
            },
            circadianLowContext = selHasTime && selPred?.predictedTime == null && _state.value.circadianLowContext,
            selectedHasTimeSection = selHasTime,
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

    /** Null runs frozen (no adapter/store); attached adapter that fails to apply THROWS. */
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

    /** Head's per-step input, null if no hidden; skipped if not needed and parity settled. */
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

    /** Dose path scores on the same forecaster the panel draws; a bad adapter THROWS. */
    suspend fun adaptedHeadRawFor(modelId: String, out: GraphOutput, gi: GraphInput): List<Double>? {
        val store = loraStore ?: return null
        val w = store.attached(modelId) ?: return null
        val entry = cycleMutex.withLock { loaded[modelId] } ?: error("model $modelId is not loaded")
        val steps = stepStates(entry.bundle, gi, out, needed = true)
        // Dose path can be the FIRST caller after a start; proved against this forward.
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

        // Slice by patch not count, so an added infill span can't shift which rows are read.
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
            selected = selected,
            stale = stale,
            latencyMs = latMs,
            predictedTime = predictedTime,
        )
    }

    /** Fail-OPEN: null unless desc declares time AND tensor is flat (P,nBins); never throws. */
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

    /** Aligned to series.gridStartMs (SPEC §3.3); missing or mismatched falls to normalize(0). */
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

    /** Aligned one step past the last context sample; fixed pred zone (P*S), null=normalize(0). */
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

    /** Null future seeds pred-zone to normalize(0); channel order fixed carb-insulin-exercise. */
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

    /** Snapped to a detent; read fresh per cycle, so a Settings edit takes on the next tick. */
    private suspend fun smoothingWindow(): Int =
        InferenceControllerDefaults.nearestSmoothingStop(
            runCatching { smoothingWindowProvider() }.getOrNull() ?: InferenceControllerDefaults.SAVGOL_WINDOW,
        )

    private fun runningModels(): List<RunningModel> = loaded.map { (id, e) ->
        RunningModel(id, e.effectiveBackend, id == selectedId)
    }

    /** .pte filenames, NOT model_id (can diverge for adb-pushed); sync coordinator keys on this. */
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
        /** Fraction of the window MEASURED; a gapless demand flapped completion. */
        const val WARMUP_COMPLETION_FRACTION = 0.85
        const val N_QUANTILES = 7
        /** Empty: cycle forecast is one window, no seam; §9 carry belongs to RollingForecaster. */
        val CARRY_SPREAD = emptyList<Double>()
        const val LATENCY_WINDOW = 60

        fun snapToGrid(ts: Long): Long = Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        fun percentile(sortedAsc: List<Double>, q: Double): Double {
            if (sortedAsc.isEmpty()) return 0.0
            val idx = (q * (sortedAsc.size - 1)).toInt().coerceIn(0, max(0, sortedAsc.size - 1))
            return sortedAsc[idx]
        }
    }
}
