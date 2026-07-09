package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.Precision
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/**
 * The CPU fp32 XNNPACK reference/authority (PLAN.private.md §3.2), on the ExecuTorch Android
 * runtime pinned to the exporter's **1.3.1** (`org.pytorch:executorch-android:1.3.1`). It loads a
 * `.pte` traced from the modified `head_raw` forward and runs `(patches, struct) → head_raw`.
 *
 * The exporter verified the on-device runtime against the eager modified forward at `max|Δ| ≈
 * 1.4e-6` (well under the 1e-3 gate), and the struct mask against the stock bool mask at exactly
 * `0.0` (`exp(-30000)` underflows to 0 in fp32). The forward is blocking and must run on the
 * single-thread `inference` dispatcher; a loaded [Module] is not safe to call concurrently.
 */
class ExecuTorchXnnpackBackend : InferenceBackend {
    override val id = BackendId.EXECUTORCH_XNNPACK_FP32
    override val caps = BackendCaps(precision = Precision.FP32)

    private class EtModel(
        override val id: String,
        override val caps: BackendCaps,
        val module: Module,
    ) : LoadedModel

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel {
        require(pte.exists()) { "pte artifact missing: ${pte.absolutePath}" }
        // MMAP + mlock keeps the ~8.7 MB weights resident; IGNORE_ERRORS tolerates a device that
        // cannot lock (falls back to a plain mmap) rather than failing the load.
        val module = Module.load(pte.absolutePath, Module.LOAD_MODE_MMAP_USE_MLOCK_IGNORE_ERRORS)
        return EtModel(pte.nameWithoutExtension, caps, module)
    }

    override fun run(m: LoadedModel, x: GraphInput): GraphOutput {
        val model = m as EtModel
        val patches = Tensor.fromBlob(x.patches, longArrayOf(1, GraphIo.T.toLong(), GraphIo.PATCH_DIM.toLong()))
        val mask = Tensor.fromBlob(x.mask, longArrayOf(GraphIo.T.toLong(), GraphIo.T.toLong()))
        val out: Array<EValue> = model.module.forward(EValue.from(patches), EValue.from(mask))
        require(out.isNotEmpty() && out[0].isTensor) { "backend returned no head_raw tensor" }
        val head = out[0].toTensor().dataAsFloatArray
        require(head.size == PRED_STEPS * N_Q) { "head_raw size ${head.size} != ${PRED_STEPS * N_Q}" }
        // Slot 1 is the co-trained time probe (`time_logits`, (1,P,N_BINS)) — present only in a
        // time-exported `.pte`. Read it OPPORTUNISTICALLY and defensively: a head_raw-only export
        // has a single output, so a missing/non-tensor slot 1 leaves timeLogits null (no crash,
        // BG path unaffected). The Rust `decode_time` owns the softmax/resultant downstream.
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
