package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.Precision
import java.io.File

/**
 * The NPU routing targets behind the [InferenceBackend] seam (§3.2). fp32 CPU XNNPACK
 * ([ExecuTorchXnnpackBackend]) REMAINS THE AUTHORITY; each NPU path here is an alternative backend
 * that is cross-checked against the fp32 goldens and NEVER silently promoted.
 *
 * Every NPU backend below FAILS its `load` with a plain-language, evidence-based reason — so a
 * mis-routed model is refused loudly (the controller falls back to the [StubBackend]) rather than a
 * silent no-op, and the Hardware / Models panels can state UNAMBIGUOUSLY why each is unavailable
 * (issue 1 — end the "stub" ambiguity). The reasons are factual, not aspirational. NOTHING RUNS ON
 * THE APU; the one accelerated path that does execute is [ExecuTorchVulkanBackend] at the end of
 * this file, on the GPU, which is why the file's name undersells it:
 *
 *  • [LiteRtNpuBackend] — the LiteRT-unified MediaTek NeuroPilot path (the user's chosen NPU route).
 *    The `.tflite` ARTIFACT is proven: `T1DMAI/exporters/litert_npu.py` converts the SAME modified
 *    `head_raw`+`time_logits` forward via `litert-torch` and matches the fp32 authority to
 *    `max|Δ| ≈ 1.4e-6` (head_raw) / `4.5e-6` (time_logits) on host. On-device execution is blocked
 *    for THIS app because the NeuroPilot NPU runtime libraries ship only via Google Play PODAI /
 *    Play Feature Delivery (`litert_npu_runtime_libraries:mediatek_runtime`), which a sideload-only
 *    build cannot fetch; and LiteRT exposes no Kotlin/Java API to confirm the APU actually executed
 *    the graph (only the C++/Python `is_fully_accelerated()`), so a silent CPU/GPU partial-delegation
 *    could not be told apart from real APU execution. When those two gaps close (runtime `.so`s
 *    bundled into `jniLibs`, or a Play track), the load becomes
 *    `CompiledModel.create(assets, "<id>.tflite", Options(Accelerator.NPU, Accelerator.GPU))` and the
 *    run reads BOTH output buffers exactly as [ExecuTorchXnnpackBackend] reads the two `.pte` slots.
 *    Dep: `com.google.ai.edge.litert:litert:2.1.x` (google() maven), minSdk 31, arm64-v8a only.
 *
 *  • [ExecuTorchNeuronBackend] — an fp16 ExecuTorch MediaTek/Neuron delegate. The stock
 *    `org.pytorch:executorch-android:1.3.1` AAR ships ONLY `libexecutorch.so` (zero Neuron/MediaTek
 *    delegate), and the MediaTek NeuroPilot SDK (`mtk_neuron`/`ncc-tflite`) needed to lower a
 *    `.neuron.pte` is partner-gated and not installed — so there is nothing to route to.
 *
 *  • [LiteRtNeuronBackend] — the legacy TFLite NeuroPilot *delegate* path, superseded by the
 *    LiteRT-unified [LiteRtNpuBackend] `CompiledModel` route above; kept only as an enumerated id.
 */

/** The LiteRT-unified MediaTek NeuroPilot NPU path (issue 1). Artifact proven; on-device blocked. */
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

    override fun run(m: LoadedModel, x: GraphInput): GraphOutput = throw NotImplementedError(NOT_YET)
    override fun close(m: LoadedModel) = Unit
}

/** ExecuTorch + MediaTek Neuron delegate, fp16 on the APU 990 — no delegate in the shipped AAR. */
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

    override fun run(m: LoadedModel, x: GraphInput): GraphOutput = throw NotImplementedError(NOT_YET)
    override fun close(m: LoadedModel) = Unit
}

/** Legacy TFLite NeuroPilot *delegate* path — superseded by the LiteRT-unified [LiteRtNpuBackend]. */
class LiteRtNeuronBackend : InferenceBackend {
    override val id = BackendId.LITERT_NEURON_FP16
    override val caps = BackendCaps(precision = Precision.FP16)

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel =
        throw NotImplementedError(
            "LITERT_NEURON_FP16: the legacy TFLite NeuroPilot delegate is superseded by the LiteRT-" +
                "unified CompiledModel NPU path (BackendId.LITERT_NPU). Enumerated for routing only.",
        )

    override fun run(m: LoadedModel, x: GraphInput): GraphOutput = throw NotImplementedError(NOT_YET)
    override fun close(m: LoadedModel) = Unit
}

/**
 * ExecuTorch Vulkan GPU-compute delegate on the Mali GPU (issue 20). One class serves BOTH the
 * fp32 ([BackendId.EXECUTORCH_VULKAN_FP32]) and the fp16 ([BackendId.EXECUTORCH_VULKAN_FP16])
 * variants: the precision is baked into the `.pte` by the exporter's `force_fp16` (fp16 GPU
 * storage+compute; the input/output STAGING buffers stay fp32, so `Tensor.fromBlob(FloatArray)` and
 * `dataAsFloatArray` are unchanged) and routed by the descriptor `engine`, so `load`/`run` are
 * precision-agnostic here — only [id]/[caps] differ. The custom
 * `executorch-android` AAR built from ExecuTorch 1.3.1 source with `EXECUTORCH_BUILD_VULKAN=ON`
 * DOES register the Vulkan backend (verified: the packaged `libexecutorch.so` carries the
 * `VulkanBackend` symbol + op library, alongside XNNPACK). The host PARTITION report is strong —
 * 95.1 % of the edge graph (974/1024 ops, 18 subgraphs) delegates to Vulkan; only the boolean
 * attention-mask reduction + two RoPE inv-freq scalars fall back to the portable CPU kernels
 * (RoPE trig, SDPA, ALiBi, and the einsum step-basis all delegate).
 *
 * The artifact exists. `T1DMAI/exporters/executorch_vulkan.py` serializes a `.vulkan.pte` after
 * installing its own fix for the ExecuTorch-1.3.1 Vulkan preprocess pipeline, which used to trip
 * `AssertionError: fake mode … doesn't match mode …` in `exir/pass_base.py` on every
 * constant-folding fusion: a constant-only pass yields ZERO fake inputs, so the pass base spawned
 * a fresh `FakeTensorMode` that then disagreed with the graph's own. The XNNPACK lowering path
 * never hit it. With that cured, `load`/`run` below are the ordinary `Module.load` plus the dual
 * `head_raw`/`time_logits` read, exactly as [ExecuTorchXnnpackBackend] performs them.
 *
 * fp32 XNNPACK CPU stays the AUTHORITY regardless (safety rule E). This backend may render a
 * forecast, but its numerics must pass the fp32-agreement gate before they may feed any §3.6
 * dosing decision.
 */
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

    /**
     * Load the `.vulkan.pte`. The custom AAR's `libexecutorch.so` registers `VulkanBackend`, so
     * `Module.load` resolves the Vulkan-delegated subgraphs at load time (the delegate builds its
     * Vulkan compute context + shaders here). A device without a working Vulkan compute path throws
     * from native — the controller catches it and falls back to the [StubBackend], never silently to
     * a different backend. The artifact is REQUIRED to be the `.vulkan.pte` (the XNNPACK `.pte` would
     * load fine on the same runtime but would NOT exercise the GPU — the descriptor's `engine` routes
     * the right artifact here).
     */
    override fun load(desc: ModelDescriptor, pte: File): LoadedModel {
        require(pte.exists()) { "vulkan pte artifact missing: ${pte.absolutePath}" }
        val module = org.pytorch.executorch.Module.load(
            pte.absolutePath,
            org.pytorch.executorch.Module.LOAD_MODE_MMAP_USE_MLOCK_IGNORE_ERRORS,
        )
        return EtModel(pte.nameWithoutExtension, caps, module)
    }

    /**
     * One forward `(patches, struct-mask) → (head_raw, time_logits?)`, identical output contract to
     * [ExecuTorchXnnpackBackend] — the same modified forward was lowered, only the partitioner
     * differs, so slot 0 is `head_raw (1,4,6,7)` flattened to 168 and slot 1 the optional time
     * probe. Blocking; the controller confines it to the single-thread `inference` dispatcher.
     */
    override fun run(m: LoadedModel, x: GraphInput): GraphOutput {
        val model = m as EtModel
        val patches = org.pytorch.executorch.Tensor.fromBlob(
            x.patches, longArrayOf(1, GraphIo.T.toLong(), GraphIo.PATCH_DIM.toLong()),
        )
        val mask = org.pytorch.executorch.Tensor.fromBlob(
            x.mask, longArrayOf(GraphIo.T.toLong(), GraphIo.T.toLong()),
        )
        val out = model.module.forward(
            org.pytorch.executorch.EValue.from(patches),
            org.pytorch.executorch.EValue.from(mask),
        )
        require(out.isNotEmpty() && out[0].isTensor) { "vulkan backend returned no head_raw tensor" }
        val head = out[0].toTensor().dataAsFloatArray
        require(head.size == PRED_STEPS * N_Q) { "head_raw size ${head.size} != ${PRED_STEPS * N_Q}" }
        val timeLogits: FloatArray? =
            if (out.size > 1 && out[1].isTensor) out[1].toTensor().dataAsFloatArray else null
        return GraphOutput(head, timeLogits)
    }

    override fun close(m: LoadedModel) {
        runCatching { (m as EtModel).module.destroy() }
    }

    private companion object {
        const val PRED_STEPS = GraphIo.PRED * GraphIo.PATCH_DIM / 3 // 4·6 = 24 output steps
        const val N_Q = 7
    }
}

private const val NOT_YET = "NPU/GPU backend load must succeed before run; this path is unavailable (see load())."
