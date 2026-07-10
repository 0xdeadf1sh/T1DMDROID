package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.Precision
import java.io.File

/**
 * A fixed-output fallback backend (Phase 2: "If Phase A produced NO working `.pte`,
 * wire a StubBackend returning a fixed plausible head_raw so the whole path builds + runs, and mark
 * the real path blocked"). It emits a gentle, monotone, non-degenerate `head_raw` so the full
 * CycleRunner → assemble_decode → degeneracy-guard → overlay path is exercisable with no model
 * present. The controller marks [com.t1dm.core.model.InferenceState.realBackendAvailable] `false`
 * whenever the selected model resolves to this backend.
 */
class StubBackend : InferenceBackend {
    override val id = BackendId.STUB
    override val caps = BackendCaps(precision = Precision.FP32)

    private class StubModel(override val id: String, override val caps: BackendCaps) : LoadedModel

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel = StubModel("stub", caps)

    override fun run(m: LoadedModel, x: GraphInput): GraphOutput {
        val head = FloatArray(P * S * Q)
        for (p in 0 until P) {
            for (s in 0 until S) {
                val i = (p * S + s) * Q
                // col0: a gentle risk-space rise over the horizon (DCT-projected downstream).
                head[i] = 0.015f * (p * S + s)
                // cols 1..6: modest pre-softplus spreads → softplus(-1.6)+1e-3 ≈ 0.19 risk each,
                // cumsum'd into a monotone widening fan (passes the degeneracy guard).
                for (c in 1 until Q) head[i + c] = -1.6f
            }
        }
        return GraphOutput(head)
    }

    override fun close(m: LoadedModel) = Unit

    private companion object {
        const val P = GraphIo.PRED          // 4 prediction patches
        const val S = 6                     // PATCH_SIZE steps
        const val Q = 7                     // 1 median + 2·N_SPREADS
    }
}
