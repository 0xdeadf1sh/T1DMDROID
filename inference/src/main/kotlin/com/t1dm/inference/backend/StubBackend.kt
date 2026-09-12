package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import java.io.File

/** Fixed plausible head_raw runs with no model; controller marks realBackendAvailable false. */
class StubBackend : InferenceBackend {
    override val id = BackendId.STUB
    override val caps = BackendCaps()

    private class StubModel(override val id: String, override val caps: BackendCaps) : LoadedModel

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel = StubModel("stub", caps)

    override fun run(m: LoadedModel, x: GraphTensors): GraphOutput {
        val head = FloatArray(x.mSlots * S * Q)
        for (p in 0 until x.mSlots) {
            for (s in 0 until S) {
                val i = (p * S + s) * Q
                // col0: risk-space rise, added to the slot's anchor downstream.
                head[i] = 0.015f * (p * S + s)
                // cols 1..6: pre-softplus spreads, softplus(-1.6)+1e-3~=0.19, clears degeneracy.
                for (c in 1 until Q) head[i + c] = -1.6f
            }
        }
        return GraphOutput(head)
    }

    override fun close(m: LoadedModel) = Unit

    private companion object {
        const val S = 6                     // PATCH_SIZE steps
        const val Q = 7                     // 1 median + 2·N_SPREADS
    }
}
