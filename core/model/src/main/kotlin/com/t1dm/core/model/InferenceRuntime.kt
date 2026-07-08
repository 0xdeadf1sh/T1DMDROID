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
) {
    /** `true` iff this forecast is fit to render/drive (finite, ordered, non-collapsed, fresh). */
    val eligible: Boolean get() = status == ForecastStatus.OK && !stale

    /** The step count `P·S` of the horizon (derived from [medianBg]). */
    val horizonSteps: Int get() = medianBg.size
}

/** What triggered a cycle, surfaced for the Hardware/Models panels and logs. */
enum class InferenceCause { GRID_TICK, MANUAL, SYNTHETIC, COLLECTING_CONTEXT }

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
    val lastCycleTsMs: Long? = null,
    val lastCause: InferenceCause? = null,
    val lastCycleDurationMs: Long? = null,
    /** `false` when the selected model is served by the [BackendId.STUB] fallback (no real `.pte`). */
    val realBackendAvailable: Boolean = true,
    val note: String? = null,
) {
    val selectedPrediction: ModelPrediction? get() = predictions.firstOrNull { it.selected }
    fun latencyOf(modelId: String): ModelLatency? = latencies.firstOrNull { it.modelId == modelId }
}
