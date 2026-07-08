package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.inference.backend.ExecuTorchNeuronBackend
import com.t1dm.inference.backend.ExecuTorchXnnpackBackend
import com.t1dm.inference.backend.LiteRtNeuronBackend
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
): InferenceController {
    val store = ModelStore(modelsDir, native)
    val controller = InferenceController(
        native = native,
        dispatchers = dispatchers,
        store = store,
        history = history,
        predictionStore = predictionStore,
    )
    controller.registerBackend(ExecuTorchXnnpackBackend())
    controller.registerBackend(ExecuTorchNeuronBackend())
    controller.registerBackend(LiteRtNeuronBackend())
    return controller
}
