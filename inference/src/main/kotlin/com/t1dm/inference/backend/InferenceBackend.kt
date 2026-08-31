package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.Precision
import java.io.File
import java.nio.FloatBuffer

data class BackendCaps(
    val precision: Precision,
    /** Additive-float struct mask, never a bool one; one-hot selection, never an int64 index. */
    val maskIsExternalStruct: Boolean = true,
)

/** Direct buffers positioned at 0: `patches (1,T,PATCH_DIM)` step-major z-space, `mask (T,T)`
 *  additive (`0` attend / `neg_fill` block), `slotSel (M,T)` one-hot rows naming each slot's patch. */
class GraphTensors(
    val patches: FloatBuffer,
    val mask: FloatBuffer,
    val slotSel: FloatBuffer,
    val t: Int,
    val patchDim: Int,
    val mSlots: Int,
)

/** [headRaw] is the flattened `M·S·7` risk-space `head_raw`, C-contiguous over
 *  `(slot, step, level)`. [timeLogits] is the hour-of-day probe, flat `(M, nBins)`; [hidden] the
 *  final-normed state of every PATCH, flat `(T, D_MODEL)` — a span's spline nodes reach outside its
 *  own slots, so the seam carries the whole window. Both null when the export omits them. */
class GraphOutput(
    val headRaw: FloatArray,
    val timeLogits: FloatArray? = null,
    val hidden: FloatArray? = null,
)

/** Closed via [InferenceBackend.close]. */
interface LoadedModel {
    val id: String
    val caps: BackendCaps
}

/** [run] is blocking and MUST be called on the single-thread `inference` dispatcher. */
interface InferenceBackend {
    val id: BackendId
    val caps: BackendCaps

    /** The graph starts from normalized patches; the weights are baked into [pte]. */
    fun load(desc: ModelDescriptor, pte: File): LoadedModel

    fun run(m: LoadedModel, x: GraphTensors): GraphOutput

    fun close(m: LoadedModel)
}
