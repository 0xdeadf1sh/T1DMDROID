package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ThermalStatus
import com.t1dm.inference.backend.ExecuTorchNeuronBackend
import com.t1dm.inference.backend.ExecuTorchVulkanBackend
import com.t1dm.inference.backend.ExecuTorchXnnpackBackend
import com.t1dm.inference.backend.LiteRtNeuronBackend
import com.t1dm.inference.backend.LiteRtNpuBackend
import java.io.File

/**
 * Builds an [InferenceController] with the standard backend set registered (the CPU fp32 XNNPACK
 * authority + the documented Neuron/LiteRT stubs), so the composition root in `:app` stays free of
 * the concrete ExecuTorch backend types and the ExecuTorch AAR classpath.
 */
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
    backendPrefProvider: suspend (modelId: String) -> BackendId? = { null },
    telemetryStore: TelemetryStore? = null,
    thermalProvider: suspend () -> ThermalStatus? = { null },
    smoothingWindowProvider: suspend () -> Int = { InferenceControllerDefaults.SAVGOL_WINDOW },
    baselineStore: BaselineStore? = null,
    curveEvents: CurveEventSource? = null,
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
        backendPrefProvider = backendPrefProvider,
        telemetryStore = telemetryStore,
        thermalProvider = thermalProvider,
        smoothingWindowProvider = smoothingWindowProvider,
        baseline = BaselineRunner(native, dispatchers, baselineStore, curveEvents, futureOverrides),
    )
    controller.registerBackend(ExecuTorchXnnpackBackend())
    controller.registerBackend(ExecuTorchNeuronBackend())
    controller.registerBackend(LiteRtNeuronBackend())
    controller.registerBackend(LiteRtNpuBackend())
    controller.registerBackend(ExecuTorchVulkanBackend())
    controller.registerBackend(
        ExecuTorchVulkanBackend(BackendId.EXECUTORCH_VULKAN_FP16, Precision.FP16),
    )
    return controller
}

/** Defaults shared between the factory and `:app`'s Settings floor (inference-runtime.md). */
object InferenceControllerDefaults {
    const val WARMUP_HOURS = 24.0

    /** Model MIN_CONTEXT floor for the warmup setting: 16 patches · 6 steps · 5 min = 8 h. */
    const val MIN_WARMUP_HOURS = 8

    /** The causal SavGol window applied to the BG channel before normalization (INFERENCE.md §7.1).
     *  7 taps ≙ the trailing 30 min — the shipped default and the value the fp16-agreement probe is
     *  pinned to, so its input stays build-invariant whatever the user selects. */
    const val SAVGOL_WINDOW = 7

    /** The offered detents, in samples (× 5 min each). `1` is unfiltered. Not a continuous range:
     *  the filter needs an ODD window, and a corrupt even/zero value would otherwise reach the Rust
     *  model-input guard as a hard failure. */
    val SAVGOL_STOPS = listOf(1, 7, 13, 19, 25)

    /** Snap any persisted/garbage value onto the nearest offered detent — the single coercion both
     *  `:app`'s store and the controller apply, so a hand-edited kv row can never widen the filter
     *  to something the Rust guard rejects mid-cycle. */
    fun nearestSmoothingStop(window: Int): Int =
        // Long arithmetic deliberately: `abs(1 - Int.MIN_VALUE)` overflows back into a *small*
        // distance and would snap the most hostile value to the WIDEST filter.
        SAVGOL_STOPS.minByOrNull { kotlin.math.abs(it.toLong() - window.toLong()) } ?: SAVGOL_WINDOW
}
