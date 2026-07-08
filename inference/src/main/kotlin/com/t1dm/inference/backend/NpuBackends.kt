package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.Precision
import java.io.File

/**
 * Documented NPU backend stubs (PLAN.private.md §3.2). The fp16 MediaTek/NeuroPilot (Neuron) path
 * on the APU 990 and the LiteRT + Neuron-delegate fallback are **deferred** — Phase 2 is the CPU
 * fp32 XNNPACK authority only (session decision, inference-runtime.md). They exist here so the seam
 * enumerates every routing target; loading one is an explicit, plain-language failure rather than a
 * silent no-op (a stub that reads as a backend but produces nothing would be worse than none).
 *
 * When the fp16 path lands (a follow-up), each will lower the *same* fixed-`T=52` modified-forward
 * graph to `<id>.neuron.pte` / a LiteRT model, run the selected model as an fp16 shadow for the
 * §3.6-E agreement gate, and upcast `head_raw` to fp64 in Rust before assemble.
 */

/** ExecuTorch + MediaTek Neuron delegate, fp16 on the APU 990 — deferred (inference-runtime.md). */
class ExecuTorchNeuronBackend : InferenceBackend {
    override val id = BackendId.EXECUTORCH_NEURON_FP16
    override val caps = BackendCaps(precision = Precision.FP16)

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel =
        throw NotImplementedError(
            "EXECUTORCH_NEURON_FP16 is deferred to the fp16 follow-up (Phase 2 is CPU fp32 XNNPACK " +
                "authority only). Route the selected model through ExecuTorchXnnpackBackend.",
        )

    override fun run(m: LoadedModel, x: GraphInput): GraphOutput = throw NotImplementedError(NOT_YET)
    override fun close(m: LoadedModel) = Unit
}

/** LiteRT runtime + Neuron delegate — the reserved NPU fallback, also deferred (§3.2). */
class LiteRtNeuronBackend : InferenceBackend {
    override val id = BackendId.LITERT_NEURON_FP16
    override val caps = BackendCaps(precision = Precision.FP16)

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel =
        throw NotImplementedError(
            "LITERT_NEURON_FP16 is the reserved NPU fallback, held in reserve behind the seam (§3.2); " +
                "not implemented this phase.",
        )

    override fun run(m: LoadedModel, x: GraphInput): GraphOutput = throw NotImplementedError(NOT_YET)
    override fun close(m: LoadedModel) = Unit
}

private const val NOT_YET = "NPU backend is a documented stub this phase."
