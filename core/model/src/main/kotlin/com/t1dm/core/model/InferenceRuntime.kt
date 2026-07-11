package com.t1dm.core.model

/**
 * The UI- and persistence-facing inference-runtime domain types (Phase 2, SPEC.private.md §3.2 /
 * §2.4). They live in `:core:model` — not `:inference` — so the graph overlay (`:ui:graph`) and the
 * Hardware / Models panels can render a forecast and per-model telemetry without depending on the
 * ExecuTorch backend module. The `InferenceBackend` seam itself (GraphInput/GraphOutput/backends)
 * stays in `:inference`; only the *results* cross into shared model space here.
 */

/** Numeric precision a backend runs at. fp32 XNNPACK is the Phase-2 authority; fp16 is deferred. */
enum class Precision { FP32, FP16 }

/**
 * Which runtime executed a model this cycle (SPEC.private.md §3.2). `STUB` is the fixed-output
 * fallback used when no real `.pte` is present, so the whole pipeline still builds and runs.
 *
 * [EXECUTORCH_XNNPACK_FP32] is the authoritative CPU path (the only one that executes today).
 * [LITERT_NPU] is the LiteRT-unified MediaTek-NeuroPilot NPU path (issue 1): its `.tflite` artifact
 * converts from the SAME modified forward via litert-torch and matches the fp32 authority to
 * `max|Δ| ≈ 1.4e-6` on host, but on-device execution is blocked for a sideload build (the NeuroPilot
 * NPU runtime ships via Google Play PODAI / Play Feature Delivery). [EXECUTORCH_NEURON_FP16] and the
 * legacy [LITERT_NEURON_FP16] TFLite-delegate id remain enumerated but unavailable — see each
 * backend's plain-language reason ([com.t1dm.inference.backend]). The distinction is surfaced
 * verbatim on the Hardware / Models panels — no "stub" ambiguity.
 */
enum class BackendId {
    EXECUTORCH_XNNPACK_FP32,
    EXECUTORCH_NEURON_FP16,
    LITERT_NEURON_FP16,
    LITERT_NPU,
    EXECUTORCH_VULKAN_FP32,
    EXECUTORCH_VULKAN_FP16,
    STUB,
}

/**
 * Human-readable backend label for the Hardware / Models panels (issue 1 — no "stub" ambiguity).
 * Names the engine + numeric precision + the compute unit it targets; the *live* execution truth is
 * the panel's "Executing on:" line (from the selected [RunningModel]), while this names the routing
 * target itself.
 */
fun BackendId.displayName(): String = when (this) {
    BackendId.EXECUTORCH_XNNPACK_FP32 -> "XNNPACK CPU · fp32"
    BackendId.EXECUTORCH_NEURON_FP16 -> "Neuron NPU · fp16"
    BackendId.LITERT_NEURON_FP16 -> "LiteRT Neuron NPU · fp16"
    BackendId.LITERT_NPU -> "LiteRT NPU · fp32"
    BackendId.EXECUTORCH_VULKAN_FP32 -> "Vulkan GPU · fp32"
    BackendId.EXECUTORCH_VULKAN_FP16 -> "Vulkan GPU · fp16"
    BackendId.STUB -> "Stub · no .pte"
}

/**
 * One entry in the forecast-backend switcher catalog (issue 20 STEP 4). Enumerates a backend the
 * controller can route the FORECAST CYCLE to, with an evidence-based availability verdict: [available]
 * is true only when a real `.pte` for this engine is on device AND its native `load` succeeded on the
 * runtime classpath; otherwise [reason] states plainly why not (no artifact, no delegate in the AAR,
 * a partner-gated runtime, a load failure). [precision] is the numeric precision the backend runs at.
 * [authoritative] marks the fp32 XNNPACK CPU reference — the only backend trusted for dosing without
 * an agreement probe (§3.6-E).
 */
data class BackendAvailability(
    val backend: BackendId,
    val precision: Precision,
    val available: Boolean,
    val authoritative: Boolean,
    val reason: String?,
)

/**
 * The honest on-device comparison of a NON-authoritative backend against the fp32 XNNPACK authority
 * (issue 20 STEP 3 + the §3.6-E agreement gate). Both backends run the SAME fixed deterministic input;
 * timings are medians over [runs] warm forwards plus the first (cold) forward, numerics are the worst-
 * case absolute deltas of `head_raw` (risk space) and the decoded mg/dL median fan. [agreementOk] is
 * the gate verdict: the decoded-mg/dL worst delta is within [toleranceMgdl]. A backend only feeds the
 * dosing path once [agreementOk] is true (BackendInfo.trustworthy) — the forecast may still render.
 */
data class BackendComparison(
    val backend: BackendId,
    val authority: BackendId,
    val runs: Int,
    val warmMedianMsBackend: Double,
    val warmMedianMsAuthority: Double,
    val coldMsBackend: Double,
    val coldMsAuthority: Double,
    val maxAbsHeadRawDelta: Double,
    val maxAbsDecodedMgdlDelta: Double,
    val toleranceMgdl: Double,
    val agreementOk: Boolean,
    /** Resident-set growth (KB) attributable to the backend's load, best-effort; null if unmeasured.
     *  Mali heaps are UNIFIED with system RAM — this is process RSS, not a discrete VRAM figure. */
    val loadRssGrowthKb: Long? = null,
)

/** One model in the running set (≤5; SPEC.private.md §2.3), tagged by the descriptor's `model_id`. */
data class RunningModel(
    val modelId: String,
    val backend: BackendId,
    val precision: Precision,
    val selected: Boolean,
)

/**
 * Static, size-reasoning metadata for a running model (Phase 7C — Models panel meta rows). The
 * arch dims + [paramCount] come from the descriptor (`geometry` + the display-only `model_card`
 * block the exporter stamps); [diskBytes] is the `stat`'d artifact size (null when the `.pte` is
 * absent and the StubBackend stands in). [reference] carries the model's own held-out validation
 * metrics (train.py) as a REFERENCE — distinct from the on-device realized [AccuracyReport].
 */
data class ModelMeta(
    val modelId: String,
    val paramCount: Long? = null,
    val diskBytes: Long? = null,
    val dModel: Int? = null,
    val nLayers: Int? = null,
    val nHeads: Int? = null,
    val patchDim: Int? = null,
    val minContextPatches: Int? = null,
    val maxContextPatches: Int? = null,
    val predictionHorizonHours: Int? = null,
    val archVersion: String? = null,
    val executorchVersion: String? = null,
    val valStep: Int? = null,
    val reference: ReferenceMetrics? = null,
)

/** The exported model's held-out validation reference metrics (from the descriptor `model_card`). */
data class ReferenceMetrics(
    val horizonsMin: List<Int>,
    val rmseMgdl: List<Double?>,
    val mardPct: List<Double?>,
    val clarkeAPct: List<Double?>,
    val coverage90: List<Double?>,
    val clarkeAbPct: Double?,
    val todMaeH: Double?,
    val todMaeHiconfH: Double?,
)

/**
 * CUMULATIVE per-model inference telemetry (Phase 7C — Models drill-down). Unlike [ModelLatency]
 * (a rolling p50/p95 window), this is a durable running total the CycleRunner increments every
 * cycle and persists, surviving process restarts: how many forecasts a model has produced, the
 * TOTAL wall-time spent in its backend forward, and the derived average.
 */
data class ModelTelemetry(
    val modelId: String,
    val predictions: Long,
    val totalInferenceMs: Double,
) {
    val avgInferenceMs: Double get() = if (predictions > 0) totalInferenceMs / predictions else 0.0
}

/** Rolling per-model backend latency (ms) for the Hardware panel (Phase 2 §8). */
data class ModelLatency(
    val modelId: String,
    val runs: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val lastMs: Double,
)

/**
 * A decoded forecast for one model at one 5-min cycle (Phase 2 deliverable 4).
 * [medianBg] is the `P·S` mg/dL headline line; [bandsMgdl] the `P·S·[nQuantiles]` ascending-τ fan
 * (step-major `i = p·S + s`, then the τ column), both already `f_inv`-decoded in the Rust core.
 * [status] is the §3.6-B degeneracy verdict; a non-`OK` prediction is ineligible to drive a rail
 * or a predictive alert. [stale] marks a forecast whose anchor is older than the freshness gate
 * (§3.6-D) — surfaced now, enforced by the calculators in Phase 4.
 */
data class ModelPrediction(
    val modelId: String,
    val cycleTsMs: Long,
    val anchorTsMs: Long,
    val stepMs: Long,
    val medianBg: List<Double>,
    val bandsMgdl: List<Double>,
    val nQuantiles: Int,
    val lastBg: Double,
    val status: ForecastStatus,
    val backend: BackendId,
    val precision: Precision,
    val selected: Boolean,
    val stale: Boolean,
    val latencyMs: Double?,
    /**
     * The model's decoded circadian-phase belief this cycle, or null when the descriptor lacks a
     * time section (graph cut at `head_raw`), the backend returned no second output, or the decode
     * failed (fail-open — never blocks the BG forecast). Reachable for the BG panel via
     * [InferenceState.selectedPrediction]`?.predictedTime`.
     */
    val predictedTime: PredictedTime? = null,
) {
    /** `true` iff this forecast is fit to render/drive (finite, ordered, non-collapsed, fresh). */
    val eligible: Boolean get() = status == ForecastStatus.OK && !stale

    /** The step count `P·S` of the horizon (derived from [medianBg]). */
    val horizonSteps: Int get() = medianBg.size
}

/**
 * The decoded hour-of-day belief of a model's co-trained TIME PROBE for one cycle (mirrors the
 * Rust `PredictedTime`; Phase 7A). This is a CIRCADIAN-PHASE belief — the model's
 * estimate of **what hour-of-day it currently is**, NOT a per-forecast-step timestamp. A
 * predicted-time axis for the forecast is [predictedHour] plus each step's offset.
 *
 * [probs] is the [nBins]-long softmax of the ORIGIN prediction patch's logits; [predictedHour] the
 * mean-resultant hour in `[0,24)`; [resultantR] the resultant length in `[0,1]` = the circular
 * concentration (confidence — near 0 means the belief is diffuse / effectively undefined).
 * Non-null only when the selected model's descriptor carries a time section AND the decode
 * succeeded; the BG panel must treat null as "predicted time unavailable in this model build".
 */
data class PredictedTime(
    val probs: List<Double>,
    val predictedHour: Double,
    val resultantR: Double,
    val nBins: Int,
    val binHours: Double,
)

/** What triggered a cycle, surfaced for the Hardware/Models panels and logs. */
enum class InferenceCause { GRID_TICK, MANUAL, SYNTHETIC, COLLECTING_CONTEXT, OVER_TEMPERATURE }

/**
 * The device-temperature reading the inference gate reasons over (D1: the BATTERY-sensor °C — a true
 * die temp is unreadable on this device). [thresholdC] is the pause line; [warnMarginC] is how far
 * below it the TEMP chip turns amber; [resumeMarginC] is the hysteresis band the controller keeps
 * inference paused across until the reading falls below `thresholdC - resumeMarginC` (avoids flapping
 * on/off at the boundary). All fields are Celsius.
 */
data class ThermalStatus(
    val currentC: Double,
    val thresholdC: Double,
    val warnMarginC: Double,
    val resumeMarginC: Double,
)

/** TEMP-chip band (D1): NORMAL below the warn margin, WARN within it, CRITICAL at/above threshold. */
enum class ThermalLevel { NORMAL, WARN, CRITICAL }

/** Compares in Celsius; thresholdC null ⇒ NORMAL (gate disabled ⇒ chip stays its normal color). */
fun thermalLevel(celsius: Double, thresholdC: Double?, warnMarginC: Double): ThermalLevel = when {
    thresholdC == null -> ThermalLevel.NORMAL
    celsius >= thresholdC -> ThermalLevel.CRITICAL
    celsius >= thresholdC - warnMarginC -> ThermalLevel.WARN
    else -> ThermalLevel.NORMAL
}

/**
 * Warmup-gate progress (inference-runtime.md — the WARMUP gate). While fewer than [requiredHours] of
 * MEASURED (non-interpolated) BG have accrued in the trailing window, the cycle suppresses every
 * prediction and the dashboard shows "collecting context — [measuredHours] / [requiredHours] h".
 * [requiredHours] is the user's `warmupHours` setting, floored at the model's MIN_CONTEXT (8 h).
 * This is DISTINCT from the per-cycle freshness gate (§3.6-D): freshness marks an aged anchor STALE;
 * warmup withholds forecasts until enough real history exists to condition on at all.
 */
data class WarmupProgress(val measuredHours: Double, val requiredHours: Double) {
    val fraction: Double get() = if (requiredHours <= 0.0) 1.0 else (measuredHours / requiredHours).coerceIn(0.0, 1.0)
}

/**
 * The immutable snapshot the UI observes as a `StateFlow` (Phase 2). Carries the
 * running set, this cycle's per-model predictions (selected first), rolling latencies, and a
 * plain-language [note] for the "collecting context" / "forecast unavailable" states — every
 * refusal states WHY (progress.md Q10).
 */
data class InferenceState(
    val running: List<RunningModel> = emptyList(),
    val predictions: List<ModelPrediction> = emptyList(),
    val latencies: List<ModelLatency> = emptyList(),
    val metas: List<ModelMeta> = emptyList(),
    val telemetry: List<ModelTelemetry> = emptyList(),
    val lastCycleTsMs: Long? = null,
    val lastCause: InferenceCause? = null,
    val lastCycleDurationMs: Long? = null,
    /** `false` when the selected model is served by the [BackendId.STUB] fallback (no real `.pte`). */
    val realBackendAvailable: Boolean = true,
    /** Non-null while the WARMUP gate is withholding forecasts (predictions cleared); null once met. */
    val warmup: WarmupProgress? = null,
    /**
     * The selected model's circadian-phase belief, published INDEPENDENTLY of the BG forecast so it
     * SURVIVES the warmup gate (issues 7 & 9). During a full cycle this equals the selected
     * prediction's [PredictedTime]; during warmup it is a low-context belief formed from whatever
     * history exists while [predictions] stays (correctly) empty. It is a phase belief, NOT a glucose
     * forecast and NOT a dosing signal — no §3.6 gate depends on it.
     */
    val circadianTime: PredictedTime? = null,
    /** Anchor (epoch-ms) the [circadianTime] belief was formed at — the clock offset is measured from it. */
    val circadianAnchorMs: Long? = null,
    /** True when [circadianTime] was formed during warmup on limited history (⇒ show a low-confidence caveat). */
    val circadianLowContext: Boolean = false,
    /** Whether the SELECTED model's descriptor declares a time section at all (distinguishes the
     *  "no time section" empty state from a "decode failed" one). Defaults true until a cycle sets it. */
    val selectedHasTimeSection: Boolean = true,
    /** The forecast-backend switcher catalog (issue 20 STEP 4): every routable backend with an
     *  evidence-based availability verdict. Drives the Settings selector + the Hardware panel rows. */
    val backendCatalog: List<BackendAvailability> = emptyList(),
    /** The backend the user REQUESTED for the forecast cycle of the SELECTED model (kv-persisted).
     *  null ⇒ auto (authority). The backend ACTUALLY executing is [selectedPrediction]`.backend` /
     *  the selected [RunningModel]. See [requestedBackendByModel] for the full per-model map. */
    val requestedBackend: BackendId? = null,
    /** The requested forecast backend PER MODEL id (kv-persisted; issue 20 STEP 4 is now per-model).
     *  An id absent here (or mapped to null) means auto = the fp32 XNNPACK authority. Steers only the
     *  DISPLAY forecast — never the dosing/authority path (§3.6-E). */
    val requestedBackendByModel: Map<String, BackendId?> = emptyMap(),
    /** The last on-device GPU-vs-CPU comparison (timings + numerics + agreement verdict), or null
     *  until one has been run (the switcher/Hardware panel trigger it when a GPU backend is active). */
    val backendComparison: BackendComparison? = null,
    val note: String? = null,
) {
    val selectedPrediction: ModelPrediction? get() = predictions.firstOrNull { it.selected }

    /** The selected model's circadian-phase belief this cycle, or null when unavailable (BG panel).
     *  Prefers the warmup-surviving [circadianTime]; falls back to the in-cycle prediction's copy. */
    val selectedPredictedTime: PredictedTime? get() = circadianTime ?: selectedPrediction?.predictedTime

    fun latencyOf(modelId: String): ModelLatency? = latencies.firstOrNull { it.modelId == modelId }

    fun metaOf(modelId: String): ModelMeta? = metas.firstOrNull { it.modelId == modelId }

    fun telemetryOf(modelId: String): ModelTelemetry? = telemetry.firstOrNull { it.modelId == modelId }

    fun runningOf(modelId: String): RunningModel? = running.firstOrNull { it.modelId == modelId }

    /** The requested forecast backend for [modelId] (null ⇒ auto = the fp32 XNNPACK authority). */
    fun requestedBackendOf(modelId: String): BackendId? = requestedBackendByModel[modelId]
}
