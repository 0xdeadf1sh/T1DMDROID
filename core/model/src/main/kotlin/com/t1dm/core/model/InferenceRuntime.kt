package com.t1dm.core.model

/**
 * The UI- and persistence-facing inference-runtime domain types (Phase 2, PLAN.private.md §3.2 /
 * §2.4). They live in `:core:model` — not `:inference` — so the graph overlay (`:ui:graph`) and the
 * Hardware / Models panels can render a forecast and per-model telemetry without depending on the
 * ExecuTorch backend module. The `InferenceBackend` seam itself (GraphInput/GraphOutput/backends)
 * stays in `:inference`; only the *results* cross into shared model space here.
 */

/** Numeric precision a backend runs at. fp32 XNNPACK is the Phase-2 authority; fp16 is deferred. */
enum class Precision { FP32, FP16 }

/**
 * Which runtime executed a model this cycle (PLAN.private.md §3.2). `STUB` is the fixed-output
 * fallback used when no real `.pte` is present, so the whole pipeline still builds and runs; the
 * Neuron / LiteRT ids are declared but their backends are documented stubs this phase.
 */
enum class BackendId {
    EXECUTORCH_XNNPACK_FP32,
    EXECUTORCH_NEURON_FP16,
    LITERT_NEURON_FP16,
    STUB,
}

/** One model in the running set (≤5; PLAN.private.md §2.3), tagged by the descriptor's `model_id`. */
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

/** Rolling per-model backend latency (ms) for the Hardware panel (PLAN.private.md Phase 2 §8). */
data class ModelLatency(
    val modelId: String,
    val runs: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val lastMs: Double,
)

/**
 * A decoded forecast for one model at one 5-min cycle (PLAN.private.md Phase 2 deliverable 4).
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
 * Rust `PredictedTime`; PLAN.private.md Phase 7A). This is a CIRCADIAN-PHASE belief — the model's
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
enum class InferenceCause { GRID_TICK, MANUAL, SYNTHETIC, COLLECTING_CONTEXT }

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
 * The immutable snapshot the UI observes as a `StateFlow` (PLAN.private.md Phase 2). Carries the
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
    val note: String? = null,
) {
    val selectedPrediction: ModelPrediction? get() = predictions.firstOrNull { it.selected }

    /** The selected model's circadian-phase belief this cycle, or null when unavailable (BG panel). */
    val selectedPredictedTime: PredictedTime? get() = selectedPrediction?.predictedTime

    fun latencyOf(modelId: String): ModelLatency? = latencies.firstOrNull { it.modelId == modelId }

    fun metaOf(modelId: String): ModelMeta? = metas.firstOrNull { it.modelId == modelId }

    fun telemetryOf(modelId: String): ModelTelemetry? = telemetry.firstOrNull { it.modelId == modelId }

    fun runningOf(modelId: String): RunningModel? = running.firstOrNull { it.modelId == modelId }
}
