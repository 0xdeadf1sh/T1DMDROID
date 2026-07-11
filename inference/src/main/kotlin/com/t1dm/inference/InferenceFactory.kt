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
    backendPrefProvider: suspend (modelId: String) -> BackendId? = { null },
    telemetryStore: TelemetryStore? = null,
    thermalProvider: suspend () -> ThermalStatus? = { null },
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
        backendPrefProvider = backendPrefProvider,
        telemetryStore = telemetryStore,
        thermalProvider = thermalProvider,
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
}
