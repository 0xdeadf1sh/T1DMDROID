package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.BacktestRefusal
import com.t1dm.core.model.BacktestStop
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
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

/** [cycleMutex] serialises whole cycles: two forwards never overlap on the one command queue. */
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
    /** The future zone, as against [contextChannels]'s past (SPEC §3.3). Null ⇒ normalize(0). */
    private val futureOverrides: FutureOverrideSource? = null,
    /** Read FRESH each cycle; model's own MIN_CONTEXT is the floor. */
    private val warmupHoursProvider: suspend () -> Double = { InferenceControllerDefaults.WARMUP_HOURS },
    /** Null ⇒ session-only in-memory counters. */
    private val telemetryStore: TelemetryStore? = null,
    /** BATTERY °C, read FRESH; null never gates. DEATH doesn't defeat it: hardware, not glucose. */
    private val thermalProvider: suspend () -> ThermalStatus? = { null },
    /** INFERENCE.md §7.1, read FRESH; snapped to detent so a corrupt value never reaches Rust. */
    private val smoothingWindowProvider: suspend () -> Int = { InferenceControllerDefaults.SAVGOL_WINDOW },
    /** Null ⇒ the counterfactual branch never runs, so every adapter stays `ABSENT`. */
    private val probeInsulin: ProbeInsulinPort? = null,
    /** Re-read every cycle. Null ⇒ every model runs frozen. */
    private val loraStore: LoraStore? = null,
    /** Null ⇒ no adapter fit: no window can be shown free of events. */
    private val eventOnsets: EventOnsetSource? = null,
    /** Null ⇒ the choice lasts the session. */
    private val selectionStore: SelectionStore? = null,
    /** Null ⇒ files replaced under a kept id go unnoticed. */
    private val artifactLedger: ArtifactLedger? = null,
    /** Runs holding [cycleMutex], so must not call back in; a throw retries next refresh. */
    private val onArtifactReplaced: suspend (modelId: String) -> Unit = {},
) {
    private val _state = MutableStateFlow(InferenceState())
    val state: StateFlow<InferenceState> = _state.asStateFlow()

    /** Old predictions describe nothing vs. a new sensor's glucose; metadata/telemetry survive. */
    fun onCgmSourceChanged() {
        // update, not copy(): outside cycleMutex, a read-modify-write races a cycle's publish.
        _state.update {
            it.copy(predictions = emptyList(), otherPredictions = emptyMap(), lastCycleTsMs = null, lastCause = null)
        }
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
    /** Every DISCOVERED model, pre-load; ModelStore admits only XNNPACK, one bundle/id. */
    private val installed = LinkedHashMap<String, ModelBundle>()
    /** Written under [cycleMutex] on inference; read unlocked in [runFromHistory]'s preamble. */
    @Volatile
    private var selectedId: String? = null
    private val latencySamples = HashMap<String, ArrayDeque<Double>>()
    /** Durable via [telemetryStore]; loaded once, then in-memory. */
    private val cumulative = HashMap<String, CumulativeTelemetry>()
    private var storesLoaded = false
    /** Largest requiredSteps window EVER met; latches so one dropped slot can't flap warmup. */
    @Volatile
    private var warmupSatisfiedUpTo = 0
    private val cycleMutex = Mutex()
    /** Latches at thresholdC; clears below thresholdC-resumeMarginC, so hovering can't flap it. */
    @Volatile
    private var thermalBlocked = false

    /** One immutable holder, one volatile write: stamp/note read together. See [overTempNote]. */
    private class ThermalVerdict(val nowMs: Long, val note: String?)

    @Volatile
    private var thermalVerdict: ThermalVerdict? = null

    fun registerBackend(backend: InferenceBackend) { backends[backend.id] = backend }

    /** After an in-place wipe the app must re-earn warmup, or forecast runs on empty context. */
    suspend fun resetWarmupLatch() = cycleMutex.withLock { warmupSatisfiedUpTo = 0 }

    suspend fun restoreLast(nowMs: Long = System.currentTimeMillis()) {
        val last = runCatching { predictionStore.loadLast() }.getOrNull() ?: return
        if (last.isNotEmpty()) {
            // Cold start shows no lit forecast on a dark clock; null belief leaves it as-is.
            val sel = last.firstOrNull { it.selected }
            _state.value = _state.value.copy(
                predictions = last
                    .map { it.copy(stale = it.stale || nowMs - it.anchorTsMs > freshnessThresholdMs) }
                    .sortedByDescending { it.selected },
                circadianTime = sel?.predictedTime ?: _state.value.circadianTime,
                circadianAnchorMs = sel?.predictedTime?.let { sel.anchorTsMs } ?: _state.value.circadianAnchorMs,
                note = "restored ${last.size} prediction(s) from last run",
            )
        }
    }

    /** Loads models to [maxRunningProvider]'s cap, falls back to [StubBackend] on load failure. */
    suspend fun refreshModels() = cycleMutex.withLock { refreshModelsLocked() }

    /** Call only holding [cycleMutex] (not reentrant); unlocked callers use [refreshModels]. */
    private suspend fun refreshModelsLocked() = withContext(dispatchers.inference) {
        if (!storesLoaded) {
            runCatching { telemetryStore?.load() }.getOrNull()?.let { cumulative.putAll(it) }
            selectedId = selectedId ?: runCatching { selectionStore?.load() }.getOrNull()
            storesLoaded = true
        }
        val discovered = store.discover()
        val replaced = noteReplacedArtifacts(discovered)

        // Refresh is rare, so a full close/reload beats diffing and cannot leave a stale handle.
        loaded.values.forEach { runCatching { it.backend.close(it.handle) } }
        loaded.clear()
        // Parity is proved per graph; a reloaded one owes it again.
        heads.closeAll()
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
            discovered.isEmpty() && store.refusedDescriptors.isNotEmpty() -> {
                val first = store.refusedDescriptors.first()
                val rest = store.refusedDescriptors.size - 1
                "refused ${first.file}: ${first.reason}" + if (rest > 0) " (+$rest more)" else ""
            }
            discovered.isEmpty() ->
                "no model — adb push a .pte and its descriptor.json"
            else -> listOfNotNull(noPteNote(), truncated).joinToString(" · ").ifEmpty { null }
        }
        // runFromHistory returns at descAny == null before its own clear; nothing else drops these.
        val noModel = installed.isEmpty()
        _state.value = _state.value.copy(
            running = runningModels(),
            metas = metasSnapshot(),
            telemetry = telemetrySnapshot(),
            note = note,
            // A restored fan carries the flag of the run that stored it.
            predictions = if (noModel) {
                emptyList()
            } else {
                _state.value.predictions
                    .filterNot { it.modelId in replaced }
                    .map { it.copy(selected = it.modelId == selectedId) }
                    .sortedByDescending { it.selected }
            },
            circadianTime = if (noModel) null else _state.value.circadianTime,
            circadianAnchorMs = if (noModel) null else _state.value.circadianAnchorMs,
            circadianLowContext = if (noModel) false else _state.value.circadianLowContext,
        )
        Timber.tag(TAG).i(
            "refreshModels models=%s active=%s",
            installed.keys, loaded[selectedId]?.effectiveBackend,
        )
    }

    /** Ids whose files changed since recorded; a first sight or an absent .pte only records. */
    private suspend fun noteReplacedArtifacts(discovered: List<ModelBundle>): Set<String> {
        val ledger = artifactLedger ?: return emptySet()
        val known = runCatching { ledger.load() }.getOrElse { return emptySet() }
        val next = HashMap(known)
        val replaced = HashSet<String>()
        for (b in discovered) {
            // Recording an absent .pte would make the same file pushed back read as new.
            if (!b.pte.exists()) continue
            val fp = fingerprint(b)
            if (known[b.id]?.let { it != fp } == true) replaced += b.id
            next[b.id] = fp
        }
        for (id in replaced) {
            runCatching { onArtifactReplaced(id) }.onFailure {
                Timber.tag(TAG).w(it, "dropping state of replaced %s failed", id)
                next[id] = known.getValue(id)
            }
        }
        if (next != known) {
            runCatching { ledger.save(next) }.onFailure { Timber.tag(TAG).w(it, "artifact ledger persist failed") }
        }
        return replaced
    }

    /** Descriptor text by SHA-256; .pte and head by size and mtime, which a push rewrites. */
    private fun fingerprint(b: ModelBundle): String {
        fun stat(f: File?) = f?.takeIf { it.exists() }?.let { "${it.length()}@${it.lastModified()}" } ?: "-"
        val sha = MessageDigest.getInstance("SHA-256").digest(b.descriptorJson.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$sha|${stat(b.pte)}|${stat(b.head)}"
    }

    /** fp32 XNNPACK authority, else [StubBackend]: never real, so the model forecasts nothing. */
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

    /** Null unless the selected model has no working .pte. */
    private fun noPteNote(): String? {
        val pte = loaded[selectedId]?.takeIf { !it.real }?.bundle?.pte ?: return null
        return "no forecast — ${pte.name} ${if (pte.exists()) "won't load" else "missing"}"
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

    /** Debug-only: ELIGIBLE fan [startBg]->[endBg], +-15 mg/dL band so degeneracy guard passes. */
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

    /** [real] false when [StubBackend] stood in for a failed .pte: treated as no model. */
    data class SelectedModelInfo(
        val id: String,
        val descriptor: ModelDescriptor,
        val backend: BackendId,
        val real: Boolean,
    )

    /** Selected model. :calc reads [real]: dose runs on fp32 XNNPACK authority or not at all. */
    fun selectedModelInfo(): SelectedModelInfo? {
        val id = selectedId ?: return null
        val e = loaded[id] ?: return null
        return SelectedModelInfo(id, e.bundle.descriptor, e.effectiveBackend, e.real)
    }

    /** DOSING provenance (§3.6-E): null unless real .pte is on authority; fails closed on stub. */
    fun authorityModelInfo(): SelectedModelInfo? =
        selectedModelInfo()?.takeIf { it.real && it.backend == BackendId.EXECUTORCH_XNNPACK_FP32 }

    /** Confined to `inference`, serialised on [cycleMutex]: never two forwards on one queue. */
    suspend fun runSelected(input: GraphTensors): GraphOutput = cycleMutex.withLock {
        val id = selectedId ?: error("no selected model")
        val e = loaded[id] ?: error("selected model not loaded")
        withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
    }

    /** DOSE forward (§3.6-E) on [expected], confined like [runSelected]. */
    suspend fun runSelectedAuthority(expected: SelectedModelInfo, input: GraphTensors): GraphOutput =
        cycleMutex.withLock {
            val e = authorityEntry(expected)
            withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
        }

    /** Caller holds [cycleMutex]. Throws once a select or reload has replaced [expected]. */
    private fun authorityEntry(expected: SelectedModelInfo): Entry {
        check(selectedId == expected.id) { "selection moved off ${expected.id}" }
        val e = loaded[expected.id]?.takeIf { it.real && it.effectiveBackend == BackendId.EXECUTORCH_XNNPACK_FP32 }
            ?: error("fp32 XNNPACK authority not loaded for ${expected.id}")
        check(e.bundle.descriptor === expected.descriptor) { "model ${expected.id} reloaded" }
        return e
    }

    /** [forecast] holds EVERY decoded slot incl. infill, not just horizon; none reaches alarms. */
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

    fun headState(modelId: String): HeadCache.State? =
        loaded[modelId]?.bundle?.let { heads.stateOf(it) }

    /** [spans] are context-relative patch indices; a disagreeing head refuses the unadapted fan. */
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
        if (!entry.real) error("model $modelId has no working .pte")
        val desc = entry.bundle.descriptor
        val gi = buildGraphInput(desc, series.mgdl, channels, future, spans, smoothingWindow(), withForecast)
        val t0 = System.nanoTime()
        val out = withContext(dispatchers.inference) { entry.backend.run(entry.handle, GraphIo.tensors(gi)) }
        val latMs = (System.nanoTime() - t0) / 1_000_000.0
        val headRaw = verifiedHeadRaw(entry, gi, out, lora)
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

    /** Caller holds [cycleMutex]: the head is shared, and [lora] is set on it for one forward. */
    private suspend fun verifiedHeadRaw(entry: Entry, gi: GraphInput, out: GraphOutput, lora: LoraWeights?): List<Double> {
        val steps = stepStates(entry.bundle, gi, out, needed = lora != null)
        heads.verify(entry.bundle, steps, out.headRaw, gi.mSlots)
        if (lora == null) return out.headRaw.map { it.toDouble() }
        val state = heads.stateOf(entry.bundle)
        if (state !is HeadCache.State.Ready) {
            error("model ${entry.bundle.id} takes no adapter: ${(state as? HeadCache.State.Unusable)?.why ?: "no head file"}")
        }
        val input = steps ?: error("the graph emitted no hidden state to adapt")
        state.head.setLora(lora)
        return try {
            withContext(dispatchers.default) { state.head.forward(input, gi.mSlots) }
        } finally {
            state.head.setLora(null)
        }
    }

    /** One origin, built as the live cycle would have built it then. */
    class BacktestInput(
        val cycleTsMs: Long,
        val series: BgSeries,
        val context: ContextChannelSource,
        val future: FutureOverrideSource,
    )

    /** [forecasts] oldest first; [refusal] non-null means nothing ran. */
    class BacktestRun(
        val forecasts: List<ModelPrediction>,
        val adapterAttached: Boolean,
        val stopped: BacktestStop?,
        val refusal: BacktestRefusal? = null,
    )

    /**
     * Replays [origins] through [modelId] as it runs now — offset, adapter, smoothing — and stores
     * nothing. Null from [inputAt] skips an origin. One forward per lock, so the live cycle interleaves.
     */
    suspend fun <O> backtest(
        modelId: String,
        origins: List<O>,
        inputAt: suspend (origin: O, desc: ModelDescriptor) -> BacktestInput?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): BacktestRun {
        val entry = cycleMutex.withLock { loaded[modelId] }
            ?: return BacktestRun(emptyList(), false, null, BacktestRefusal.NOT_LOADED)
        if (!entry.real) return BacktestRun(emptyList(), false, null, BacktestRefusal.NO_ARTIFACT)
        val desc = entry.bundle.descriptor
        // Read once: a per-origin read would deserialize the adapter thousands of times.
        val lora = loraStore?.attached(modelId)
        val window = smoothingWindow()
        val out = ArrayList<ModelPrediction>(origins.size)
        for ((i, origin) in origins.withIndex()) {
            onProgress(i, origins.size)
            if (i % BACKTEST_THERMAL_EVERY == 0 && overTempNote(System.currentTimeMillis()) != null) {
                return BacktestRun(out, lora != null, BacktestStop.TOO_HOT)
            }
            val input = inputAt(origin, desc) ?: continue
            val gi = buildGraphInput(
                desc,
                input.series.mgdl,
                buildDoseChannels(input.series, input.context),
                buildFutureChannels(input.series, desc, input.future),
                emptyList(),
                window,
            )
            val headRaw = cycleMutex.withLock {
                if (loaded[modelId] !== entry) return BacktestRun(out, lora != null, BacktestStop.MODEL_CHANGED)
                val graph = withContext(dispatchers.inference) { entry.backend.run(entry.handle, GraphIo.tensors(gi)) }
                verifiedHeadRaw(entry, gi, graph, lora)
            }
            val decoded = decode(desc, headRaw, gi)
            out += ModelPrediction(
                modelId = modelId,
                cycleTsMs = input.cycleTsMs,
                anchorTsMs = input.series.anchorTsMs,
                sourceId = input.series.sourceId,
                stepMs = GRID_MS,
                medianBg = decoded.forecast.medianBg,
                bandsMgdl = decoded.forecast.bandsMgdl,
                nQuantiles = N_QUANTILES,
                lastBg = decoded.anchorBg,
                status = decoded.status,
                backend = entry.effectiveBackend,
                selected = false,
                stale = false,
                latencyMs = null,
            )
        }
        onProgress(origins.size, origins.size)
        return BacktestRun(out, lora != null, null)
    }

    /** One sensor on its dense grid; prefix counts make each window check O(1). */
    private class FitGrid(val src: FitSource, val target: DoubleArray, onsetMs: LongArray) {
        val dense get() = src.dense
        val n = src.dense.mgdl.size
        private val measuredPre = prefix(n) { !target[it].isNaN() }
        private val reconstructedPre = prefix(n) { dense.gridStartMs + it * GRID_MS in src.reconstructed }
        private val onsetPre: IntArray

        init {
            val at = BooleanArray(n)
            for (t in onsetMs) stepOf(t).let { if (it in 0 until n) at[it] = true }
            onsetPre = prefix(n) { at[it] }
        }

        fun stepOf(ms: Long): Int = Math.floorDiv(ms - dense.gridStartMs, GRID_MS).toInt()
        fun msOf(step: Int): Long = dense.gridStartMs + step * GRID_MS
        fun measured(from: Int, to: Int) = count(measuredPre, from, to)
        fun reconstructed(from: Int, to: Int) = count(reconstructedPre, from, to)
        fun onsets(from: Int, to: Int) = count(onsetPre, from, to)

        /** Measured slots in `[fromMs, toMs)`, clipped to this grid. */
        fun measuredMs(fromMs: Long, toMs: Long) = measured(stepOf(fromMs), stepOf(toMs))

        private fun count(pre: IntArray, from: Int, to: Int): Int {
            val a = from.coerceIn(0, n)
            val b = to.coerceIn(a, n)
            return pre[b] - pre[a]
        }

        private companion object {
            inline fun prefix(n: Int, hit: (Int) -> Boolean): IntArray {
                val p = IntArray(n + 1)
                for (i in 0 until n) p[i + 1] = p[i] + if (hit(i)) 1 else 0
                return p
            }
        }
    }

    private class FitWindow(val grid: Int, val w: Int, val atEvent: Boolean, val spanStartMs: Long)

    /** Oldest-first for a chronological split; no event inside the span, a gapped span DROPPED. */
    suspend fun loraSamples(
        modelId: String,
        maxWindows: Int,
        kind: MaskGeometry,
        onWindow: ((done: Int, total: Int) -> Unit)? = null,
    ): LoraWindows {
        // Captured under the lock, re-checked pre-forward: a refresh can close it mid-replay.
        val entry = cycleMutex.withLock { loaded[modelId] } ?: error("model $modelId is not loaded")
        val desc = entry.bundle.descriptor
        val predSteps = predSteps(desc)
        val shape = loraSpanShape(kind, desc.minContextPatches, desc.patchSize, predSteps, desc.maskSpanMax)
            ?: return LoraWindows(emptyList(), 0)
        val ctxSteps = shape.ctxSteps
        // Without the log no span can be shown free of events, so no window is admissible.
        val onsetSource = eventOnsets ?: error("no meal and dose log; no window can be shown event-free")
        val sources = history.fitSources(LORA_SCAN_STEPS, shape.winLen)
        if (sources.isEmpty()) return LoraWindows(emptyList(), 0)
        val fromMs = sources.minOf { it.dense.gridStartMs }
        val toMs = sources.maxOf { it.dense.gridStartMs + it.dense.mgdl.size * GRID_MS }
        val onsetMs = onsetSource.onsets(fromMs, toMs)
        val clusters = eventClusters(onsetMs, EVENT_MERGE_MS)
        val grids = sources.map { s ->
            val n = s.dense.mgdl.size
            // Fit series has its own filter/length/origin; projected by TIMESTAMP, not index.
            val target = DoubleArray(n) { Double.NaN }
            val shift = ((s.measured.gridStartMs - s.dense.gridStartMs) / GRID_MS).toInt()
            for (i in s.measured.mgdl.indices) {
                val j = shift + i
                if (j in 0 until n) target[j] = s.measured.mgdl[i]
            }
            FitGrid(s, target, onsetMs)
        }

        val stride = desc.patchSize * LORA_GRID_STRIDE_PATCHES
        val found = ArrayList<FitWindow>()
        for ((k, g) in grids.withIndex()) {
            fun admissible(w: Int): Boolean {
                if (w < 0 || w + shape.winLen > g.n) return false
                val s = w + shape.spanOff
                val e = s + shape.spanLen
                if (g.onsets(s, e) > 0 || g.measured(s, e) != e - s) return false
                // ANCHOR must be a real measurement: pinball target and both guard reads key off it.
                val anchor = anchorStepOf(w, w + ctxSteps, shape.startPatch, shape.spanPatches, desc.patchSize)
                if (anchor !in 0 until g.n || g.target[anchor].isNaN()) return false
                // No reconstruction anywhere in context; a carried-forward slot stays admissible.
                if (g.reconstructed(w, w + ctxSteps) > 0) return false
                // Overlapping sensors: the one earlier in the list, authoritative first, keeps it.
                val a = g.msOf(w)
                val b = g.msOf(w + shape.winLen)
                return (0 until k).none { grids[it].measuredMs(a, b) > 0 }
            }
            val tried = HashSet<Int>()
            fun offer(w: Int) {
                if (!tried.add(w) || !admissible(w)) return
                val beside = boundaryStep(kind, shape, w)
                found.add(FitWindow(k, w, g.onsets(beside, beside + 1) > 0, g.msOf(w + shape.spanOff)))
            }
            for (c in clusters) offer(eventWindowStart(kind, shape, g.stepOf(c.first), g.stepOf(c.last)))
            var w = g.n - shape.winLen
            while (w >= 0) {
                offer(w)
                w -= stride
            }
        }
        found.sortByDescending { it.spanStartMs }
        val chosen = found.subList(0, minOf(maxWindows.coerceAtLeast(0), found.size)).asReversed()

        val out = ArrayList<LoraSample>(chosen.size)
        var nAtEvent = 0
        val channels = HashMap<Int, ModelChannels>()
        val total = chosen.size
        var seen = 0
        for (fw in chosen) {
            onWindow?.invoke(seen++, total)
            val g = grids[fw.grid]
            val ch = channels.getOrPut(fw.grid) { buildDoseChannels(g.dense) }
            val ctxFrom = fw.w
            val o = ctxFrom + ctxSteps
            val window = ModelChannels(
                ch.carb.copyOfRange(ctxFrom, o),
                ch.insulin.copyOfRange(ctxFrom, o),
            )
            val isForecastWindow = kind == MaskGeometry.FORECAST
            // Doses that ACTUALLY happened are the window's plan, not a no-event baseline.
            val ahead = if (isForecastWindow) {
                ModelChannels(ch.carb.copyOfRange(o, o + predSteps), ch.insulin.copyOfRange(o, o + predSteps))
            } else {
                null
            }
            val gi = buildGraphInput(
                desc,
                g.dense.mgdl.copyOfRange(ctxFrom, o),
                window,
                ahead,
                if (isForecastWindow) emptyList() else listOf(MaskSpan(shape.startPatch!!, shape.spanPatches)),
                smoothingWindow(),
                withForecast = isForecastWindow,
            )
            val spanFrom = ctxFrom + shape.spanOff
            val spanTarget = g.target.copyOfRange(spanFrom, spanFrom + shape.spanLen)
            // ONE forward per lock: holding it across many windows starves the live forecast.
            val steps = cycleMutex.withLock {
                if (loaded[modelId] !== entry) return LoraWindows(out, nAtEvent)
                val run = withContext(dispatchers.inference) { entry.backend.run(entry.handle, GraphIo.tensors(gi)) }
                stepStates(entry.bundle, gi, run, needed = true)
                    .also { heads.verify(entry.bundle, it, run.headRaw, gi.mSlots) }
            }
            val hidden = steps ?: return LoraWindows(out, nAtEvent)
            val d = desc.dModel * desc.patchSize

            // SAME window +1u insulin on horizon dose; FORECAST only (guard reads terminal step).
            val stimulus = if (ahead == null) {
                null
            } else {
                probeInsulin?.action(PROBE_DOSE_U, ahead.insulin.size)
            }
            val probed = if (stimulus == null || ahead == null) null else ModelChannels(
                ahead.carb,
                DoubleArray(ahead.insulin.size) { i -> ahead.insulin[i] + stimulus.getOrElse(i) { 0.0 } },
            )
            val hiddenPert = if (probed == null) {
                null
            } else {
                val giPert = buildGraphInput(
                    desc,
                    g.dense.mgdl.copyOfRange(ctxFrom, o),
                    window,
                    probed,
                    emptyList(),
                    smoothingWindow(),
                    withForecast = true,
                )
                val runPert = cycleMutex.withLock {
                    if (loaded[modelId] !== entry) return LoraWindows(out, nAtEvent)
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
                    probeDoseU = PROBE_DOSE_U,
                    isForecast = isForecastWindow,
                ),
            )
            if (fw.atEvent) nAtEvent++
        }
        onWindow?.invoke(total, total)
        return LoraWindows(out, nAtEvent)
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
        // Deliberately OUTSIDE cycleMutex: minutes of CPU, no backend handle touched.
        return withContext(dispatchers.default) {
            native.loraTrain(state.head, entry.bundle.descriptor, samples, config, opts, progress)
        }
    }

    /** What a STORED adapter does to insulin response; bar is [NativeCore.loraGuardOptsFit]. */
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

    /** No-op if [id] isn't running; selection governs only DISPLAYED forecast/belief/authority. */
    suspend fun selectModel(id: String) {
        // Write/re-flag race refreshModelsLocked; release BEFORE refreshModels() (non-reentrant).
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
                // No time head clears the belief rather than freezing the prior model's.
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
        runCatching { selectionStore?.save(id) }.onFailure { Timber.tag(TAG).w(it, "selection persist failed") }
        refreshModels()
    }

    /** Deletes from disk, purges telemetry/latency/predictions; whether an artifact was removed. */
    suspend fun deleteModel(id: String): Boolean = cycleMutex.withLock {
        heads.evict(id)
        val removed = withContext(dispatchers.inference) { runCatching { store.delete(id) }.getOrDefault(false) }
        cumulative.remove(id); latencySamples.remove(id)
        runCatching { telemetryStore?.save(HashMap(cumulative)) }
        refreshModelsLocked() // already under cycleMutex; refreshModels would self-deadlock
        _state.value = _state.value.copy(predictions = _state.value.predictions.filterNot { it.modelId == id })
        removed
    }

    /** Banner while BLOCKED, else null. Same [cycleNowMs] reuses verdict: one read, one latch. */
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
        // Sizes context/warmup on SELECTED descriptor, not values.first(). Snapshot then release.
        val (descAny, selReal, selHasTime) = cycleMutex.withLock {
            val selEntry = loaded[selectedId]
            val desc = (selEntry ?: loaded.values.firstOrNull())?.bundle?.descriptor
            Triple(desc, selEntry?.real ?: false, selEntry?.bundle?.descriptor?.time != null)
        }
        if (descAny == null) { refreshModels(); return }
        // Before warmup gate so the banner is over-temp, not warmup; copy preserves circadianTime.
        overTempNote(nowMs)?.let { note ->
            _state.value = _state.value.copy(
                predictions = emptyList(),
                otherPredictions = emptyMap(),
                lastCause = InferenceCause.OVER_TEMPERATURE,
                note = note,
            )
            Timber.tag(TAG).i(note)
            return
        }
        val minSteps = descAny.minContextPatches * descAny.patchSize
        val maxSteps = descAny.maxContextPatches * descAny.patchSize

        // WARMUP gate: withhold until warmupHours, floored at MIN_CONTEXT.
        val minContextHours = minSteps * GRID_MS / MS_PER_HOUR
        val requiredHours = warmupHoursProvider().coerceAtLeast(minContextHours)
        val requiredSteps = Math.round(requiredHours * MS_PER_HOUR / GRID_MS).toInt()
        // Passive CGM never fills every slot: needs WARMUP_COMPLETION_FRACTION, then latches.
        val completionSteps = kotlin.math.ceil(requiredSteps * WARMUP_COMPLETION_FRACTION).toInt()
        val gate = ContextGate(minSteps, maxSteps, requiredHours, requiredSteps, completionSteps)
        runAuthoritative(cause, nowMs, gate, selReal, selHasTime)
        runOtherSources(nowMs, gate)
    }

    private class ContextGate(
        val minSteps: Int,
        val maxSteps: Int,
        val requiredHours: Double,
        val requiredSteps: Int,
        val completionSteps: Int,
    )

    private suspend fun runAuthoritative(
        cause: InferenceCause,
        nowMs: Long,
        gate: ContextGate,
        selReal: Boolean,
        selHasTime: Boolean,
    ) {
        val minSteps = gate.minSteps
        val maxSteps = gate.maxSteps
        val requiredHours = gate.requiredHours
        val requiredSteps = gate.requiredSteps
        val completionSteps = gate.completionSteps
        val measuredSteps = runCatching { history.measuredStepsInWindow(requiredSteps) }.getOrDefault(0)
        if (measuredSteps >= completionSteps) warmupSatisfiedUpTo = maxOf(warmupSatisfiedUpTo, requiredSteps)
        val warmedUp = measuredSteps >= completionSteps || requiredSteps <= warmupSatisfiedUpTo
        if (!warmedUp) {
            val measuredHours = measuredSteps * GRID_MS / MS_PER_HOUR
            // Circadian belief degrades gracefully; published only if SELECTED model has time head.
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

    /** SPEC/invariants.md §7: display only; predictions, circadian belief, warm-up untouched. */
    private suspend fun runOtherSources(nowMs: Long, gate: ContextGate) {
        val ids = runCatching { history.otherActiveSourceIds() }.getOrDefault(emptyList())
        // No latch: a sensor that drops below the warm-up fraction stops being forecast.
        val ready = ids.mapNotNull { src ->
            val measured = runCatching { history.sourceMeasuredStepsInWindow(src, gate.requiredSteps) }
                .getOrDefault(0)
            if (measured < gate.completionSteps) return@mapNotNull null
            runCatching { history.sourceBgSeries(src, gate.maxSteps, gate.minSteps) }.getOrNull()?.let { src to it }
        }
        cycleMutex.withLock {
            if (ready.isEmpty() || loaded.isEmpty() || overTempNote(nowMs) != null) {
                _state.update { it.copy(otherPredictions = emptyMap()) }
                return@withLock
            }
            val cycleTs = snapToGrid(nowMs)
            val anchorDesc = (loaded[selectedId] ?: loaded.values.firstOrNull())?.bundle?.descriptor
            val bySource = LinkedHashMap<String, List<ModelPrediction>>(ready.size)
            for ((src, series) in ready) {
                val stale = (nowMs - series.anchorTsMs) > freshnessThresholdMs
                val doseChannels = buildDoseChannels(series)
                val futureChannels = anchorDesc?.let { buildFutureChannels(series, it) }
                val preds = ArrayList<ModelPrediction>(loaded.size)
                for ((id, entry) in loaded) {
                    if (!entry.real) continue
                    runCatching { runOne(entry, id == selectedId, series, doseChannels, futureChannels, cycleTs, stale) }
                        .onSuccess { preds.add(it) }
                        .onFailure { Timber.tag(TAG).w(it, "model %s on another sensor failed", id) }
                }
                if (preds.isNotEmpty()) bySource[src] = preds.sortedByDescending { it.selected }
            }
            _state.update { it.copy(otherPredictions = bySource) }
            runCatching { predictionStore.persist(cycleTs, bySource.values.flatten()) }
                .onFailure { Timber.tag(TAG).w(it, "other-sensor prediction persist failed") }
            Timber.tag(TAG).i("other sensors: %d forecast of %d active", bySource.size, ids.size)
        }
    }

    /** Public so the service can drive a synthetic or manual cycle with no sensor present. */
    suspend fun runCycle(cause: InferenceCause, series: BgSeries, nowMs: Long) = cycleMutex.withLock {
        if (loaded.isEmpty()) {
            refreshOrNote()
            if (loaded.isEmpty()) return@withLock
        }
        // The UNIVERSAL chokepoint: grid tick, manual, synthetic all funnel through here.
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

        // ONE shared context build (SPEC §3.3), aligned to BG grid; model-independent.
        val doseChannels = buildDoseChannels(series)

        // ONE shared PRED-ZONE build (SPEC §3.3), calc's curve engine: a meal RAISES the forecast.
        val anchorDesc = (loaded[selectedId] ?: loaded.values.firstOrNull())?.bundle?.descriptor
        val futureChannels = anchorDesc?.let { buildFutureChannels(series, it) }

        // Serial: never two forwards on the one command queue.
        for ((id, entry) in loaded) {
            if (!entry.real) continue
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
            // Keeps last belief across a TRANSIENT failure, not a switch to no-time-head model.
            circadianTime = if (selHasTime) selPred?.predictedTime ?: _state.value.circadianTime else null,
            circadianAnchorMs = if (selHasTime) {
                selPred?.predictedTime?.let { selPred.anchorTsMs } ?: _state.value.circadianAnchorMs
            } else {
                null
            },
            circadianLowContext = selHasTime && selPred?.predictedTime == null && _state.value.circadianLowContext,
            selectedHasTimeSection = selHasTime,
            warmup = null, // a published cycle clears the warmup banner
            note = if (stale) {
                "forecast STALE — last real BG is ${(nowMs - series.anchorTsMs) / 60_000} min old"
            } else {
                noPteNote()
            },
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

    /** Null runs frozen; NOT a fallback: an adapter that can't apply THROWS, drops prediction. */
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

    /** Head's per-step input, null with no hidden. Skipped when [needed] false, parity settled. */
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

    /** Dose path scores the SAME forecaster the panel draws; a THROWN adapter fails it closed. */
    suspend fun adaptedHeadRawFor(expected: SelectedModelInfo, out: GraphOutput, gi: GraphInput): List<Double>? {
        val store = loraStore ?: return null
        val w = store.attached(expected.id) ?: return null
        // Dose path can be FIRST caller after start: head proved against this forward, not assumed.
        return cycleMutex.withLock { verifiedHeadRaw(authorityEntry(expected), gi, out, w) }
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
        val (forecast, anchorBg, status) = decode(desc, adapted ?: out.headRaw.map { it.toDouble() }, gi)

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

    private data class Decoded(val forecast: Forecast, val anchorBg: Double, val status: ForecastStatus)

    private suspend fun decode(desc: ModelDescriptor, headRaw: List<Double>, gi: GraphInput): Decoded {
        // Slice by patch, not count: an added infill span can't shift which rows panel/calc read.
        val forecast: Forecast = withContext(dispatchers.default) {
            val all = native.assembleDecode(desc, headRaw, gi.anchors, gi.slotPatch, gi.nMasked, CARRY_SPREAD)
            native.forecastSlice(all, gi.firstForecastPatch, gi.t)
        }
        val anchorBg = gi.anchors.getOrElse(gi.slotPatch.indexOf(gi.firstForecastPatch)) { Double.NaN }
        val status = withContext(dispatchers.default) { native.forecastDegeneracyCheck(desc, forecast) }
        return Decoded(forecast, anchorBg, status)
    }

    /** Fail-OPEN: null unless desc has a time section AND a matching flat tensor. Never throws. */
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

    /** Aligned to series.gridStartMs (SPEC §3.3); a bad source or length falls to normalize(0). */
    private suspend fun buildDoseChannels(
        series: BgSeries,
        source: ContextChannelSource? = contextChannels,
    ): ModelChannels {
        val n = series.mgdl.size
        val src = source ?: return ModelChannels.zero(n)
        return runCatching {
            val ch = src.channels(series.gridStartMs, n)
            if (ch.carb.size == n && ch.insulin.size == n) ch
            else ModelChannels.zero(n)
        }.getOrElse {
            Timber.tag(TAG).w(it, "context channel build failed; falling back to no-event baseline")
            ModelChannels.zero(n)
        }
    }

    /** Grid boundary past the last context sample; fixed pred zone length; null ⇒ normalize(0). */
    private suspend fun buildFutureChannels(
        series: BgSeries,
        desc: ModelDescriptor,
        source: FutureOverrideSource? = futureOverrides,
    ): ModelChannels? {
        val src = source ?: return null
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

    /** Null [future] seeds pred-zone to normalize(0); channel order fixed, as RollingForecaster. */
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
                ann { it.carb },
                ann { it.insulin },
                maskSpans,
                withForecast = withForecast,
                smoothingWindow = smoothingWindow,
            )
        }

    /** Snapped to a detent; read fresh per cycle, so a Settings edit takes on next tick. */
    private suspend fun smoothingWindow(): Int =
        InferenceControllerDefaults.nearestSmoothingStop(
            runCatching { smoothingWindowProvider() }.getOrNull() ?: InferenceControllerDefaults.SAVGOL_WINDOW,
        )

    private fun runningModels(): List<RunningModel> = loaded.map { (id, e) ->
        RunningModel(id, e.effectiveBackend, id == selectedId)
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
        /** Fraction of window covered by MEASURED slots: a gapless demand made completion flap. */
        const val WARMUP_COMPLETION_FRACTION = 0.85
        const val N_QUANTILES = 7
        /** Empty: cycle forecast is one ≤2h window, no seam; §9 carry is calc's own overlay. */
        val CARRY_SPREAD = emptyList<Double>()
        const val LATENCY_WINDOW = 60
        /** An hour of origins between thermal reads. */
        const val BACKTEST_THERMAL_EVERY = 12
        /** A year of 5-min steps per sensor: the adapter fit's lookback. */
        const val LORA_SCAN_STEPS = 365 * 288
        /** Onsets this close chain into one event: a meal and its bolus. */
        const val EVENT_MERGE_MS = 15 * 60_000L
        /** Event-free windows are also taken on a grid, one per hour. */
        const val LORA_GRID_STRIDE_PATCHES = 2

        fun snapToGrid(ts: Long): Long = Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        fun percentile(sortedAsc: List<Double>, q: Double): Double {
            if (sortedAsc.isEmpty()) return 0.0
            val idx = (q * (sortedAsc.size - 1)).toInt().coerceIn(0, max(0, sortedAsc.size - 1))
            return sortedAsc[idx]
        }
    }
}
