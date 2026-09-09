package com.t1dm.core.model

enum class Precision { FP32, FP16 }

/** XNNPACK_FP32 executes .pte (only dose-scoreable path); STUB fallback. */
enum class BackendId {
    EXECUTORCH_XNNPACK_FP32,
    STUB,

    /** Backend name this build lost; reads as unknown, not throw; never trusted for dosing. */
    UNKNOWN,
}

fun BackendId.displayName(): String = when (this) {
    BackendId.EXECUTORCH_XNNPACK_FP32 -> "XNNPACK CPU · fp32"
    BackendId.STUB -> "Stub · no .pte"
    BackendId.UNKNOWN -> "unknown backend"
}

/** The running set is ≤5 (§2.3); [modelId] is the descriptor's `model_id`. */
data class RunningModel(
    val modelId: String,
    val backend: BackendId,
    val precision: Precision,
    val selected: Boolean,
)

/** [diskBytes] null ⇒ StubBackend; [reference] is REFERENCE metrics, ≠ [MetricsSuite]. */
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

/** Parsed (descriptor field) but rendered NOWHERE: another dataset's numbers, not patient's. */
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

/** [medianBg]:P·S mg/dL; [bandsMgdl]:P·S·nQ, step-major asc-τ; non-OK/[stale] blocks rails. */
data class ModelPrediction(
    val modelId: String,
    val cycleTsMs: Long,
    val anchorTsMs: Long,
    /** CGM source conditioning this forecast; null=UNKNOWN never matches, so unstamped drops. */
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
    /** Null: no time section, no second output, or decode failed; fail-open, blocks nothing. */
    val predictedTime: PredictedTime? = null,
) {
    val eligible: Boolean get() = status == ForecastStatus.OK && !stale

    val horizonSteps: Int get() = medianBg.size
}

/** Current hour-of-day belief; [predictedHour]∈[0,24); [resultantR]∈[0,1], diffuse near 0. */
data class PredictedTime(
    val probs: List<Double>,
    val predictedHour: Double,
    val resultantR: Double,
    val nBins: Int,
    val binHours: Double,
)

/** [LOG_WRITE]: a logged meal/dose (or withdrawal) fired this, not cadence tick; same gates. */
enum class InferenceCause { GRID_TICK, LOG_WRITE, MANUAL, SYNTHETIC, COLLECTING_CONTEXT, OVER_TEMPERATURE }

/** BATTERY °C; [thresholdC] pause line, [warnMarginC] amber margin, [resumeMarginC] hysteresis. */
data class ThermalStatus(
    val currentC: Double,
    val thresholdC: Double,
    val warnMarginC: Double,
    val resumeMarginC: Double,
)

/** TEMP-chip band (D1): NORMAL below margin, WARN within, CRITICAL at/above threshold. */
enum class ThermalLevel { NORMAL, WARN, CRITICAL }

/** Celsius; null [thresholdC] ⇒ NORMAL, the gate being disabled. */
fun thermalLevel(celsius: Double, thresholdC: Double?, warnMarginC: Double): ThermalLevel = when {
    thresholdC == null -> ThermalLevel.NORMAL
    celsius >= thresholdC -> ThermalLevel.CRITICAL
    celsius >= thresholdC - warnMarginC -> ThermalLevel.WARN
    else -> ThermalLevel.NORMAL
}

/** Hours of MEASURED BG in window; below [requiredHours] all predictions suppressed. */
data class WarmupProgress(val measuredHours: Double, val requiredHours: Double) {
    val fraction: Double get() = if (requiredHours <= 0.0) 1.0 else (measuredHours / requiredHours).coerceIn(0.0, 1.0)
}

/** Immutable StateFlow snapshot; [predictions] selected-first, [note] states refusal reason. */
data class InferenceState(
    val running: List<RunningModel> = emptyList(),
    val predictions: List<ModelPrediction> = emptyList(),
    val latencies: List<ModelLatency> = emptyList(),
    val metas: List<ModelMeta> = emptyList(),
    val telemetry: List<ModelTelemetry> = emptyList(),
    val lastCycleTsMs: Long? = null,
    val lastCause: InferenceCause? = null,
    val lastCycleDurationMs: Long? = null,
    /** false when selected model served by [BackendId.STUB] fallback (no real .pte). */
    val realBackendAvailable: Boolean = true,
    /** Non-null while WARMUP withholds forecasts (predictions cleared); null once met. */
    val warmup: WarmupProgress? = null,
    /** Published independent of BG forecast, surviving warmup; phase belief, not dosing signal. */
    val circadianTime: PredictedTime? = null,
    /** Anchor (epoch-ms) [circadianTime] formed at; clock offset is measured from it. */
    val circadianAnchorMs: Long? = null,
    /** True when [circadianTime] was formed during warmup on limited history. */
    val circadianLowContext: Boolean = false,
    /** Distinguishes "no time section" from "decode failed"; defaults true until cycle sets it. */
    val selectedHasTimeSection: Boolean = true,
    val note: String? = null,
) {
    val selectedPrediction: ModelPrediction? get() = predictions.firstOrNull { it.selected }

    val selectedPredictedTime: PredictedTime? get() = circadianTime ?: selectedPrediction?.predictedTime

    fun latencyOf(modelId: String): ModelLatency? = latencies.firstOrNull { it.modelId == modelId }

    fun metaOf(modelId: String): ModelMeta? = metas.firstOrNull { it.modelId == modelId }

    fun telemetryOf(modelId: String): ModelTelemetry? = telemetry.firstOrNull { it.modelId == modelId }

    fun runningOf(modelId: String): RunningModel? = running.firstOrNull { it.modelId == modelId }
}
