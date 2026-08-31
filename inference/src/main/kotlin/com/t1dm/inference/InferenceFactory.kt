package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ThermalStatus
import com.t1dm.inference.backend.ExecuTorchXnnpackBackend
import java.io.File

/** Keeps `:app` free of the concrete backend types and the ExecuTorch AAR classpath. */
fun buildInferenceController(
    native: NativeCore,
    dispatchers: T1dmDispatchers,
    modelsDir: File,
    history: BgHistoryProvider,
    predictionStore: PredictionStore,
    contextChannels: ContextChannelSource? = null,
    futureOverrides: FutureOverrideSource? = null,
    warmupHoursProvider: suspend () -> Double = { InferenceControllerDefaults.WARMUP_HOURS },
    maxRunningProvider: suspend () -> Int = { InferenceController.DEFAULT_MAX_RUNNING },
    telemetryStore: TelemetryStore? = null,
    thermalProvider: suspend () -> ThermalStatus? = { null },
    smoothingWindowProvider: suspend () -> Int = { InferenceControllerDefaults.SAVGOL_WINDOW },
    baselineStore: BaselineStore? = null,
    curveEvents: CurveEventSource? = null,
    loraStore: LoraStore? = null,
    probeInsulin: ProbeInsulinPort? = null,
): InferenceController {
    val store = ModelStore(modelsDir, native)
    val controller = InferenceController(
        native = native,
        dispatchers = dispatchers,
        store = store,
        history = history,
        predictionStore = predictionStore,
        contextChannels = contextChannels,
        futureOverrides = futureOverrides,
        warmupHoursProvider = warmupHoursProvider,
        maxRunningProvider = maxRunningProvider,
        telemetryStore = telemetryStore,
        thermalProvider = thermalProvider,
        smoothingWindowProvider = smoothingWindowProvider,
        baseline = BaselineRunner(native, dispatchers, baselineStore, curveEvents, futureOverrides),
        loraStore = loraStore,
        probeInsulin = probeInsulin,
    )
    controller.registerBackend(ExecuTorchXnnpackBackend())
    return controller
}

/** Shared with `:app`'s Settings floor (inference-runtime.md). */
object InferenceControllerDefaults {
    const val WARMUP_HOURS = 24.0

    /** Kept well under each descriptor's own MIN_CONTEXT, so the setting never binds first. */
    const val MIN_WARMUP_HOURS = 8

    /** INFERENCE.md §7.1. 7 taps ≙ the trailing 30 min. */
    const val SAVGOL_WINDOW = 7

    /** In samples (× 5 min); `1` is unfiltered. Discrete because the filter needs an ODD window. */
    val SAVGOL_STOPS = listOf(1, 7, 13, 19, 25)

    /** The single coercion both `:app`'s store and the controller apply. */
    fun nearestSmoothingStop(window: Int): Int =
        // Long deliberately: `abs(1 - Int.MIN_VALUE)` overflows into a small distance and would snap
        // the most hostile value to the WIDEST filter.
        SAVGOL_STOPS.minByOrNull { kotlin.math.abs(it.toLong() - window.toLong()) } ?: SAVGOL_WINDOW
}
