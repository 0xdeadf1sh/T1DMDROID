package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendAvailability
import com.t1dm.core.model.BackendComparison
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.displayName
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
 * The Phase-2 inference orchestrator (SPEC.private.md §3.2, Phase 2 deliverable 4). It owns the
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
    /** Manual running-set cap (SPEC.private.md §2.3). Default 1 selected model this phase. */
    private val maxRunning: Int = 1,
    /** Reconstructed carb/insulin context channels (SPEC §3.3); null ⇒ `normalize(0)` baseline. */
    private val contextChannels: ContextChannelSource? = null,
    /** Committed dose tails carried into the PREDICTION ZONE (SPEC §3.3); null ⇒ `normalize(0)`
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
    /** Every DISCOVERED backend variant of a model id (xnnpack / vulkan / …), before load. */
    private val variants = LinkedHashMap<String, LinkedHashMap<BackendId, ModelBundle>>()
    /** Every SUCCESSFULLY-LOADED variant, so the active cycle + the agreement probe reuse handles. */
    private val loadedVariants = LinkedHashMap<String, LinkedHashMap<BackendId, Entry>>()
    /** The evidence-based forecast-backend switcher catalog (issue 20 STEP 4). */
    private var catalog: List<BackendAvailability> = emptyList()
    /** The user's requested forecast backend (kv-persisted in :app); null ⇒ auto (authority). */
    private var forecastBackendPref: BackendId? = null
    /** Cached fp32-agreement verdict per NON-authority backend (null until a comparison runs). */
    private val agreementByBackend = HashMap<BackendId, Boolean>()
    /** The last on-device GPU-vs-CPU comparison (timings + numerics + agreement). */
    private var lastComparison: BackendComparison? = null
    /** Process RSS growth (KB) attributed to the non-authority backend's load (best-effort). */
    private var vulkanLoadRssKb: Long? = null
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

        // The single running model this phase (maxRunning=1): the first discovered id.
        val primaryId = variants.keys.firstOrNull()
        catalog = buildCatalog(primaryId)

        if (primaryId != null) {
            val active = chooseActive(primaryId)
            loaded[primaryId] = active
            selectedId = primaryId
        } else {
            selectedId = null
        }

        val note = when {
            bundles.isEmpty() ->
                "no model on device — adb push a descriptor.json (+ .pte) to the app models dir"
            loaded.values.none { it.real } ->
                "running on the StubBackend (no working .pte) — real forecast path blocked"
            loaded[selectedId]?.effectiveBackend?.let { it != BackendId.EXECUTORCH_XNNPACK_FP32 } == true ->
                "forecast running on ${loaded[selectedId]?.effectiveBackend?.displayName()} " +
                    "(non-authoritative; dosing needs the agreement probe)"
            else -> null
        }
        _state.value = _state.value.copy(
            running = runningModels(),
            metas = metasSnapshot(),
            telemetry = telemetrySnapshot(),
            backendCatalog = catalog,
            requestedBackend = forecastBackendPref,
            backendComparison = lastComparison,
            note = note,
        )
        Timber.tag(TAG).i(
            "refreshModels variants=%s active=%s pref=%s catalog=%s",
            variants.mapValues { it.value.keys }, loaded[selectedId]?.effectiveBackend, forecastBackendPref,
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
        val chosen = forecastBackendPref?.let { vmap[it] }
            ?: vmap[BackendId.EXECUTORCH_XNNPACK_FP32]
            ?: vmap.values.firstOrNull()
        if (chosen != null) return chosen
        val bundle = variants[id]?.values?.firstOrNull() ?: error("no bundle for $id")
        val handle = stub.load(bundle.descriptor, bundle.pte)
        return Entry(bundle, stub, handle, BackendId.STUB, Precision.FP32, real = false)
    }

    /**
     * Set the FORECAST-CYCLE backend (issue 20 STEP 4). Governs the forecast cycle ONLY; the dosing
     * path stays fail-closed on a non-authoritative backend until the agreement probe passes (§3.6-E).
     * Re-runs discovery so the active handle + catalog + "executing on" line reflect the choice; if the
     * requested backend cannot load, the controller falls back to the authority and the requested-vs-
     * executing divergence is visible to the user. Returns the backend ACTUALLY active afterwards.
     */
    suspend fun setForecastBackend(pref: BackendId?): BackendId? {
        forecastBackendPref = pref
        refreshModels()
        return loaded[selectedId]?.effectiveBackend
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
     *  null when nothing is loaded/selected. This follows [forecastBackendPref]; it drives the
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
     * Deliberately ignores [forecastBackendPref]: dose advice must ALWAYS be computed on the fp32 CPU
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
    suspend fun runSelected(input: GraphInput): GraphOutput = cycleMutex.withLock {
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
    suspend fun runSelectedAuthority(input: GraphInput): GraphOutput = cycleMutex.withLock {
        val id = selectedId ?: error("no selected model")
        val e = loadedVariants[id]?.get(BackendId.EXECUTORCH_XNNPACK_FP32)
            ?: error("fp32 XNNPACK authority variant not loaded for $id")
        withContext(dispatchers.inference) { e.backend.run(e.handle, input) }
    }

    /** Manually pick the selected (fp32-authoritative) model; a no-op if [id] is not loaded. */
    fun selectModel(id: String) {
        if (id in loaded.keys) {
            selectedId = id
            _state.value = _state.value.copy(running = runningModels())
        }
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
        val id = selectedId ?: return@withLock null
        val vmap = loadedVariants[id] ?: return@withLock null
        val authority = vmap[BackendId.EXECUTORCH_XNNPACK_FP32] ?: run {
            _state.value = _state.value.copy(note = "agreement probe needs the fp32 XNNPACK authority loaded")
            return@withLock null
        }
        val other = vmap.entries.firstOrNull { it.key != BackendId.EXECUTORCH_XNNPACK_FP32 }?.value ?: run {
            _state.value = _state.value.copy(note = "no non-authoritative backend loaded to compare against CPU")
            return@withLock null
        }
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
        val authOut = withContext(dispatchers.inference) { authority.backend.run(authority.handle, probeInput(desc)) }
        val otherOut = withContext(dispatchers.inference) { other.backend.run(other.handle, probeInput(desc)) }
        val headDelta = maxAbsDelta(authOut.headRaw, otherOut.headRaw)
        val lastBg = withContext(dispatchers.default) {
            // Recover the anchor mg/dL for the decode (same fixed series the probe used).
            SyntheticContext.plausible24h(desc.maxContextPatches * GraphIo.PATCH_DIM / 3, PROBE_ANCHOR_MS).mgdl.last()
        }
        val fAuth = withContext(dispatchers.default) {
            native.assembleDecode(desc, authOut.headRaw.map { it.toDouble() }, lastBg, CARRY_SPREAD)
        }
        val fOther = withContext(dispatchers.default) {
            native.assembleDecode(desc, otherOut.headRaw.map { it.toDouble() }, lastBg, CARRY_SPREAD)
        }
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

    /** Build the FIXED, time-independent, dose-free probe input (deterministic across runs/builds). */
    private suspend fun probeInput(desc: ModelDescriptor): GraphInput {
        val steps = desc.maxContextPatches * GraphIo.PATCH_DIM / 3
        val series = SyntheticContext.plausible24h(steps, anchorTsMs = PROBE_ANCHOR_MS)
        val n = series.mgdl.size
        val ctx = buildContext(desc, series.mgdl, DoseChannels(DoubleArray(n), DoubleArray(n)), null)
        return GraphIo.graphInput(ctx, desc.negFill)
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
            // The BG forecast stays (correctly) suppressed — §3.6 gates untouched, [predictions] empty.
            // BUT the circadian-phase belief is NOT a glucose forecast and NOT a dosing signal, and it
            // degrades gracefully with little context, so we still publish it (issues 7 & 9) as a
            // low-context belief. It survives warmup via [circadianTime], never re-entering the forecast
            // path. Fail-OPEN to null when there is not yet enough raw history to run a single forward.
            val warmupBelief = runCatching { circadianDuringWarmup() }.getOrNull()
            val selEntry = loaded[selectedId]
            _state.value = _state.value.copy(
                predictions = emptyList(), // clear the overlay while warming
                lastCause = InferenceCause.COLLECTING_CONTEXT,
                warmup = com.t1dm.core.model.WarmupProgress(measuredHours, requiredHours),
                circadianTime = warmupBelief?.first,
                circadianAnchorMs = warmupBelief?.second,
                circadianLowContext = warmupBelief != null,
                realBackendAvailable = selEntry?.real ?: false,
                selectedHasTimeSection = selEntry?.bundle?.descriptor?.time != null,
                note = "collecting context — %.1f / %.0f h of measured data".format(measuredHours, requiredHours),
            )
            Timber.tag(TAG).i(
                "warmup: %.1f/%.0f h measured — forecasts suppressed; circadian=%s",
                measuredHours, requiredHours,
                warmupBelief?.let { "%.2fh R=%.3f".format(it.first.predictedHour, it.first.resultantR) } ?: "n/a",
            )
            return
        }

        val series = history.recentBgSeries(maxSteps, minSteps)
        if (series == null) {
            val selEntry = loaded[selectedId]
            _state.value = _state.value.copy(
                predictions = emptyList(),
                lastCause = InferenceCause.COLLECTING_CONTEXT,
                circadianTime = null,
                circadianAnchorMs = null,
                circadianLowContext = false,
                realBackendAvailable = selEntry?.real ?: false,
                selectedHasTimeSection = selEntry?.bundle?.descriptor?.time != null,
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
        // action (feat 2) channels reconstructed from the logged meals/doses/basal (SPEC §3.3),
        // aligned to the BG grid. Model-independent (the per-desc normalization happens in
        // build_context); a null/failed source falls back to the `normalize(0)` no-dose baseline.
        val doseChannels = buildDoseChannels(series)

        // ONE shared PREDICTION-ZONE build: the COMMITTED dose tails (already-logged meals/doses still
        // absorbing past the now-boundary) reconstructed via the SAME curve engine the calculator's
        // baseline roll uses (SPEC §3.3). Carried into build_context's announced-future slots so a
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
        val selPred = preds.firstOrNull { it.selected }
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
            // A full cycle republishes the circadian belief from the selected prediction (full context,
            // not low-context) so the clock/dial track the live forecast the moment warmup clears.
            circadianTime = selPred?.predictedTime,
            circadianAnchorMs = selPred?.predictedTime?.let { selPred.anchorTsMs },
            circadianLowContext = false,
            selectedHasTimeSection = loaded[selectedId]?.bundle?.descriptor?.time != null,
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

    /**
     * Run ONE forward on the selected model DURING WARMUP purely to obtain the circadian-phase belief
     * (issues 7 & 9) — the BG forecast stays suppressed and is never derived here. Returns the decoded
     * belief + the series anchor it was formed at, or null when it cannot run at all: no real backend
     * (the stub carries no probe), a descriptor without a time section, too little raw history for a
     * single forward, or any decode hiccup. Serialised on [cycleMutex] like every other forward so it
     * never overlaps a calculator `runSelected` on the one command queue. Never throws (the caller
     * wraps it too); a time-probe hiccup must not perturb the warmup gate.
     */
    private suspend fun circadianDuringWarmup(): Pair<PredictedTime, Long>? {
        val id = selectedId ?: return null
        val entry = loaded[id] ?: return null
        if (!entry.real) return null                       // StubBackend has no circadian probe
        val desc = entry.bundle.descriptor
        if (desc.time == null) return null                 // model has no hour-of-day head
        val minSteps = desc.minContextPatches * GraphIo.PATCH_DIM / 3
        val maxSteps = desc.maxContextPatches * GraphIo.PATCH_DIM / 3
        val series = runCatching { history.recentBgSeries(maxSteps, minSteps) }.getOrNull() ?: return null
        return cycleMutex.withLock {
            runCatching {
                val doseChannels = buildDoseChannels(series)
                val futureChannels = buildFutureChannels(series, desc)
                val ctx = buildContext(desc, series.mgdl, doseChannels, futureChannels)
                val input = GraphIo.graphInput(ctx, desc.negFill)
                val out = withContext(dispatchers.inference) { entry.backend.run(entry.handle, input) }
                decodeTimeSafely(desc, out.timeLogits)?.let { it to series.anchorTsMs }
            }.getOrElse {
                Timber.tag(TAG).w(it, "warmup circadian forward failed; predicted hour omitted")
                null
            }
        }
    }

    /** The two shared context channels for a cycle, index-aligned to the BG grid. */
    private class DoseChannels(val carb: DoubleArray, val insulin: DoubleArray)

    /**
     * Reconstruct the carb-appearance + insulin-action channels ONCE for the cycle from the logged
     * events (SPEC §3.3), aligned to `series.gridStartMs`. Off-main via the source's own dispatcher.
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
     * Reconstruct the COMMITTED prediction-zone dose tails ONCE for the cycle (SPEC §3.3). Aligned to
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
     * (SPEC §3.3) — so the main-view forecast reflects logged meals/doses across the now-boundary.
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

    /** Current process resident-set size (KB) from /proc/self/statm; 0 if unreadable (best-effort). */
    private fun residentKb(): Long = runCatching {
        val pages = java.io.File("/proc/self/statm").readText().trim().split(" ")[1].toLong()
        pages * 4L // 4 KB page (K90 runtime page size = 4 KB — see target-device.md)
    }.getOrDefault(0L)

    private companion object {
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
