package com.t1dm.core.model

/** [EXECUTORCH_XNNPACK_FP32]=dose-scorable; [STUB]=fallback. */
enum class BackendId {
    EXECUTORCH_XNNPACK_FP32,
    STUB,

    /** Backend this build lacks; lets an old row read back as unknown instead of throwing. */
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
    val selected: Boolean,
)

/** [diskBytes]=stat'd size, null under StubBackend. [reference]=held-out REFERENCE only. */
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
    val bgClampMinMgdl: Double? = null,
)

/** Parsed but rendered NOWHERE: another dataset's numbers, not a second opinion on this patient. */
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

/** [medianBg]=P·S mg/dL, [bandsMgdl]=fan, step-major, f_inv-decoded; non-OK/stale blocks rails. */
data class ModelPrediction(
    val modelId: String,
    val cycleTsMs: Long,
    val anchorTsMs: Long,
    /** CGM source conditioning this; null=UNKNOWN, never matches, so it's dropped not guessed. */
    val sourceId: String? = null,
    val stepMs: Long,
    val medianBg: List<Double>,
    val bandsMgdl: List<Double>,
    val nQuantiles: Int,
    val lastBg: Double,
    val status: ForecastStatus,
    val backend: BackendId,
    val selected: Boolean,
    val stale: Boolean,
    val latencyMs: Double?,
    /** Null if no time section, no second output, or decode failed; fails open, never blocks BG. */
    val predictedTime: PredictedTime? = null,
) {
    val eligible: Boolean get() = status == ForecastStatus.OK && !stale

    val horizonSteps: Int get() = medianBg.size
}

/** §6.1 alarm levels as positions in a [ModelPrediction.bandsMgdl] row; the crate resolves them. */
data class AlarmFanEdges(val hypoIdx: Int, val hyperIdx: Int)

/** Model's belief of hour-of-day NOW, not per-step. [resultantR]∈[0,1]; diffuse near 0. */
data class PredictedTime(
    val probs: List<Double>,
    val predictedHour: Double,
    val resultantR: Double,
    val nBins: Int,
    val binHours: Double,
)

/** [LOG_WRITE]: a logged meal/dose fired this cycle, not the tick; same controller path/gates. */
enum class InferenceCause { GRID_TICK, LOG_WRITE, MANUAL, SYNTHETIC, COLLECTING_CONTEXT, OVER_TEMPERATURE }

/** BATTERY sensor °C, die temp unreadable. [thresholdC]=pause line, [resumeMarginC]=hysteresis. */
data class ThermalStatus(
    val currentC: Double,
    val thresholdC: Double,
    val warnMarginC: Double,
    val resumeMarginC: Double,
)

/** TEMP-chip band (D1): NORMAL below warn margin, WARN within it, CRITICAL at/above threshold. */
enum class ThermalLevel { NORMAL, WARN, CRITICAL }

/** Celsius; null [thresholdC] ⇒ NORMAL, the gate being disabled. */
fun thermalLevel(celsius: Double, thresholdC: Double?, warnMarginC: Double): ThermalLevel = when {
    thresholdC == null -> ThermalLevel.NORMAL
    celsius >= thresholdC -> ThermalLevel.CRITICAL
    celsius >= thresholdC - warnMarginC -> ThermalLevel.WARN
    else -> ThermalLevel.NORMAL
}

/** MEASURED BG hours in window; below [requiredHours] (floor 8h) all predictions suppress. */
data class WarmupProgress(val measuredHours: Double, val requiredHours: Double) {
    val fraction: Double get() = if (requiredHours <= 0.0) 1.0 else (measuredHours / requiredHours).coerceIn(0.0, 1.0)
}

/** Immutable UI snapshot. [predictions] is selected-first; [note] says why a refusal refused. */
data class InferenceState(
    val running: List<RunningModel> = emptyList(),
    /** The authoritative sensor's; the only forecasts alarms, dosing and outbound read (§7). */
    val predictions: List<ModelPrediction> = emptyList(),
    /** Every other active sensor's, by source id; display only. A sensor in warm-up is absent. */
    val otherPredictions: Map<String, List<ModelPrediction>> = emptyMap(),
    val latencies: List<ModelLatency> = emptyList(),
    val metas: List<ModelMeta> = emptyList(),
    val telemetry: List<ModelTelemetry> = emptyList(),
    val lastCycleTsMs: Long? = null,
    val lastCause: InferenceCause? = null,
    val lastCycleDurationMs: Long? = null,
    /** false when the selected model is served by [BackendId.STUB] fallback (no real .pte). */
    val realBackendAvailable: Boolean = true,
    /** Non-null while WARMUP gate withholds forecasts (predictions cleared); null once met. */
    val warmup: WarmupProgress? = null,
    /** Survives warmup gate, independent of BG; phase belief, NOT glucose/dosing, no §3.6 gate. */
    val circadianTime: PredictedTime? = null,
    /** Anchor (epoch-ms) [circadianTime] was formed at; the clock offset is measured from it. */
    val circadianAnchorMs: Long? = null,
    /** True when [circadianTime] was formed during warmup on limited history. */
    val circadianLowContext: Boolean = false,
    /** Distinguishes no-time-section empty state from decode-failed; defaults true until set. */
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
