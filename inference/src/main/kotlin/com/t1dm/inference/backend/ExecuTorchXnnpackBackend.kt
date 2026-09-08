package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.Precision
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/** CPU fp32, ExecuTorch 1.3.1. [Module] unsafe concurrently; forward runs single-thread only. */
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
        // IGNORE_ERRORS: a device that cannot mlock falls back to a plain mmap instead of failing.
        val module = Module.load(pte.absolutePath, Module.LOAD_MODE_MMAP_USE_MLOCK_IGNORE_ERRORS)
        return EtModel(pte.nameWithoutExtension, caps, module)
    }

    override fun run(m: LoadedModel, x: GraphTensors): GraphOutput {
        val model = m as EtModel
        val patches = Tensor.fromBlob(x.patches, longArrayOf(1, x.t.toLong(), x.patchDim.toLong()))
        val mask = Tensor.fromBlob(x.mask, longArrayOf(x.t.toLong(), x.t.toLong()))
        val slotSel = Tensor.fromBlob(x.slotSel, longArrayOf(x.mSlots.toLong(), x.t.toLong()))
        val out: Array<EValue> =
            model.module.forward(EValue.from(patches), EValue.from(mask), EValue.from(slotSel))
        require(out.isNotEmpty() && out[0].isTensor) { "backend returned no head_raw tensor" }
        val head = out[0].toTensor().dataAsFloatArray
        require(head.size == x.mSlots * PATCH_SIZE * N_Q) {
            "head_raw size ${head.size} != M·S·7 ${x.mSlots * PATCH_SIZE * N_Q}"
        }
        // Time probe and adapter seam, both optional: an export emitting neither leaves them null.
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
