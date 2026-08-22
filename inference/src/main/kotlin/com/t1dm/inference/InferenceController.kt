package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BASELINE_MODEL_ID
import com.t1dm.core.model.BackendAvailability
import com.t1dm.core.model.BackendComparison
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

/**
 * The Phase-2 inference orchestrator (§3.2, Phase 2 deliverable 4). It owns the
 * running set (every discovered model up to a user-configurable cap, default 5), the loaded backend
 * handles, and the observable [state]. A cycle —
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
    /** The user's running-set cap (§2.3), read FRESH each discovery (kv-backed;
     *  mirrors [warmupHoursProvider]). Every discovered model up to this cap runs each cycle;
     *  coerced to ≥1. null/throw ⇒ [DEFAULT_MAX_RUNNING]. */
    private val maxRunningProvider: suspend () -> Int = { DEFAULT_MAX_RUNNING },
    /** Reconstructed carb/insulin context channels (SPEC §3.3); null ⇒ `normalize(0)` baseline. */
    private val contextChannels: ContextChannelSource? = null,
    /** Committed dose tails carried into the PREDICTION ZONE (SPEC §3.3); null ⇒ `normalize(0)`
     *  baseline. Distinct from [contextChannels] (the past): this is the already-logged action that
     *  keeps absorbing past the now-boundary, so the forecast responds to a just-logged dose the way
     *  the calculator's baseline roll does. See [FutureOverrideSource]. */
    private val futureOverrides: FutureOverrideSource? = null,
    /** The user's `warmupHours` setting, read FRESH each cycle (kv-backed). The model's own
     *  MIN_CONTEXT is the binding floor and comes from its descriptor. inference-runtime.md. */
    private val warmupHoursProvider: suspend () -> Double = { DEFAULT_WARMUP_HOURS },
    /** The user's PER-MODEL forecast-backend preference (kv-backed in :app), re-read FRESH for every
     *  discovered model id at each discovery. null ⇒ auto (the fp32 XNNPACK authority). Steers the
     *  DISPLAY forecast cycle ONLY — the dosing/authority path ignores it entirely (§3.6-E). */
    private val backendPrefProvider: suspend (modelId: String) -> BackendId? = { null },
    /** Durable cumulative per-model inference telemetry (Phase 7C — Models drill-down). Null ⇒
     *  session-only in-memory counters. */
    private val telemetryStore: TelemetryStore? = null,
    /** D1/D4 thermal gate: the current [ThermalStatus] (BATTERY-sensor °C, thresholds from Settings),
     *  or null when the gate is disabled / the temperature is unreadable ⇒ never gates. Read FRESH each
     *  cycle. This gate has NO death-mode check — it stays active in DEATH (the one §3.6 rail DEATH does
     *  not defeat), since running the APU into a thermal fault is a hardware risk, not a glucose alarm. */
    private val thermalProvider: suspend () -> ThermalStatus? = { null },
    /** The user's causal-SavGol window for the BG channel (INFERENCE.md §7.1), read FRESH each cycle
     *  (kv-backed; mirrors [warmupHoursProvider]). Snapped to an offered detent and defaulted on a
     *  throw, so a corrupt setting can never reach the Rust guard. It MOVES the §3.6-D `last_bg`
     *  anchor, hence its exclusion from the agreement probe below. */
    private val smoothingWindowProvider: suspend () -> Int = { InferenceControllerDefaults.SAVGOL_WINDOW },
    /** The stimulus the adapter guard's counterfactual branch injects. Null ⇒ no branch is run at
     *  all, so no window is paired, no verdict is reachable and every adapter stays `ABSENT`. */
    private val probeInsulin: ProbeInsulinPort? = null,
    /** The classical baseline the neural models are compared against. It runs beside the loaded set
     *  every cycle and publishes an ordinary [ModelPrediction], so every consumer that reads a FAN
     *  treats it as another model. It is not part of [loaded] because it has no descriptor and no
     *  artifact — it is fitted on device rather than exported — and `SPEC/invariants.md` §4 rule 5
     *  makes a descriptor and an artifact one unit, so there is no honest bundle to give it. The
     *  consequence is that [authorityModelInfo] cannot resolve it and the dose path fails closed
     *  while it is selected; see [com.t1dm.core.model.BaselineModel]. Null ⇒ absent. */
    private val baseline: BaselineRunner? = null,
    /** The attached adapter per model, re-read every cycle. Null ⇒ every model runs frozen. */
    private val loraStore: LoraStore? = null,
) {
    private val _state = MutableStateFlow(InferenceState())
    val state: StateFlow<InferenceState> = _state.asStateFlow()

    /**
     * Drop the standing forecast because the authoritative CGM sensor changed.
     *
     * Every published prediction was conditioned on the OUTGOING sensor's history, so beside the new
     * sensor's glucose it describes nothing — and the widget, the watch glance and the ongoing
     * notification all pair the two. Clearing them puts the panel back into its "collecting context"
     * state, which is the truth: the model has no history for this sensor yet.
     *
     * The running model set, the metadata and the telemetry survive — none of them is about a sensor.
     * The next cycle republishes as soon as the new sensor has enough context (`recentBgSeries`
     * returns null below `minSteps`, so nothing is emitted from a short series).
     */
    fun onCgmSourceChanged() {
        // `update`, not `value = value.copy(...)`: this is called from the service's own coroutine,
        // outside the cycle mutex, so a read-modify-write here races a cycle publishing its results
        // and could restore the predictions it is trying to drop.
        _state.update { it.copy(predictions = emptyList(), lastCycleTsMs = null, lastCause = null) }
    }

    private val stub = StubBackend()
    private val backends = HashMap<BackendId, InferenceBackend>()

    /** The re-runnable heads, opened lazily and verified against the graph on first use. Only the
     *  adapter path reads them; a cycle never does. */
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
    /** Every DISCOVERED backend variant of a model id (xnnpack / vulkan / …), before load. */
    private val variants = LinkedHashMap<String, LinkedHashMap<BackendId, ModelBundle>>()
    /** Every SUCCESSFULLY-LOADED variant, so the active cycle + the agreement probe reuse handles. */
    private val loadedVariants = LinkedHashMap<String, LinkedHashMap<BackendId, Entry>>()
    /** The evidence-based forecast-backend switcher catalog (issue 20 STEP 4). */
    private var catalog: List<BackendAvailability> = emptyList()
    /** The user's requested forecast backend PER MODEL id (kv-persisted in :app); absent/null ⇒ auto
     *  (authority). Repopulated from [backendPrefProvider] on every discovery. */
    private val forecastBackendPrefs = HashMap<String, BackendId?>()
    /** Cached fp32-agreement verdict per NON-authority backend (null until a comparison runs). */
    private val agreementByBackend = HashMap<BackendId, Boolean>()
    /** The last on-device GPU-vs-CPU comparison (timings + numerics + agreement). */
    private var lastComparison: BackendComparison? = null
    /** Why the last [runBackendComparison] returned null, or null when it produced a comparison. The
     *  same string [runBackendComparison] puts in the note, kept reachable here because a caller that
     *  does not render [state]'s note (the model drill-down) would otherwise see only the null and be
     *  unable to say anything at all. */
    @Volatile
    var lastProbeRefusal: String? = null
        private set
    /** Process RSS growth (KB) attributed to the non-authority backend's load (best-effort). */
    private var vulkanLoadRssKb: Long? = null
    /** Written under [cycleMutex] on the inference thread (selectModel / refreshModelsLocked) but read
     *  unlocked off the default dispatcher in the runFromHistory preamble — @Volatile so that read sees
     *  the latest write instead of a stale cached value (FIX #10). */
    @Volatile
    private var selectedId: String? = null
    private val latencySamples = HashMap<String, ArrayDeque<Double>>()
    /** Cumulative per-model telemetry (durable via [telemetryStore]); loaded once, then in-memory. */
    private val cumulative = HashMap<String, CumulativeTelemetry>()
    private var telemetryLoaded = false
    /** WARMUP hysteresis: the largest `requiredSteps` window whose completion coverage has EVER been met.
     *  Warmup completion latches monotonically at or below it, so a single dropped slot from a gappy
     *  passive CGM cannot flap the forecast (and the glycemic status + circadian clock) back into
     *  "collecting context". In-memory only — a process restart re-evaluates from history. */
    @Volatile
    private var warmupSatisfiedUpTo = 0
    private val cycleMutex = Mutex()
    /** Thermal-gate hysteresis latch: once the die crosses [ThermalStatus.thresholdC] we stay BLOCKED
     *  until it falls back below `thresholdC - resumeMarginC`, so a temperature hovering on the
     *  threshold cannot flap the forecast on and off cycle to cycle. In-memory only. */
    @Volatile
    private var thermalBlocked = false

    /** The thermal verdict already reached for the cycle stamped [nowMs], so the two gates a
     *  history-fed cycle passes through consult one temperature sample rather than two. See
     *  [overTempNote]. One immutable holder behind one volatile write, so the stamp and the note it
     *  belongs to can never be read apart. */
    private class ThermalVerdict(val nowMs: Long, val note: String?)

    @Volatile
    private var thermalVerdict: ThermalVerdict? = null

    /** Register the backends the controller may route to (real XNNPACK + documented NPU stubs). */
    fun registerBackend(backend: InferenceBackend) { backends[backend.id] = backend }

    /** Reset the monotonic warmup latch — after an IN-PLACE data wipe (issue 5, which preserves the
     *  process so the sensor stays connected) the app must re-earn warmup from the now-empty history;
     *  the latch cannot be allowed to survive the reset, or the forecast would run on empty context. */
    suspend fun resetWarmupLatch() = cycleMutex.withLock { warmupSatisfiedUpTo = 0 }

    /**
     * Fit the classical baseline from the patient's own history — the manual action behind the
     * Models panel's button. Returns the fit (with its held-out evidence) or a failure naming the
     * refusal. Not serialised on [cycleMutex]: the fit reads history and touches no loaded handle,
     * and it takes long enough that blocking the cycle for its duration would stall the forecast.
     * [BaselineRunner] serialises fits against each other.
     */
    suspend fun fitBaseline(nowMs: Long, minCalWindows: Int): Result<BaselineFit> {
        val b = baseline ?: return Result.failure(IllegalStateException("no baseline runner"))
        val result = b.fit(history, nowMs, minCalWindows)
        if (result.isSuccess) {
            // The row already exists; what changes is that it now has a model behind it. Republish
            // immediately so the drill-down shows the new provenance without waiting for a tick.
            _state.value = _state.value.copy(running = runningModels(), baselineModel = b.fitted)
        }
        return result
    }

    /** Rehydrate the last persisted predictions so the overlay is populated before the first tick. */
    suspend fun restoreLast() {
        runCatching { baseline?.restore() }.onFailure { Timber.tag(TAG).w(it, "baseline restore failed") }
        // The baseline's row exists before any cycle has ticked — and before any fit — so publish it
        // here rather than leaving the panel empty until the first forecast lands.
        if (baseline != null) {
            _state.value = _state.value.copy(running = runningModels(), baselineModel = baseline.fitted)
        }
        val last = runCatching { predictionStore.loadLast() }.getOrNull() ?: return
        if (last.isNotEmpty()) {
            // Rehydrate the circadian belief from the restored selected forecast too (the graph's clock
            // axis reads [circadianTime] directly), so a cold start doesn't show a lit forecast with a
            // dark clock until the first live cycle. Null belief leaves the clock as-is.
            val sel = last.firstOrNull { it.selected }
            _state.value = _state.value.copy(
                predictions = last.sortedByDescending { it.selected },
                circadianTime = sel?.predictedTime ?: _state.value.circadianTime,
                circadianAnchorMs = sel?.predictedTime?.let { sel.anchorTsMs } ?: _state.value.circadianAnchorMs,
                note = "restored ${last.size} prediction(s) from last run",
            )
        }
    }

    /**
     * (Re)discover models on disk and load the running set: every discovered model up to the
     * [maxRunningProvider] cap (read fresh here), so the panel shows N rows, N forecasts run each
     * cycle, and N predictions are pushed. Closes handles that dropped out, loads each running model
     * onto its backend (falling back to the [StubBackend] when the `.pte` is absent or a real load
     * throws), and preserves the current selection when it stays in the running set, else falls back
     * to the first running model. Only the SELECTED model gets the full dual-backend catalog +
     * agreement probe (it alone feeds dosing). Runs its native loads on the `inference` thread.
     */
    suspend fun refreshModels() = cycleMutex.withLock { refreshModelsLocked() }

    /** The body of [refreshModels] WITHOUT acquiring [cycleMutex] — call only while already holding it
     *  (its in-class callers [deleteModel] and [refreshOrNote] do). External/unlocked callers use the
     *  public [refreshModels]. Splitting avoids a non-reentrant-[Mutex] deadlock while still serialising
     *  the loaded-set close/reload against a live [runCycle] — the race the 1→N model fan-out widened. */
    private suspend fun refreshModelsLocked() = withContext(dispatchers.inference) {
        if (!telemetryLoaded) {
            runCatching { telemetryStore?.load() }.getOrNull()?.let { cumulative.putAll(it) }
            telemetryLoaded = true
        }
        val bundles = store.discover()

        // Close every previously-loaded variant + regroup discovery. Refresh is rare (startup +
        // backend switch), so a full close/reload is simpler than diffing and avoids stale handles.
        loadedVariants.values.forEach { m -> m.values.forEach { runCatching { it.backend.close(it.handle) } } }
        loadedVariants.clear()
        loaded.clear()
        variants.clear()
        agreementByBackend.clear()
        lastComparison = null
        for (b in bundles) {
            variants.getOrPut(b.id) { LinkedHashMap() }[b.backendId] = b
        }

        // Re-read the persisted PER-MODEL backend preference for every discovered id (suspend provider;
        // fine on the inference dispatcher). A stale entry for a vanished model simply goes unused.
        for (id in variants.keys) {
            forecastBackendPrefs[id] = runCatching { backendPrefProvider(id) }.getOrNull()
        }

        // The running set this discovery: every discovered id up to the (fresh) user cap. `loaded` was
        // cleared above; the per-cycle fan-out and runningModels() both iterate it, so loading N here
        // makes the panel show N rows, run N forecasts, and push N predictions.
        val cap = runCatching { maxRunningProvider() }.getOrNull()?.coerceAtLeast(1) ?: DEFAULT_MAX_RUNNING
        val runningIds = variants.keys.take(cap).toList()
        // Preserve the current selection across refreshes (backend switch / model add-remove) when it
        // is still in the running set; else fall back to the first running id. selectModel() can only
        // ever pick a loaded (running) model, so a valid selection stays valid unless the cap shrank
        // below its position, in which case falling back to the first running model is correct.
        // A fitted baseline is a valid selection that is not in `runningIds` (it has no bundle to
        // discover), so it must survive a refresh; and when no neural model is installed at all it
        // is the only thing left to select.
        selectedId = selectedId
            ?.takeIf { it in runningIds || (it == BASELINE_MODEL_ID && baseline?.fitted != null) }
            ?: runningIds.firstOrNull()
            ?: BASELINE_MODEL_ID.takeIf { baseline?.fitted != null }
        // Full dual-backend catalog + agreement probe for the SELECTED model only (it feeds dosing);
        // this loads its variants into loadedVariants[selectedId].
        catalog = buildCatalog(selectedId)
        for (id in runningIds) {
            if (id != selectedId) loadModelActive(id)   // selected already loaded by buildCatalog
            loaded[id] = chooseActive(id)
        }

        // When the cap hides installed models, say so — folded into whichever note applies.
        val truncated = if (variants.size > cap)
            "running $cap of ${variants.size} installed models (cap in Settings → Forecast & models)"
        else null
        val note = when {
            bundles.isEmpty() ->
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
            loaded[selectedId]?.effectiveBackend?.let { it != BackendId.EXECUTORCH_XNNPACK_FP32 } == true ->
                listOfNotNull(
                    "forecast running on ${loaded[selectedId]?.effectiveBackend?.displayName()} " +
                        "(non-authoritative; dosing needs the agreement probe)",
                    truncated,
                ).joinToString(" · ")
            else -> truncated
        }
        _state.value = _state.value.copy(
            running = runningModels(),
            metas = metasSnapshot(),
            telemetry = telemetrySnapshot(),
            backendCatalog = catalog,
            requestedBackend = selectedId?.let { forecastBackendPrefs[it] },
            requestedBackendByModel = HashMap(forecastBackendPrefs),
            backendComparison = lastComparison,
            note = note,
        )
        Timber.tag(TAG).i(
            "refreshModels variants=%s active=%s prefs=%s catalog=%s",
            variants.mapValues { it.value.keys }, loaded[selectedId]?.effectiveBackend, forecastBackendPrefs,
            catalog.joinToString { "${it.backend}:${if (it.available) "ok" else "x"}" },
        )
    }

    /**
     * Probe every registered backend for the primary model and build the evidence-based switcher
     * catalog (issue 20 STEP 4). A backend with a real `.pte` for this engine is ATTEMPTED with a
     * native load: success ⇒ available (the handle is cached in [loadedVariants] for the cycle + the
     * agreement probe); a load failure ⇒ unavailable with the native reason verbatim. A backend with
     * no artifact surfaces its own documented reason (Neuron/LiteRT throw a static explanation) — so
     * the switcher can always state UNAMBIGUOUSLY why a path is unavailable, never a bare "stub".
     */
    private fun buildCatalog(primaryId: String?): List<BackendAvailability> {
        val vmap = primaryId?.let { variants[it] } ?: LinkedHashMap()
        val anyDesc = vmap.values.firstOrNull()?.descriptor
        val loadedForId = primaryId?.let { loadedVariants.getOrPut(it) { LinkedHashMap() } }
        return BACKEND_ORDER.mapNotNull { backends[it] }.map { backend ->
            val bid = backend.id
            val authoritative = bid == BackendId.EXECUTORCH_XNNPACK_FP32
            val variant = vmap[bid]
            if (variant != null && variant.pte.exists()) {
                val rssBefore = residentKb()
                val res = runCatching { backend.load(variant.descriptor, variant.pte) }
                val handle = res.getOrNull()
                if (handle != null) {
                    val entry = Entry(variant, backend, handle, bid, variant.precision, real = true)
                    loadedForId?.put(bid, entry)
                    if (!authoritative) vulkanLoadRssKb = (residentKb() - rssBefore).coerceAtLeast(0)
                    BackendAvailability(bid, variant.precision, available = true, authoritative, reason = null)
                } else {
                    BackendAvailability(
                        bid, variant.precision, available = false, authoritative,
                        reason = res.exceptionOrNull()?.message?.take(400) ?: "load failed",
                    )
                }
            } else {
                // No artifact for this engine: surface the backend's own documented reason.
                val reason = if (anyDesc != null) {
                    runCatching { backend.load(anyDesc, java.io.File(store.ensureDir(), "$primaryId.$bid.absent.pte")) }
                        .exceptionOrNull()?.message?.take(400)
                } else null
                BackendAvailability(
                    bid, backend.caps.precision, available = false, authoritative,
                    reason = reason ?: "no $bid artifact on device",
                )
            }
        }
    }

    /** Pick the active cycle backend for [id]: the requested pref if loaded, else the fp32 XNNPACK
     *  authority, else any loaded variant, else the StubBackend (real path blocked). */
    private fun chooseActive(id: String): Entry {
        val vmap = loadedVariants[id] ?: LinkedHashMap()
        val chosen = forecastBackendPrefs[id]?.let { vmap[it] }
            ?: vmap[BackendId.EXECUTORCH_XNNPACK_FP32]
            ?: vmap.values.firstOrNull()
        if (chosen != null) return chosen
        val bundle = variants[id]?.values?.firstOrNull() ?: error("no bundle for $id")
        val handle = stub.load(bundle.descriptor, bundle.pte)
        return Entry(bundle, stub, handle, BackendId.STUB, Precision.FP32, real = false)
    }

    /** Load backend variant(s) for a NON-selected running model into loadedVariants[id]: ALWAYS the
     *  fp32 XNNPACK authority when present (so this model can feed dosing the INSTANT it is selected —
     *  §3.6-E — without waiting for a catalog rebuild), PLUS its persisted display backend (the pref)
     *  if different, PLUS a fallback to any variant so it still forecasts. Cheap CPU loads; the full
     *  evidence-based dual-backend probe is [buildCatalog]'s job and runs for the selected model only.
     *  chooseActive falls back to the StubBackend when nothing loaded. */
    private fun loadModelActive(id: String) {
        val vmap = variants[id] ?: return
        val loadedForId = loadedVariants.getOrPut(id) { LinkedHashMap() }
        fun tryLoad(bid: BackendId?) {
            if (bid == null || loadedForId.containsKey(bid)) return
            val variant = vmap[bid] ?: return
            val backend = backends[bid] ?: return
            if (!variant.pte.exists()) return
            val handle = runCatching { backend.load(variant.descriptor, variant.pte) }.getOrNull() ?: return
            loadedForId[bid] = Entry(variant, backend, handle, bid, variant.precision, real = true)
        }
        tryLoad(BackendId.EXECUTORCH_XNNPACK_FP32) // authority — always, so selecting this model can dose
        tryLoad(forecastBackendPrefs[id])          // the DISPLAY-active backend (the pref), if different
        if (loadedForId.isEmpty()) for (bid in vmap.keys) { tryLoad(bid); if (loadedForId.isNotEmpty()) break }
    }

    /**
     * Set the FORECAST-CYCLE backend for one model id (issue 20 STEP 4). Governs the DISPLAY forecast
     * cycle ONLY; the dosing path stays fail-closed on a non-authoritative backend until the agreement
     * probe passes (§3.6-E). Assumes the caller has already persisted the choice to kv (discovery re-reads
     * it via [backendPrefProvider]); re-runs discovery so the active handle + catalog + "executing on" line
     * reflect the choice; if the requested backend cannot load, the controller falls back to the authority
     * and the requested-vs-executing divergence is visible to the user. Returns the backend ACTUALLY active
     * for [modelId] afterwards.
     */
    suspend fun setForecastBackend(modelId: String, pref: BackendId?): BackendId? {
        forecastBackendPrefs[modelId] = pref
        refreshModels()
        return loaded[modelId]?.effectiveBackend
    }

    /**
     * Debug-only: publish a NON_FINITE forecast for the selected model so the overlay/panels can be
     * verified to flag a degenerate forecast as ineligible (Phase 2 verify:
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
        /** §3.6-E: null = not measured; true/false = last fp32-agreement probe. The authoritative
         *  XNNPACK backend leaves this null and is trusted regardless; any other backend is trusted
         *  for dosing ONLY when this is true (BackendInfo.trustworthy). */
        val agreementOk: Boolean?,
    )

    /** The selected model's provenance as it is DISPLAYED (the switcher-chosen active backend), or
     *  null when nothing is loaded/selected. This follows [forecastBackendPrefs]; it drives the
     *  "Executing on:" line and panels — NOT the dosing path (see [authorityModelInfo]). */
    fun selectedModelInfo(): SelectedModelInfo? {
        val id = selectedId ?: return null
        val e = loaded[id] ?: return null
        val agreement = if (e.effectiveBackend == BackendId.EXECUTORCH_XNNPACK_FP32) null
                        else agreementByBackend[e.effectiveBackend]
        return SelectedModelInfo(id, e.bundle.descriptor, e.effectiveBackend, e.precision, e.real, agreement)
    }

    /**
     * The AUTHORITATIVE fp32 XNNPACK CPU provenance for the selected model's DOSING path (§3.6-E).
     * Deliberately ignores [forecastBackendPrefs]: dose advice must ALWAYS be computed on the fp32 CPU
     * authority regardless of which backend the switcher renders the DISPLAYED forecast with, so this
     * resolves the loaded XNNPACK variant directly from [loadedVariants] (the authority `.pte` is the
     * deployed one and is always loaded when discovery succeeds). [backend] is therefore always
     * [BackendId.EXECUTORCH_XNNPACK_FP32] and [agreementOk] is null (trusted by construction —
     * `BackendInfo.trustworthy`). Returns null (⇒ `:calc` fails closed) when the authority variant is
     * not loaded — a genuinely model-free state, never a silent promotion of a GPU/NPU path.
     */
    fun authorityModelInfo(): SelectedModelInfo? {
        val id = selectedId ?: return null
        val e = loadedVariants[id]?.get(BackendId.EXECUTORCH_XNNPACK_FP32) ?: return null
        if (!e.real) return null
        return SelectedModelInfo(
            id, e.bundle.descriptor, BackendId.EXECUTORCH_XNNPACK_FP32, e.precision, e.real, agreementOk = null,
        )
    }

    /**
     * Run one forward on the SELECTED model for the dose calculator's rolled search. Confined to the
     * single-thread `inference` dispatcher AND serialised against a live 5-min cycle through
     * [cycleMutex] (§2.3 — never two forwards concurrent on the one APU/CPU command queue). Throws if
     * no model is selected/loaded; the [com.t1dm.calc.RollingForecaster] catches and fails closed.
     */
    suspend fun runSelected(input: GraphTensors): GraphOutput = cycleMutex.withLock {
        val id = selectedId ?: error("no selected model")
        val e = loaded[id] ?: error("selected model not loaded")
        withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
    }

    /**
     * Run one forward for the DOSE CALCULATOR on the AUTHORITATIVE fp32 XNNPACK variant of the selected
     * model — NEVER the switcher-chosen display backend (§3.6-E). The forecast the `:calc` rails consume
     * is thus produced on the fp32 CPU authority whatever the user is looking at, so the backend-agreement
     * refusal never arises in normal use. A CPU forward is ~13.8 ms — negligible against the 5-min cycle.
     * Same confinement + [cycleMutex] serialisation as [runSelected]. Throws when the authority variant is
     * not loaded; [com.t1dm.calc.RollingForecaster] catches and fails closed.
     */
    suspend fun runSelectedAuthority(input: GraphTensors): GraphOutput = cycleMutex.withLock {
        val id = selectedId ?: error("no selected model")
        val e = loadedVariants[id]?.get(BackendId.EXECUTORCH_XNNPACK_FP32)
            ?: error("fp32 XNNPACK authority variant not loaded for $id")
        withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
    }

    // ── Experiments: an arbitrary masked set, on any loaded model ───────────────────

    /**
     * One experimental run: what was masked, what came back, and everything needed to draw it.
     *
     * [forecast] holds EVERY decoded slot, infill spans included, with `slotPatch` naming where
     * each sits — a Lab run is not a cycle and must not be reduced to a trailing horizon. Nothing
     * here is stored, pushed, or read by an alarm, a rail or a statistic.
     */
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

    /**
     * Drop a model's cached head — call whenever its artifact changes under a fixed id.
     *
     * The parity check that proves a head belongs to its graph runs ONCE per model and is
     * remembered; without this, a replaced artifact inherits the previous head's verdict and the
     * adapter path decodes against weights that are not the graph's.
     */
    fun evictHead(modelId: String) = heads.evict(modelId)

    /** What a model's re-runnable head turned out to be — `null` for an unknown model. */
    fun headState(modelId: String): HeadCache.State? =
        loaded[modelId]?.bundle?.let { heads.stateOf(it) }

    /**
     * Run [modelId] over one window with an arbitrary masked set.
     *
     * [spans] are context-relative patch indices; [withForecast] appends the future zone. A pure
     * infill passes `withForecast = false`, which is what a gap repair wants — the evidence on
     * BOTH sides of the gap is then real, and no slot is spent on a forecast nobody asked for.
     *
     * When [lora] is non-null the fan is assembled from the head re-run over the graph's own
     * `slot_hidden` instead of the graph's `head_raw`. A model whose head is absent or disagrees
     * refuses rather than silently falling back to the unadapted fan — the whole point of the run
     * would otherwise be invisible.
     */
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
        heads.verify(entry.bundle, out.slotHidden, out.headRaw, gi.mSlots)

        val headRaw: List<Double> = if (lora == null) {
            out.headRaw.map { it.toDouble() }
        } else {
            val state = heads.stateOf(entry.bundle)
            if (state !is HeadCache.State.Ready) {
                error("model $modelId takes no adapter: ${(state as? HeadCache.State.Unusable)?.why ?: "no head file"}")
            }
            val hidden = out.slotHidden ?: error("the graph emitted no slot_hidden to adapt")
            state.head.setLora(lora)
            try {
                withContext(dispatchers.default) { state.head.forward(hidden.map { it.toDouble() }, gi.mSlots) }
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

    /**
     * Replay historical windows through [modelId] and pair each with the BG that actually
     * followed — the training set an adapter is fitted on.
     *
     * The hidden states are recomputed here rather than stored per cycle: they are a function of
     * the model and the window, so storing them would add megabytes a day AND go stale the moment
     * the artifact was replaced. Windows are returned oldest-first, which is what makes the fit's
     * held-out split chronological.
     *
     * A window whose realised horizon carries a gap is DROPPED. `SPEC/invariants.md` §1 makes a
     * carried-forward value a presentation step, and fitting on one teaches the adapter that
     * glucose holds perfectly still for an hour.
     */
    suspend fun loraSamples(
        modelId: String,
        maxWindows: Int,
        strideSteps: Int,
        onWindow: ((done: Int, total: Int) -> Unit)? = null,
    ): List<LoraSample> {
        // The handle is captured under the lock, and re-checked under it before every forward: a
        // discovery refresh can close a model's backend handle mid-replay, and running against a
        // closed handle is a native crash rather than an exception.
        val entry = cycleMutex.withLock { loaded[modelId] } ?: error("model $modelId is not loaded")
        val desc = entry.bundle.descriptor
        val ctxSteps = desc.minContextPatches * desc.patchSize
        val predSteps = predSteps(desc)
        val stride = strideSteps.coerceAtLeast(desc.patchSize)
        val want = ctxSteps + predSteps + stride * maxWindows.coerceAtLeast(1)
        val dense = history.recentBgSeries(want, ctxSteps + predSteps) ?: return emptyList()
        // The FIT series is what a target may come from, and it is a different series: a different
        // filter, a different length, and its own grid origin. Project it onto the context grid by
        // TIMESTAMP — reading it by the context's index would pair each window with glucose from
        // some other moment, and every fit would look ordinary while learning the wrong pairing.
        val measured = history.fitBgSeries(want, ctxSteps + predSteps)
            ?: return emptyList()   // no measured series ⇒ no honest target; never fall back to
                                    // the carried-forward one, which is a flat stretch that never
                                    // happened (`SPEC/invariants.md` §1).
        val n = dense.mgdl.size
        val target = DoubleArray(n) { Double.NaN }
        for (i in measured.mgdl.indices) {
            val j = ((measured.gridStartMs - dense.gridStartMs) / GRID_MS).toInt() + i
            if (j in 0 until n) target[j] = measured.mgdl[i]
        }
        val ch = buildDoseChannels(dense)
        // Which slots of `dense` are the model's OWN OUTPUT. `SPEC/invariants.md` §1 keeps a
        // promoted reconstruction out of a fit window's context, and this is what says where one
        // is: `target` is NaN at a sensor gap and at a reconstruction alike, so a test on it
        // cannot tell the rule's subject from an ordinary dropout.
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
        // The masked run a non-forecast window carries, in patches: as long as the forecast horizon
        // where the context has room, so the three geometries pose the model a comparable question.
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
            // The doses that ACTUALLY happened over the horizon are the announced plan for a
            // training window: that is the conditioning the model was trained under, and feeding
            // it the no-event baseline instead would teach the adapter to expect nothing.
            val ahead = ModelChannels(
                ch.carb.copyOfRange(o, o + predSteps),
                ch.insulin.copyOfRange(o, o + predSteps),
                ch.exercise.copyOfRange(o, o + predSteps),
            )
            // Which shape this window poses. A backcast or an infill masks a run INSIDE the
            // context and has no future zone at all, so its target is that run's own realised
            // glucose rather than the horizon's — and it carries no counterfactual, the guard
            // being able to read a response only at a horizon's terminal step.
            val geometry = LoraGeometryPlan.geometryAt(index)
            val startPatch = LoraGeometryPlan.startPatch(geometry, ctxPatches, spanPatches)
            val isForecastWindow = geometry == MaskGeometry.FORECAST || startPatch == null
            // The window's ANCHOR must be a real measurement, and only the anchor.
            //
            // `build_graph_input` anchors a masked span on ONE step: the last step of the patch to
            // its left, or the first step of the patch to its right when there is no left one. That
            // step is what the pinball target, the baseline `d0` and both of the guard's terminal
            // reads are measured from, so a promoted reconstruction sitting there has the model
            // grading its own work.
            //
            // Testing the whole context instead rejected every window on any real record: `target`
            // is the fit series — real sensor signal only, NaN everywhere else — and a multi-day
            // context with no gap at all does not survive a single sensor change. The fit then
            // reported "0 usable windows" on a phone with months of history.
            val anchorStep = anchorStepOf(ctxFrom, o, startPatch, spanPatches, desc.patchSize)
            if (anchorStep !in target.indices || target[anchorStep].isNaN()) continue
            // And NO reconstruction anywhere in the context, which is the rule itself rather than a
            // proxy for it. A carried-forward slot stays admissible: the model is CONDITIONED on a
            // dense context by design, and refusing every gap — which is what testing `target` for
            // NaN across the window did — left a record with one sensor change contributing no
            // usable window at all.
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
            // The masked run's own realised glucose, measured — the context test above has already
            // refused any window where one of these slots is not a real reading.
            val spanTarget = if (isForecastWindow) {
                realized
            } else {
                val from = ctxFrom + startPatch!! * desc.patchSize
                target.copyOfRange(from, from + spanPatches * desc.patchSize)
            }
            // A masked run's own target is measured or the window is dropped. `realized` was
            // already checked; this is the branch that reads from inside the context instead.
            if (spanTarget.any { it.isNaN() }) continue
            // ONE forward per lock acquisition, not one lock for the whole replay: a few hundred
            // windows is minutes of forwards, and holding the cycle mutex across them would starve
            // the live 5-minute forecast for as long as a fit runs.
            val run = cycleMutex.withLock {
                if (loaded[modelId] !== entry) return out
                withContext(dispatchers.inference) { entry.backend.run(entry.handle, GraphIo.tensors(gi)) }
            }
            heads.verify(entry.bundle, run.slotHidden, run.headRaw, gi.mSlots)
            val hidden = run.slotHidden ?: return out
            val d = desc.dModel

            // ── the counterfactual branch ──
            //
            // The SAME window with one unit of insulin added to the horizon's dose channel, so the
            // fit can see what the model does with it. A second trunk forward, and the only reason
            // the replay costs more than it did — which is why it is spent on FORECAST windows
            // only: the guard measures at the horizon, and the terminal step of an infill is not
            // one. Everything else about the window is byte-identical, so the response is a
            // property of the dose and not of the two forwards disagreeing.
            // FORECAST windows only, and that is what keeps the pairing cost near half: the guard
            // reads the response at the horizon's terminal step, which an infill does not have.
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
                runPert.slotHidden
            }

            out.add(
                LoraSample(
                    hidden = hidden.take(gi.nMasked * d).map { it.toDouble() },
                    anchors = gi.anchors.take(gi.nMasked),
                    targetBg = spanTarget.toList(),
                    nSlots = gi.nMasked,
                    // Either the WHOLE window or nothing: a truncated pairing would have the fit
                    // train one branch against a shorter other, and the crate refuses it by name.
                    hiddenPert = if (hiddenPert != null && hiddenPert.size >= gi.nMasked * d) {
                        hiddenPert.take(gi.nMasked * d).map { it.toDouble() }
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

    /** Fit an adapter for [modelId] on [samples]. Attaches nothing — the caller decides. */
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
        // Deliberately OUTSIDE the cycle mutex: a fit is minutes of CPU, and it touches no backend
        // handle. It reads the head's frozen weights and carries its own adapter through the
        // gradient loop, so a cycle running the same head with an attached adapter is unaffected.
        return withContext(dispatchers.default) {
            native.loraTrain(state.head, entry.bundle.descriptor, samples, config, opts, progress)
        }
    }

    /**
     * Measure what a STORED adapter does to the model's marginal response to insulin.
     *
     * The fit runs this on its own held-out windows and stores the verdict; this is the same
     * measurement for an adapter that arrived some other way — imported, restored from an archive,
     * or fitted before the guard existed — none of which carry one, and all of which are refused at
     * attach until somebody looks.
     *
     * The bar comes from the crate ([NativeCore.loraGuardOptsFit]) rather than from a literal here,
     * so a probe and a fit cannot reach different verdicts about the same adapter.
     */
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
        // Outside the cycle mutex for the reason the fit is: head-only arithmetic over already
        // computed hidden states, touching no backend handle.
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

    /** The descriptor of a loaded model, for a caller that must respect its geometry. */
    fun descriptorOf(modelId: String): ModelDescriptor? = loaded[modelId]?.bundle?.descriptor

    /**
     * Manually pick the SELECTED model — the one whose forecast the BG panel draws and whose fp32 CPU
     * authority feeds dosing; a no-op if [id] is not in the running set. All running models keep running
     * and pushing predictions regardless; selection governs only the DISPLAYED forecast (+ circadian) and
     * the dosing authority. Two steps: (1) immediately re-flag the already-computed predictions so the
     * panel switches to [id]'s fan this instant (rather than waiting for the next cycle); (2) re-run
     * discovery so the Compute-backend switcher catalog + agreement probe describe the newly selected
     * model (and its dual-backend catalog is (re)loaded). Suspends — callers launch it in a scope.
     */
    suspend fun selectModel(id: String) {
        // Guard the selectedId write + the immediate prediction re-flag under cycleMutex (FIX #10): they
        // read and mutate the same loaded/selectedId that refreshModelsLocked() clears+reloads on the
        // inference thread and that runFromHistory's preamble snapshots. Release BEFORE refreshModels() —
        // it re-acquires the non-reentrant cycleMutex and would otherwise self-deadlock.
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
                // Switching to a model with no time head clears the belief outright rather than
                // leaving the previous model's on screen, frozen at the instant of the switch.
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

    /**
     * Delete the model with descriptor id [id] from disk (its descriptor+`.pte` pair(s) via
     * [ModelStore.delete]) and purge its in-memory footprint: the durable per-model telemetry, the
     * rolling-latency window, and the forecast-backend preference. Serialised on [cycleMutex] so it
     * never races a live cycle over the loaded set, and the actual file removal runs on the `inference`
     * thread. [refreshModels] re-scans afterwards, closing the just-deleted handles and reselecting the
     * next remaining model (or none), and we strip any stale prediction for [id] from the overlay
     * immediately so the graph does not keep drawing a fan for a model that no longer exists. Returns
     * whether a matching artifact was actually removed from disk.
     */
    suspend fun deleteModel(id: String): Boolean = cycleMutex.withLock {
        heads.evict(id)
        // The baseline has no artifact to unlink; discarding the fitted weights is the same act, and
        // the row carries the same ✕, so removing it must mean the same thing.
        if (id == BASELINE_MODEL_ID) {
            val had = baseline?.fitted != null
            baseline?.clear()
            if (selectedId == BASELINE_MODEL_ID) selectedId = null
            // The same purge the neural branch performs: stale counters for a model that no longer
            // exists would otherwise be attributed to the next fit under the same id.
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
        cumulative.remove(id); latencySamples.remove(id); forecastBackendPrefs.remove(id)
        runCatching { telemetryStore?.save(HashMap(cumulative)) }
        refreshModelsLocked() // already under cycleMutex — the public refreshModels would self-deadlock
        _state.value = _state.value.copy(predictions = _state.value.predictions.filterNot { it.modelId == id })
        removed
    }

    /**
     * Run the honest on-device comparison of the non-authority backend (the Vulkan GPU delegate)
     * against the fp32 XNNPACK authority (issue 20 STEP 3 + §3.6-E). Both run the SAME fixed
     * deterministic input; [runs] warm forwards each are timed (median) plus the first cold forward,
     * and `head_raw` + the decoded mg/dL median are compared worst-case. The decoded-mg/dL agreement
     * verdict is cached ([agreementByBackend]) so — and ONLY so — the backend may feed the dosing
     * path. Serialised on [cycleMutex] against a live cycle; runs on the `inference` thread. Returns
     * null (with a note) when there is no non-authority backend loaded to compare.
     */
    suspend fun runBackendComparison(runs: Int = 20): BackendComparison? = cycleMutex.withLock {
        // Every refusal below states itself, in the note AND in [lastProbeRefusal]. Returning a bare
        // null told the drill-down nothing, so its button ran the probe and the screen said nothing.
        fun refuse(why: String): BackendComparison? {
            lastProbeRefusal = why
            _state.value = _state.value.copy(note = why)
            return null
        }
        val id = selectedId ?: return@withLock refuse("no model selected")
        val vmap = loadedVariants[id] ?: return@withLock refuse("selected model not loaded")
        val authority = vmap[BackendId.EXECUTORCH_XNNPACK_FP32]
            ?: return@withLock refuse("fp32 XNNPACK authority not loaded")
        val other = vmap.entries.firstOrNull { it.key != BackendId.EXECUTORCH_XNNPACK_FP32 }?.value
            ?: return@withLock refuse("no non-authoritative backend loaded to compare")
        lastProbeRefusal = null
        val desc = authority.bundle.descriptor

        suspend fun timeOne(e: Entry): Double {
            val input = probeInput(desc)
            val t0 = System.nanoTime()
            withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
            return (System.nanoTime() - t0) / 1_000_000.0
        }

        // Cold forward each (first call — includes any lazy shader/kernel warmup on the GPU path).
        val coldAuth = timeOne(authority)
        val coldOther = timeOne(other)
        // Warm medians.
        val authMs = ArrayList<Double>(runs)
        val otherMs = ArrayList<Double>(runs)
        repeat(runs) { authMs.add(timeOne(authority)); otherMs.add(timeOne(other)) }

        // Numerics on one more shared input: decode both to mg/dL and take the worst-case deltas.
        val probe = probeGraphInput(desc)
        val authOut = withContext(dispatchers.inference) {
            authority.backend.run(authority.handle, GraphIo.tensors(probe))
        }
        val otherOut = withContext(dispatchers.inference) {
            other.backend.run(other.handle, GraphIo.tensors(probe))
        }
        val headDelta = maxAbsDelta(authOut.headRaw, otherOut.headRaw)
        val fAuth = withContext(dispatchers.default) { decode(desc, authOut, probe) }
        val fOther = withContext(dispatchers.default) { decode(desc, otherOut, probe) }
        val mgdlDelta = maxAbsDeltaD(fAuth.medianBg, fOther.medianBg)
        val agree = mgdlDelta.isFinite() && mgdlDelta <= AGREEMENT_TOL_MGDL && headDelta.isFinite()

        agreementByBackend[other.effectiveBackend] = agree
        val cmp = BackendComparison(
            backend = other.effectiveBackend,
            authority = BackendId.EXECUTORCH_XNNPACK_FP32,
            runs = runs,
            warmMedianMsBackend = median(otherMs),
            warmMedianMsAuthority = median(authMs),
            coldMsBackend = coldOther,
            coldMsAuthority = coldAuth,
            maxAbsHeadRawDelta = headDelta,
            maxAbsDecodedMgdlDelta = mgdlDelta,
            toleranceMgdl = AGREEMENT_TOL_MGDL,
            agreementOk = agree,
            loadRssGrowthKb = vulkanLoadRssKb,
        )
        lastComparison = cmp
        _state.value = _state.value.copy(
            backendComparison = cmp,
            note = "agreement probe: ${other.effectiveBackend.displayName()} vs CPU — " +
                "mg/dL Δ=%.3f (tol %.1f) ⇒ %s".format(mgdlDelta, AGREEMENT_TOL_MGDL, if (agree) "PASS" else "FAIL"),
        )
        Timber.tag(TAG).i(
            "backend comparison %s vs XNNPACK: warm %.2f vs %.2f ms (cold %.1f vs %.1f), headΔ=%.3e mgdlΔ=%.4f agree=%s rss+%sKB",
            other.effectiveBackend, cmp.warmMedianMsBackend, cmp.warmMedianMsAuthority,
            cmp.coldMsBackend, cmp.coldMsAuthority, headDelta, mgdlDelta, agree, vulkanLoadRssKb,
        )
        cmp
    }

    /**
     * Run one forward of [backendId]'s loaded variant on the FIXED deterministic probe input and
     * return its raw `head_raw` (debug/verification only — the CPU-unchanged proof compares the
     * XNNPACK head_raw byte-for-byte across the stock and custom AAR). Null if that variant is not
     * loaded. Serialised like every other forward.
     */
    suspend fun debugHeadRaw(backendId: BackendId): FloatArray? = cycleMutex.withLock {
        val id = selectedId ?: return@withLock null
        val e = loadedVariants[id]?.get(backendId) ?: return@withLock null
        val input = probeInput(e.bundle.descriptor)
        withContext(dispatchers.inference) { e.backend.run(e.handle, input) }.headRaw
    }

    /** Build the FIXED, time-independent, dose-free probe input (deterministic across runs/builds).
     *  The smoothing window is PINNED to [InferenceControllerDefaults.SAVGOL_WINDOW], never the user
     *  setting: this input feeds the §3.6-E fp16-agreement verdict and the byte-for-byte
     *  CPU-unchanged proof, both of which must stay invariant under a Settings knob. */
    private suspend fun probeInput(desc: ModelDescriptor): GraphTensors =
        GraphIo.tensors(probeGraphInput(desc))

    /** The probe's built input, kept apart from [probeInput] so the comparison can read its
     *  anchors for the decode rather than reconstructing them from the series. */
    private suspend fun probeGraphInput(desc: ModelDescriptor): GraphInput {
        val steps = desc.maxContextPatches * desc.patchSize
        val series = SyntheticContext.plausible24h(steps, anchorTsMs = PROBE_ANCHOR_MS)
        val n = series.mgdl.size
        return buildGraphInput(
            desc,
            series.mgdl,
            ModelChannels.zero(n),
            null,
            emptyList(),
            InferenceControllerDefaults.SAVGOL_WINDOW,
        )
    }

    /**
     * D1/D4 thermal gate (read FRESH each cycle): consult [thermalProvider] and, with hysteresis,
     * decide whether inference must pause because the device is too hot. Returns the banner note while
     * BLOCKED, else null (and clears the latch). A null status (gate disabled or temperature
     * unreadable) never gates. Latches at [ThermalStatus.thresholdC] and resumes only below
     * `thresholdC - resumeMarginC` so a temperature hovering on the threshold cannot flap the forecast.
     *
     * **Once per cycle, not once per gate.** A history-fed cycle passes this gate twice — [runFromHistory]
     * sharpens the message before the warmup gate can claim the banner, then [runCycle] applies it again
     * as the universal chokepoint every forecast funnels through. Both calls carry the SAME [cycleNowMs],
     * which is what identifies them as one cycle, so the second reuses the first's verdict: one
     * [thermalProvider] invocation (three settings reads and a battery-sensor binder round trip) instead
     * of two, and — the part that was a latent defect rather than a cost — ONE advance of the hysteresis
     * latch instead of two advances against two different temperature samples.
     *
     * This is a per-cycle share, deliberately NOT a wall-clock cache: a cycle with a fresh `nowMs` always
     * re-reads, and [runCycle] driven directly (the synthetic/manual path) carries its own `nowMs` and so
     * has its own read. And it is confined to the *inference* gate — the deterministic over-temperature
     * ALARM runs on its own uncached read in `AlarmController`, so nothing here can move when that alarm
     * fires.
     *
     * What the discarded second read actually offered is worth naming: the temperature behind
     * [thermalProvider] is `ACTION_BATTERY_CHANGED`'s `EXTRA_TEMPERATURE`, a STICKY broadcast the system
     * refreshes on battery events rather than on demand. Two reads separated by the warmup query and the
     * history fetch that sit between these gates are overwhelmingly the same sticky Intent and hence the
     * same number — which is why re-deciding on it could only ever re-affirm the first verdict, and why
     * doing so twice against the hysteresis latch was the defect rather than the safeguard.
     */
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

    /** Fire a cycle off the shared BG history (the `GridTick` path). */
    suspend fun runFromHistory(cause: InferenceCause = InferenceCause.GRID_TICK, nowMs: Long) {
        // Anchor context-window sizing + the warmup gate to the SELECTED model's descriptor (mirrors
        // buildFutureChannels) — with N running models `loaded.values.first()` is the first-discovered,
        // NOT necessarily the selected/displayed model whose forecast + warmup these bounds govern.
        // Snapshot the descriptor + the selected entry's provenance ONCE under cycleMutex (FIX #10): the
        // whole preamble below runs off the default dispatcher and must not read the live loaded/selectedId
        // while refreshModelsLocked() clears+reloads them on the inference thread. Release the lock at once —
        // refreshModels(), circadianDuringWarmup(), and runCycle() below each re-acquire the non-reentrant
        // cycleMutex, so holding it across them (or across the forward) would deadlock/serialise.
        val (descAny, selReal, selHasTime) = cycleMutex.withLock {
            val selEntry = loaded[selectedId]
            val desc = (selEntry ?: loaded.values.firstOrNull())?.bundle?.descriptor
            Triple(desc, selEntry?.real ?: false, selEntry?.bundle?.descriptor?.time != null)
        }
        // A fitted baseline is a running model with no descriptor, so the descriptor-derived context
        // bounds below fall back to the exported models' own window. Without this the grid tick
        // returns here on a device carrying only the baseline, the relaxed guard in runCycle is never
        // reached, and the Models panel lists a running model that silently never forecasts.
        val baselineOnly = descAny == null && baseline?.fitted != null
        if (descAny == null && !baselineOnly) { refreshModels(); return }
        // Thermal gate BEFORE the warmup gate: while blocked, publish a clean over-temp banner (empty
        // predictions, PRESERVING circadianTime so the clock stays lit) instead of a warmup/forecast
        // state. runCycle carries the universal chokepoint guard; this one only sharpens the message.
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

        // ── WARMUP gate (inference-runtime.md): withhold forecasts until at least `warmupHours` of
        //    MEASURED (non-interpolated) context has accrued, floored at the model's MIN_CONTEXT.
        //    DISTINCT from the per-cycle freshness gate below; both remain in force.
        val minContextHours = minSteps * GRID_MS / MS_PER_HOUR
        val requiredHours = warmupHoursProvider().coerceAtLeast(minContextHours)
        val requiredSteps = Math.round(requiredHours * MS_PER_HOUR / GRID_MS).toInt()
        val measuredSteps = runCatching { history.measuredStepsInWindow(requiredSteps) }.getOrDefault(0)
        // Warmup completes at WARMUP_COMPLETION_FRACTION coverage of the required window — a passive
        // advertisement CGM never fills every slot, so demanding a gapless window kept the forecast stuck
        // in (and flapping around) warmup. Once met it LATCHES monotonically, so a later dropped slot can
        // no longer flap the forecast, glycemic status, and circadian clock back into "collecting context".
        val completionSteps = kotlin.math.ceil(requiredSteps * WARMUP_COMPLETION_FRACTION).toInt()
        if (measuredSteps >= completionSteps) warmupSatisfiedUpTo = maxOf(warmupSatisfiedUpTo, requiredSteps)
        val warmedUp = measuredSteps >= completionSteps || requiredSteps <= warmupSatisfiedUpTo
        if (!warmedUp) {
            val measuredHours = measuredSteps * GRID_MS / MS_PER_HOUR
            // The BG forecast stays (correctly) suppressed — §3.6 gates untouched, [predictions] empty.
            // BUT the circadian-phase belief is NOT a glucose forecast and NOT a dosing signal, and it
            // degrades gracefully with little context, so we still publish it (issues 7 & 9) as a
            // low-context belief. It survives warmup via [circadianTime], never re-entering the forecast
            // path. Fail-OPEN to null when there is not yet enough raw history to run a single forward.
            // Only when the SELECTED model has a time head. `circadianDuringWarmup` runs a forward on
            // whatever descriptor it can find, so with the baseline selected it would publish another
            // model's belief under the selection — and pay for a forward pass to do it.
            val warmupBelief = if (selHasTime) {
                runCatching { circadianDuringWarmup() }.getOrNull()
            } else {
                null
            }
            // The BASELINE is not gated by this window, and should not be. The gate exists because a
            // neural export conditions on 96–288 steps and says nothing trustworthy with less; the ridge
            // reads `nLags` trailing values and its own IOB/COB, so 24 h of accrual is a requirement it
            // does not have. Withholding it bought a blank panel for a day per install and no safety:
            // it cannot reach the dose calculator, the ISF/ICR probe or the rolled overlay, all of which
            // fail closed on a model with no descriptor and no graph.
            //
            // Its OWN guards still decide. It must be fitted, the series must cover `nLags`, and the
            // §3.6 degeneracy classification applies to its fan exactly as to any other.
            val warmupBaseline = runCatching { baselineDuringWarmup(nowMs) }
                .getOrElse { Timber.tag(TAG).w(it, "baseline cycle failed during warmup"); null }
            _state.value = _state.value.copy(
                // Only the baseline's. The neural fan stays suppressed, which is what this gate is for.
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

    /**
     * Run one full cycle over [series]. Public so the service can drive a synthetic/manual cycle
     * (cold-start verification with a plausible 24 h series — the sensor is not needed).
     */
    suspend fun runCycle(cause: InferenceCause, series: BgSeries, nowMs: Long) = cycleMutex.withLock {
        // A fitted baseline is a running model in its own right, so a device with no `.pte` pushed
        // still has something to publish and must not bail out of the cycle here.
        if (loaded.isEmpty() && baseline?.fitted == null) {
            refreshOrNote()
            if (loaded.isEmpty() && baseline?.fitted == null) return@withLock
        }
        // Thermal gate — the UNIVERSAL chokepoint: every forecast (grid tick, manual, synthetic) funnels
        // through here, so blocking here blocks them all. Empty predictions + OVER_TEMPERATURE cause;
        // circadianTime is left untouched by copy() so the clock stays lit while inference is paused.
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

        // ONE shared context build across the running set: the carb-appearance (feat 1) + insulin-
        // action (feat 2) channels reconstructed from the logged meals/doses/basal (SPEC §3.3),
        // aligned to the BG grid. Model-independent (the per-desc normalization happens in
        // build_context); a null/failed source falls back to the `normalize(0)` no-dose baseline.
        val doseChannels = buildDoseChannels(series)

        // ONE shared PREDICTION-ZONE build: the COMMITTED dose tails (already-logged meals/doses still
        // absorbing past the now-boundary) reconstructed via the SAME curve engine the calculator's
        // baseline roll uses (SPEC §3.3). Carried into build_context's announced-future slots so a
        // just-logged meal RAISES (and a just-logged insulin LOWERS) the main-view forecast, instead
        // of appearing in the past then vanishing at the boundary (an impossible drop-off ⇒ wrong dip).
        // Model-independent (rollStartMs is the grid boundary; predSteps is the fixed pred zone) but
        // anchored to the SELECTED model's descriptor for a stable, deterministic pred-zone length.
        val anchorDesc = (loaded[selectedId] ?: loaded.values.firstOrNull())?.bundle?.descriptor
        val futureChannels = anchorDesc?.let { buildFutureChannels(series, it) }

        // Serial fan-out over the running set (never concurrent on the one command queue).
        for ((id, entry) in loaded) {
            val pred = runCatching { runOne(entry, id == selectedId, series, doseChannels, futureChannels, cycleTs, stale) }
                .getOrElse {
                    Timber.tag(TAG).w(it, "model %s cycle failed", id); null
                }
            if (pred != null) preds.add(pred)
        }

        // The baseline runs on the same anchor and the same grid, but off the RAW series and its own
        // causal IOB/COB — see [BaselineRunner] for why it does not share the neural input transform.
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
        // Whether the SELECTED model can produce an hour-of-day belief at all. False for the
        // classical baseline, which carries no time head — and false, correctly, for any neural
        // export cut without one.
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
            // A full cycle republishes the circadian belief from the selected prediction (full context,
            // not low-context) so the clock/dial track the live forecast the moment warmup clears. When
            // THIS cycle's forecast carries no decoded time, keep the last known belief rather than
            // blinking the clock OFF while a forecast is showing (a slow circadian phase, not a dosing signal).
            //
            // That carry-over holds a belief across a TRANSIENT decode failure of a model that HAS a
            // time head. It must not survive a switch to a model that has none: the belief on screen
            // would then belong to a different model, frozen at the instant of the switch, with the
            // panel's own "no time section" state unreachable because a stale value is not null.
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

    /**
     * Decode a graph output against the input that produced it: the per-slot anchors and the slot
     * layout are the input's, never reconstructed. A decode that re-derives its own anchor is how
     * an infill span silently anchors on the forecast's neighbour.
     */
    private fun decode(desc: ModelDescriptor, out: GraphOutput, gi: GraphInput): Forecast =
        native.assembleDecode(
            desc,
            out.headRaw.map { it.toDouble() },
            gi.anchors,
            gi.slotPatch,
            gi.nMasked,
            CARRY_SPREAD,
        )

    /**
     * The adapted `head_raw` for this cycle, or null to run frozen.
     *
     * Null is the ordinary case — no adapter attached, or no store wired. It is NOT a fallback: a
     * model with an adapter attached whose adapter cannot be applied THROWS, which drops that
     * model's prediction for the cycle. Falling back to the frozen fan would store, alarm on and
     * calibrate against a forecaster the user is not looking at, and mix two of them in one
     * model's history with nothing recording which produced which row.
     */
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
        val hidden = out.slotHidden
            ?: error("adapter attached to ${entry.bundle.id} but the graph emits no slot_hidden")
        state.head.setLora(w)
        return try {
            withContext(dispatchers.default) { state.head.forward(hidden.map { it.toDouble() }, gi.mSlots) }
        } finally {
            state.head.setLora(null)
        }
    }

    /**
     * The adapted `head_raw` for a caller outside the cycle — the dose path, which must score on
     * the same forecaster the panel draws.
     *
     * `null` is the frozen model. An ATTACHED adapter that cannot be applied THROWS here rather
     * than returning null: the cycle can fall back to the frozen fan because a forecast is due
     * either way, but a dose scored on a different model from the displayed one is exactly the
     * disagreement §3.6-E exists to prevent, so the roll fails closed instead.
     */
    suspend fun adaptedHeadRawFor(modelId: String, out: GraphOutput, mSlots: Int): List<Double>? {
        val store = loraStore ?: return null
        val w = store.attached(modelId) ?: return null
        val entry = cycleMutex.withLock { loaded[modelId] } ?: error("model $modelId is not loaded")
        // The dose path can be the FIRST caller after a process start — a recommendation asked for
        // before any cycle has run — so it proves the head against this very forward rather than
        // assuming a cycle already did.
        heads.verify(entry.bundle, out.slotHidden, out.headRaw, mSlots)
        val state = heads.stateOf(entry.bundle)
        if (state !is HeadCache.State.Ready) {
            error("adapter attached to $modelId but its head is unusable: ${(state as? HeadCache.State.Unusable)?.why ?: "no head file"}")
        }
        val hidden = out.slotHidden ?: error("adapter attached to $modelId but the graph emits no slot_hidden")
        state.head.setLora(w)
        return try {
            withContext(dispatchers.default) { state.head.forward(hidden.map { it.toDouble() }, mSlots) }
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

        heads.verify(entry.bundle, out.slotHidden, out.headRaw, gi.mSlots)
        // An attached adapter re-runs the head over the graph's own hidden states. It FAILS OPEN to
        // the frozen fan: a forecast is due this cycle either way, and a fan the app can justify is
        // better than none. The panel names which one it got.
        val adapted = adaptedHeadRaw(entry, gi, out)

        // The cycle masks nothing but the future zone, so every decoded slot IS the forecast —
        // but slice by patch anyway rather than by count, so an added infill span cannot quietly
        // shift which rows the panel, the alarms and the calculator read.
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

        // Circadian-phase belief (Phase 7A): the co-trained time probe's second `.pte` output,
        // reduced to a predicted hour-of-day in the Rust core. Purely additive — fail-OPEN to null
        // (descriptor lacks a time section, backend returned no slot-1 tensor, or the decode
        // throws) so it can NEVER perturb the BG forecast/degeneracy path above.
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

    /**
     * Run ONE forward on the selected model DURING WARMUP purely to obtain the circadian-phase belief
     * (issues 7 & 9) — the BG forecast stays suppressed and is never derived here. Returns the decoded
     * belief + the series anchor it was formed at, or null when it cannot run at all: no real backend
     * (the stub carries no probe), a descriptor without a time section, too little raw history for a
     * single forward, or any decode hiccup. Serialised on [cycleMutex] like every other forward so it
     * never overlaps a calculator `runSelected` on the one command queue. Never throws (the caller
     * wraps it too); a time-probe hiccup must not perturb the warmup gate.
     */
    /**
     * One baseline forecast while the WARMUP gate is withholding the neural fan.
     *
     * The series is asked for at the BASELINE's own floor — `nLags` trailing steps — not the neural
     * minimum, because that floor is the whole reason this runs at all. [BaselineRunner.predict] rejects
     * anything shorter, so the two agree and a short history simply yields null.
     *
     * Never marked `selected`: selection drives the calculator, the ISF/ICR probe and the rolled overlay,
     * and all three fail closed on a model with no graph. During warmup this fan is the panel's, and
     * nothing else's.
     */
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

    /**
     * Reconstruct the carb-appearance + insulin-action channels ONCE for the cycle from the logged
     * events (SPEC §3.3), aligned to `series.gridStartMs`. Off-main via the source's own dispatcher.
     * A missing/failed source or a length mismatch falls back to the `normalize(0)` no-dose baseline.
     */
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

    /**
     * Reconstruct the COMMITTED prediction-zone dose tails ONCE for the cycle (SPEC §3.3). Aligned to
     * the grid boundary one step past the last context sample (`gridStartMs + n·STEP`) — so the tail
     * carried here continues seamlessly from the [contextChannels] past. Length = the model's fixed
     * pred zone (P·S). A missing/failed source or mismatch ⇒ `null`, i.e. the `normalize(0)` no-dose
     * baseline (exact pre-Phase-4c behaviour). Uses the SAME `ChannelBuilder.futureOverrides` engine
     * as `RollingForecaster`, so the directional response is identical.
     */
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

    /** The fixed prediction-zone step count for a descriptor: P·S (mirrors RollingForecaster). */
    private fun predSteps(desc: ModelDescriptor): Int =
        (desc.predictionHorizonHours * STEPS_PER_HOUR / desc.patchSize) * desc.patchSize

    /**
     * Build the graph input, conditioning feats 1-3 (carb / insulin / exercise) on the past
     * reconstructed channels [ch] AND the prediction zone on the COMMITTED future tails [future]
     * (SPEC §3.3) — so the main-view forecast reflects logged meals/doses across the now-boundary.
     * `future == null` seeds the pred-zone dose slots to `normalize(0)` (no committed dose / unwired).
     * Channel order is fixed carb-insulin-exercise at every `native.buildGraphInput` slot
     * (context AND announced), identical to `RollingForecaster` — no swap.
     */
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

    /** The user's BG smoothing window, snapped to an offered detent; a missing/throwing provider
     *  yields the default. Read fresh per cycle so a Settings edit takes on the next tick. */
    private suspend fun smoothingWindow(): Int =
        InferenceControllerDefaults.nearestSmoothingStop(
            runCatching { smoothingWindowProvider() }.getOrNull() ?: InferenceControllerDefaults.SAVGOL_WINDOW,
        )

    /**
     * The running set as the panels see it: the loaded exported models, then the classical baseline.
     *
     * The baseline is listed whether or not it has been fitted. It is not discovered from disk — it
     * is a model this app always has — so hiding it until a fit would leave its own Fit action with
     * nowhere to live, and the first fit would need an entry point outside the model it belongs to.
     * [InferenceState.baselineModel] is what says which of the two states it is in.
     */
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

    /**
     * The on-disk `.pte` filenames of the currently-loaded running set — the identity the model-sync
     * coordinator keys on to decide whether a server update would overwrite a LIVE (dosing-relevant)
     * artifact (⇒ stage for manual apply) versus a new/stub one (⇒ apply in place). NOT the descriptor
     * `model_id`, which can diverge from the artifact filename for an adb-pushed model. Empty when no
     * model is loaded. A stub stand-in's bundle still names its intended `.pte`, but that file is absent,
     * so the coordinator's own live-file check routes it to apply-in-place.
     */
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

    /** Reload the running set when it is empty. Its sole caller [runCycle] already holds [cycleMutex],
     *  so it uses the lock-free [refreshModelsLocked] (calling the locking [refreshModels] here would
     *  self-deadlock the non-reentrant mutex). */
    private suspend fun refreshOrNote() {
        if (loaded.isEmpty()) refreshModelsLocked()
    }

    /** Current process resident-set size (KB) from /proc/self/statm; 0 if unreadable (best-effort). */
    private fun residentKb(): Long = runCatching {
        val pages = java.io.File("/proc/self/statm").readText().trim().split(" ")[1].toLong()
        pages * 4L // 4 KB page (K90 runtime page size = 4 KB — see target-device.md)
    }.getOrDefault(0L)

    companion object {
        /** Default running-set cap when [maxRunningProvider] is unset/unreadable (Settings default). */
        const val DEFAULT_MAX_RUNNING = 5
        const val TAG = "CycleRunner"
        const val GRID_MS = 300_000L
        /** Fixed anchor for the deterministic probe/comparison input — reproducible across builds. */
        const val PROBE_ANCHOR_MS = 1_700_000_000_000L
        /** §3.6-E agreement tolerance on the decoded mg/dL median (the hypo-relevant band tol). */
        const val AGREEMENT_TOL_MGDL = 3.0
        /**
         * Stable switcher display order. Only backends that can actually load on this build are
         * listed: the fp32 XNNPACK CPU authority and the fp16 Vulkan GPU. `EXECUTORCH_VULKAN_FP32`
         * is deliberately absent — Vulkan ships fp16, so no fp32 `.vulkan.pte` is deployed and the
         * entry could only ever refuse with "artifact missing". The NPU ids stay in [BackendId] (and
         * in the Hardware catalog's reasons) but are not offered as choices.
         */
        val BACKEND_ORDER = listOf(
            BackendId.EXECUTORCH_XNNPACK_FP32,
            BackendId.EXECUTORCH_VULKAN_FP16,
        )

        fun maxAbsDelta(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size) return Double.POSITIVE_INFINITY
            var m = 0.0
            for (i in a.indices) m = maxOf(m, kotlin.math.abs(a[i].toDouble() - b[i].toDouble()))
            return m
        }

        fun maxAbsDeltaD(a: List<Double>, b: List<Double>): Double {
            if (a.size != b.size) return Double.POSITIVE_INFINITY
            var m = 0.0
            for (i in a.indices) m = maxOf(m, kotlin.math.abs(a[i] - b[i]))
            return m
        }

        fun median(xs: List<Double>): Double {
            if (xs.isEmpty()) return 0.0
            val s = xs.sorted()
            val mid = s.size / 2
            return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
        }
        const val MS_PER_HOUR = 3_600_000.0
        /** 5-min grid ⇒ 12 steps/hour (mirrors calc HorizonPolicy.STEPS_PER_HOUR). */
        const val STEPS_PER_HOUR = 12
        /** inference-runtime.md default warmup window (h); the setting floors at MIN_CONTEXT = 8 h. */
        const val DEFAULT_WARMUP_HOURS = 24.0
        /** Warmup completes at this fraction of the required window covered by MEASURED slots, tolerating
         *  the gaps a passive advertisement CGM inevitably leaves (a fully gapless window is unrealistic
         *  and made completion flap). Paired with the monotonic [warmupSatisfiedUpTo] latch. */
        const val WARMUP_COMPLETION_FRACTION = 0.85
        const val N_QUANTILES = 7
        /** No rolling widening: the cycle forecast is one ≤2 h window with no seam to carry across.
         *  §9's per-level carry belongs to `:calc`'s RollingForecaster, which rolls past the window. */
        val CARRY_SPREAD = emptyList<Double>()
        const val LATENCY_WINDOW = 60

        /** Context window for a cycle with NO descriptor to size it from — a device whose only
         *  running model is the fitted classical baseline. 8 h and 24 h of steps: the baseline
         *  reads `nLags` of them and the rest is the WARMUP gate's floor. Deliberately NOT the
         *  neural models' own bounds, which now run to days — a device with no neural model must
         *  not wait a week to satisfy a gate that is not about one. */
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
