package com.t1dm.core.model

/** [FP64] is not an ExecuTorch path at all — it is the Rust core's own `f64`, which the classical
 *  baseline's solve and forecast run in end to end. */
enum class Precision { FP64, FP32, FP16 }

/**
 * [EXECUTORCH_XNNPACK_FP32] is the one path that executes a `.pte`, and the only one a dose may be
 * scored on; [STUB] is the fixed-output fallback when no real artifact is present, and
 * [NATIVE_RIDGE_FP64] the classical baseline, which runs in the Rust core and loads no artifact.
 */
enum class BackendId {
    EXECUTORCH_XNNPACK_FP32,
    NATIVE_RIDGE_FP64,
    STUB,
}

fun BackendId.displayName(): String = when (this) {
    BackendId.EXECUTORCH_XNNPACK_FP32 -> "XNNPACK CPU · fp32"
    BackendId.NATIVE_RIDGE_FP64 -> "Ridge CPU · fp64"
    BackendId.STUB -> "Stub · no .pte"
}

/** The running set is ≤5 (§2.3); [modelId] is the descriptor's `model_id`. */
data class RunningModel(
    val modelId: String,
    val backend: BackendId,
    val precision: Precision,
    val selected: Boolean,
)

/**
 * [diskBytes] is the `stat`'d artifact size, null when the `.pte` is absent and the StubBackend
 * stands in. [reference] carries the model's own held-out validation metrics as a REFERENCE —
 * distinct from the on-device realized [MetricsSuite].
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

/** Parsed because the block is part of the descriptor, and rendered NOWHERE: these are another
 *  dataset's numbers, and beside the realized suite they read as a second opinion on this patient's
 *  forecasts, which is the one thing they cannot be. */
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

/** CUMULATIVE and persisted across process restarts, unlike [ModelLatency]'s rolling window. */
data class ModelTelemetry(
    val modelId: String,
    val predictions: Long,
    val totalInferenceMs: Double,
) {
    val avgInferenceMs: Double get() = if (predictions > 0) totalInferenceMs / predictions else 0.0
}

/** Rolling per-model backend latency, ms. */
data class ModelLatency(
    val modelId: String,
    val runs: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val lastMs: Double,
)

/**
 * [medianBg] is the `P·S` mg/dL headline line; [bandsMgdl] the `P·S·[nQuantiles]` ascending-τ fan,
 * step-major (`i = p·S + s`) then the τ column, both already `f_inv`-decoded in the Rust core.
 * [status] is the §3.6-B degeneracy verdict and [stale] an anchor past the freshness gate (§3.6-D);
 * a non-`OK` or stale prediction may not drive a rail or a predictive alert.
 */
data class ModelPrediction(
    val modelId: String,
    val cycleTsMs: Long,
    val anchorTsMs: Long,
    /** The CGM source whose readings conditioned this forecast. Two sensors worn at once disagree,
     *  so scoring a matured window across a swap measures that gap and calls it model error. Null
     *  is UNKNOWN and never matches, so an unstamped forecast is dropped rather than guessed at. */
    val sourceId: String? = null,
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
    /** Null when the descriptor lacks a time section (graph cut at `head_raw`), the backend
     *  returned no second output, or the decode failed — fail-open, never blocks the BG forecast. */
    val predictedTime: PredictedTime? = null,
) {
    val eligible: Boolean get() = status == ForecastStatus.OK && !stale

    val horizonSteps: Int get() = medianBg.size
}

/**
 * The model's estimate of WHAT HOUR-OF-DAY IT IS NOW, not a per-forecast-step timestamp; a
 * predicted-time axis is [predictedHour] plus each step's offset. [probs] is the [nBins]-long
 * softmax of the ORIGIN prediction patch's logits, [predictedHour] the mean-resultant hour in
 * `[0,24)`, [resultantR] the resultant length in `[0,1]` — near 0 the belief is diffuse.
 */
data class PredictedTime(
    val probs: List<Double>,
    val predictedHour: Double,
    val resultantR: Double,
    val nBins: Int,
    val binHours: Double,
)

/** [LOG_WRITE] is a cycle a logged meal or dose (or its withdrawal) fired off the curve channels it
 *  moved rather than the cadence tick; same controller path, same gates. */
enum class InferenceCause { GRID_TICK, LOG_WRITE, MANUAL, SYNTHETIC, COLLECTING_CONTEXT, OVER_TEMPERATURE }

/**
 * The BATTERY sensor's °C — a true die temp is unreadable on this device. [thresholdC] is the pause
 * line, [warnMarginC] how far below it the TEMP chip turns amber, [resumeMarginC] the hysteresis
 * inference stays paused across until the reading falls below `thresholdC - resumeMarginC`.
 */
data class ThermalStatus(
    val currentC: Double,
    val thresholdC: Double,
    val warnMarginC: Double,
    val resumeMarginC: Double,
)

/** TEMP-chip band (D1): NORMAL below the warn margin, WARN within it, CRITICAL at/above threshold. */
enum class ThermalLevel { NORMAL, WARN, CRITICAL }

/** Celsius; null [thresholdC] ⇒ NORMAL, the gate being disabled. */
fun thermalLevel(celsius: Double, thresholdC: Double?, warnMarginC: Double): ThermalLevel = when {
    thresholdC == null -> ThermalLevel.NORMAL
    celsius >= thresholdC -> ThermalLevel.CRITICAL
    celsius >= thresholdC - warnMarginC -> ThermalLevel.WARN
    else -> ThermalLevel.NORMAL
}

/**
 * Hours of MEASURED (non-interpolated) BG in the trailing window; below [requiredHours] the cycle
 * suppresses every prediction. [requiredHours] is the user's `warmupHours` setting floored at the
 * model's MIN_CONTEXT (8 h). Distinct from the freshness gate (§3.6-D), which only marks an anchor
 * stale.
 */
data class WarmupProgress(val measuredHours: Double, val requiredHours: Double) {
    val fraction: Double get() = if (requiredHours <= 0.0) 1.0 else (measuredHours / requiredHours).coerceIn(0.0, 1.0)
}

/** The immutable snapshot the UI observes as a `StateFlow`. [predictions] is selected-first, and
 *  [note] states why a refusal refused. */
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
     * Published INDEPENDENTLY of the BG forecast so it SURVIVES the warmup gate: during warmup it is
     * a low-context belief formed while [predictions] stays (correctly) empty. A phase belief, NOT a
     * glucose forecast and NOT a dosing signal — no §3.6 gate depends on it.
     */
    val circadianTime: PredictedTime? = null,
    /** Anchor (epoch-ms) the [circadianTime] belief was formed at — the clock offset is measured from it. */
    val circadianAnchorMs: Long? = null,
    /** True when [circadianTime] was formed during warmup on limited history. */
    val circadianLowContext: Boolean = false,
    /** Distinguishes the "no time section" empty state from a "decode failed" one. Defaults true
     *  until a cycle sets it. */
    val selectedHasTimeSection: Boolean = true,
    /** Null when the baseline has never been fitted; its row is listed in [running] either way. The
     *  only provenance its drill-down has — it carries no descriptor and no [ModelMeta]. */
    val baselineModel: BaselineModel? = null,
    val note: String? = null,
) {
    val selectedPrediction: ModelPrediction? get() = predictions.firstOrNull { it.selected }

    val selectedPredictedTime: PredictedTime? get() = circadianTime ?: selectedPrediction?.predictedTime

    fun latencyOf(modelId: String): ModelLatency? = latencies.firstOrNull { it.modelId == modelId }

    fun metaOf(modelId: String): ModelMeta? = metas.firstOrNull { it.modelId == modelId }

    fun telemetryOf(modelId: String): ModelTelemetry? = telemetry.firstOrNull { it.modelId == modelId }

    fun runningOf(modelId: String): RunningModel? = running.firstOrNull { it.modelId == modelId }
}
