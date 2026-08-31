package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.Precision
import java.io.File

class LiteRtNpuBackend : InferenceBackend {
    override val id = BackendId.LITERT_NPU
    override val caps = BackendCaps(precision = Precision.FP32)

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel =
        throw NotImplementedError(
            "LITERT_NPU: the .tflite artifact converts + matches fp32 XNNPACK to max|Δ|≈1.4e-6 on host " +
                "(litert_npu.py), but on-device NPU execution is unavailable in a SIDELOAD build — the " +
                "MediaTek NeuroPilot NPU runtime ships via Google Play PODAI / Play Feature Delivery, not " +
                "in the litert AAR, and no Kotlin API reports whether the APU (vs a CPU/GPU fallback) ran. " +
                "Bundle the runtime .so into jniLibs (or ship a Play track) to enable; fp32 XNNPACK stays " +
                "authoritative regardless.",
        )

    override fun run(m: LoadedModel, x: GraphTensors): GraphOutput = throw NotImplementedError(NOT_YET)
    override fun close(m: LoadedModel) = Unit
}

class ExecuTorchNeuronBackend : InferenceBackend {
    override val id = BackendId.EXECUTORCH_NEURON_FP16
    override val caps = BackendCaps(precision = Precision.FP16)

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel =
        throw NotImplementedError(
            "EXECUTORCH_NEURON_FP16: the org.pytorch:executorch-android:1.3.1 AAR ships only " +
                "libexecutorch.so (no MediaTek/Neuron delegate), and the partner-gated NeuroPilot SDK " +
                "needed to lower a .neuron.pte is not installed. Route the selected model through " +
                "ExecuTorchXnnpackBackend; use the LiteRT NPU path for the APU.",
        )

    override fun run(m: LoadedModel, x: GraphTensors): GraphOutput = throw NotImplementedError(NOT_YET)
    override fun close(m: LoadedModel) = Unit
}

class LiteRtNeuronBackend : InferenceBackend {
    override val id = BackendId.LITERT_NEURON_FP16
    override val caps = BackendCaps(precision = Precision.FP16)

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel =
        throw NotImplementedError(
            "LITERT_NEURON_FP16: the legacy TFLite NeuroPilot delegate is superseded by the LiteRT-" +
                "unified CompiledModel NPU path (BackendId.LITERT_NPU). Enumerated for routing only.",
        )

    override fun run(m: LoadedModel, x: GraphTensors): GraphOutput = throw NotImplementedError(NOT_YET)
    override fun close(m: LoadedModel) = Unit
}

/** Serves both Vulkan ids: precision is baked into the `.pte` by the exporter, so only [id]/[caps]
 *  differ. Needs the custom AAR built with `EXECUTORCH_BUILD_VULKAN=ON`. fp32 XNNPACK stays the
 *  authority; these numerics must pass the fp32-agreement gate before feeding a §3.6 dose. */
class ExecuTorchVulkanBackend(
    override val id: BackendId = BackendId.EXECUTORCH_VULKAN_FP32,
    precision: Precision = Precision.FP32,
) : InferenceBackend {
    override val caps = BackendCaps(precision = precision)

    private class EtModel(
        override val id: String,
        override val caps: BackendCaps,
        val module: org.pytorch.executorch.Module,
    ) : LoadedModel

    /** Must be the `.vulkan.pte`; the XNNPACK `.pte` loads on the same runtime but never reaches the
     *  GPU. Native throws without a working Vulkan compute path; the controller falls back to
     *  [StubBackend], never to another backend. */
    override fun load(desc: ModelDescriptor, pte: File): LoadedModel {
        require(pte.exists()) { "vulkan pte artifact missing: ${pte.absolutePath}" }
        val module = org.pytorch.executorch.Module.load(
            pte.absolutePath,
            org.pytorch.executorch.Module.LOAD_MODE_MMAP_USE_MLOCK_IGNORE_ERRORS,
        )
        return EtModel(pte.nameWithoutExtension, caps, module)
    }

    /** Outputs in order: head_raw, time_logits?, hidden?. Blocking; the controller confines it
     *  to the single-thread `inference` dispatcher. */
    override fun run(m: LoadedModel, x: GraphTensors): GraphOutput {
        val model = m as EtModel
        val patches = org.pytorch.executorch.Tensor.fromBlob(
            x.patches, longArrayOf(1, x.t.toLong(), x.patchDim.toLong()),
        )
        val mask = org.pytorch.executorch.Tensor.fromBlob(
            x.mask, longArrayOf(x.t.toLong(), x.t.toLong()),
        )
        val slotSel = org.pytorch.executorch.Tensor.fromBlob(
            x.slotSel, longArrayOf(x.mSlots.toLong(), x.t.toLong()),
        )
        val out = model.module.forward(
            org.pytorch.executorch.EValue.from(patches),
            org.pytorch.executorch.EValue.from(mask),
            org.pytorch.executorch.EValue.from(slotSel),
        )
        require(out.isNotEmpty() && out[0].isTensor) { "vulkan backend returned no head_raw tensor" }
        val head = out[0].toTensor().dataAsFloatArray
        require(head.size == x.mSlots * PATCH_SIZE * N_Q) {
            "head_raw size ${head.size} != M·S·7 ${x.mSlots * PATCH_SIZE * N_Q}"
        }
        fun optional(i: Int): FloatArray? =
            if (out.size > i && out[i].isTensor) out[i].toTensor().dataAsFloatArray else null
        return GraphOutput(head, optional(1), optional(2))
    }

    override fun close(m: LoadedModel) {
        runCatching { (m as EtModel).module.destroy() }
    }

    private companion object {
        const val PATCH_SIZE = 6
        const val N_Q = 7
    }
}

private const val NOT_YET = "NPU/GPU backend load must succeed before run; this path is unavailable (see load())."
