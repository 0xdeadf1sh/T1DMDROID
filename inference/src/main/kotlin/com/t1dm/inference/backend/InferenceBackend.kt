package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.Precision
import java.io.File
import java.nio.FloatBuffer

/**
 * The `InferenceBackend` seam. One fixed-shape artifact — its `T` and slot count read from the
 * descriptor, never assumed — drives interchangeable backends: a CPU fp32 XNNPACK
 * reference/authority ([ExecuTorchXnnpackBackend]), Neuron/LiteRT NPU paths held as documented
 * stubs, and a [StubBackend] fixed-output fallback so the whole pipeline builds and runs even
 * before a real `.pte` is pushed.
 *
 * The graph is cut at `head_raw` in risk space and emits the trunk's per-slot hidden state
 * beside it; the per-slot anchors, the per-span DCT median projection, the quantile assembly,
 * `f_inv` and the degeneracy guard are all downstream in the fp32/fp64 Rust `t1dm-core`, as is
 * the adapter that may re-run the head from `slot_hidden`.
 */

/** Static capabilities of a backend. */
data class BackendCaps(
    val precision: Precision,
    /** The mask crosses as the external additive-float struct mask (never a bool mask), and
     *  the masked set as a one-hot selection matrix (never an int64 index tensor). */
    val maskIsExternalStruct: Boolean = true,
)

/**
 * The graph's three inputs, already in direct buffers positioned at 0: `patches (1,T,PATCH_DIM)`
 * step-major z-space, `mask (T,T)` additive (`0` attend / `neg_fill` block), and
 * `slotSel (M,T)` one-hot rows naming the patch each head slot reads.
 */
class GraphTensors(
    val patches: FloatBuffer,
    val mask: FloatBuffer,
    val slotSel: FloatBuffer,
    val t: Int,
    val patchDim: Int,
    val mSlots: Int,
)

/**
 * The graph's outputs. [headRaw] is the flattened `M·S·7` `head_raw` in risk space,
 * C-contiguous over `(slot, step, level)` — exactly what `t1dm-core::assemble_decode` consumes.
 * [timeLogits] is the co-trained hour-of-day probe (flat `(M, nBins)`) when the export emits it,
 * else `null`. [slotHidden] is the trunk's final-normed hidden state per slot (flat
 * `(M, D_MODEL)`), the seam an adapter attaches to; `null` on an export that predates it.
 */
class GraphOutput(
    val headRaw: FloatArray,
    val timeLogits: FloatArray? = null,
    val slotHidden: FloatArray? = null,
)

/** An opaque backend-loaded model handle; closed via [InferenceBackend.close]. */
interface LoadedModel {
    val id: String
    val caps: BackendCaps
}

/** A pluggable inference backend. [run] is blocking and MUST be called off-main (the
 *  single-thread `inference` dispatcher). */
interface InferenceBackend {
    val id: BackendId
    val caps: BackendCaps

    /** Load [pte] (weights baked; graph starts from normalized patches) into a runnable handle. */
    fun load(desc: ModelDescriptor, pte: File): LoadedModel

    /** Run one forward to `head_raw`. Blocking; the caller confines it to the inference thread. */
    fun run(m: LoadedModel, x: GraphTensors): GraphOutput

    /** Release native resources for [m]. */
    fun close(m: LoadedModel)
}
