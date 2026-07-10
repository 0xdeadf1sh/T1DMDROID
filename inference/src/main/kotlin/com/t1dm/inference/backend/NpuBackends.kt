package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.Precision
import java.io.File

/**
 * The NPU routing targets behind the [InferenceBackend] seam (SPEC.private.md §3.2). fp32 CPU XNNPACK
 * ([ExecuTorchXnnpackBackend]) REMAINS THE AUTHORITY; each NPU path here is an alternative backend
 * that is cross-checked against the fp32 goldens and NEVER silently promoted.
 *
 * Every backend below currently FAILS its `load` with a plain-language, evidence-based reason — so a
 * mis-routed model is refused loudly (the controller falls back to the [StubBackend]) rather than a
 * silent no-op, and the Hardware / Models panels can state UNAMBIGUOUSLY why each is unavailable
 * (issue 1 — end the "stub" ambiguity). The reasons are factual, not aspirational:
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
 * ExecuTorch Vulkan GPU-compute delegate, fp32 on the Mali GPU (issue 20). The custom
 * `executorch-android` AAR built from ExecuTorch 1.3.1 source with `EXECUTORCH_BUILD_VULKAN=ON`
 * DOES register the Vulkan backend (verified: the packaged `libexecutorch.so` carries the
 * `VulkanBackend` symbol + op library, alongside XNNPACK). The host PARTITION report is strong —
 * 95.1 % of the edge graph (974/1024 ops, 18 subgraphs) delegates to Vulkan; only the boolean
 * attention-mask reduction + two RoPE inv-freq scalars fall back to the portable CPU kernels
 * (RoPE trig, SDPA, ALiBi, and the einsum step-basis all delegate).
 *
 * What blocks on-device execution TODAY is not the runtime but the ARTIFACT: a `.vulkan.pte`
 * cannot be serialized on host under the pinned ExecuTorch 1.3.1 with the installed torch 2.12.1.
 * The Vulkan preprocess pass pipeline (FuseBatchNorm → FuseClamp → …) trips
 * `AssertionError: fake mode … doesn't match mode … from fake tensor input 1` in
 * `exir/pass_base.py` (a `FakeTensorMode(allow_non_fake_inputs=True)` reconciliation the newer
 * torch rejects). The XNNPACK lowering path avoids it; the Vulkan path hits it on every
 * constant-folding fusion pass, so no `.vulkan.pte` exists to load. fp32 XNNPACK CPU stays the
 * AUTHORITY regardless (safety rule E); when a `.vulkan.pte` can be emitted (a torch matched to
 * ET 1.3.1, or the passes patched upstream), this load becomes the standard `Module.load` + dual
 * `head_raw`/`time_logits` read like [ExecuTorchXnnpackBackend], and its numerics must still pass
 * the fp32-agreement gate before it may feed any §3.6 dosing decision.
 */
class ExecuTorchVulkanBackend : InferenceBackend {
    override val id = BackendId.EXECUTORCH_VULKAN_FP32
    override val caps = BackendCaps(precision = Precision.FP32)

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel =
        throw NotImplementedError(
            "EXECUTORCH_VULKAN_FP32: the custom Vulkan-delegate AAR is built and registers the " +
                "VulkanBackend, and the graph delegates 95% to the GPU on host — but no .vulkan.pte " +
                "can be serialized under ExecuTorch 1.3.1 + torch 2.12.1: the Vulkan preprocess " +
                "fusion passes hit a FakeTensorMode-mismatch AssertionError in exir/pass_base.py. " +
                "Emit a .vulkan.pte (torch matched to ET 1.3.1, or patched passes) to enable; fp32 " +
                "XNNPACK CPU stays authoritative and this path must pass the agreement gate before " +
                "it can drive a dose.",
        )

    override fun run(m: LoadedModel, x: GraphInput): GraphOutput = throw NotImplementedError(NOT_YET)
    override fun close(m: LoadedModel) = Unit
}

private const val NOT_YET = "NPU/GPU backend load must succeed before run; this path is unavailable (see load())."
