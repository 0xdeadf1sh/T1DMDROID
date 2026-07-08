package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.Precision
import java.io.File
import java.nio.FloatBuffer

/**
 * The `InferenceBackend` seam (PLAN.private.md §3.2). One fixed-shape artifact (`T = 52`,
 * left-padded, external struct additive-float mask) drives interchangeable backends: a CPU fp32
 * XNNPACK reference/authority ([ExecuTorchXnnpackBackend]), Neuron/LiteRT NPU paths held as
 * documented stubs this phase, and a [StubBackend] fixed-output fallback so the whole pipeline
 * builds and runs even before a real `.pte` is pushed.
 *
 * The graph is cut at `head_raw (1,4,6,7)` in risk space; the `last_bg` anchor, DCT median
 * projection, quantile assembly, `f_inv`, and the degeneracy guard are all downstream in the
 * fp32/fp64 Rust `t1dm-core` — the fp16 tensors (when the NPU path lands) never touch the
 * risk-sensitive numerics.
 */

/** Static capabilities of a backend (PLAN.private.md §3.2). */
data class BackendCaps(
    val precision: Precision,
    /** The single exported sequence length: 48 context + 4 prediction patches. */
    val fixedSeqLen: Int = 52,
    /** The mask crosses as the external additive-float struct mask (never a bool mask). */
    val maskIsExternalStruct: Boolean = true,
)

/**
 * The fixed-shape graph input (PLAN.private.md §2.4). [patches] is `(1,52,18)` step-major,
 * already-normalized z-space; [mask] is the `(52,52)` additive-float struct mask (`0.0` attend /
 * `neg_fill` block). Both are **direct** NIO buffers positioned at 0 — ExecuTorch's
 * `Tensor.fromBlob(FloatBuffer, …)` requires a direct buffer and reads it by reference, so each
 * backend run consumes a freshly built [GraphInput] (never a shared/rewound one).
 */
class GraphInput(val patches: FloatBuffer, val mask: FloatBuffer)

/**
 * The graph output (PLAN.private.md §2.4). [headRaw] is the flattened `4·6·7 = 168` `head_raw`
 * in risk space, C-contiguous over `(P, S, C)` so the flat index is `(p·6 + s)·7 + c` — exactly
 * what `t1dm-core::assemble_decode` consumes. [timeLogits] is the optional diagnostic hour-of-day
 * probe; the graph is cut before it, so it is always `null` here.
 */
class GraphOutput(val headRaw: FloatArray, val timeLogits: FloatArray? = null)

/** An opaque backend-loaded model handle; closed via [InferenceBackend.close]. */
interface LoadedModel {
    val id: String
    val caps: BackendCaps
}

/** A pluggable inference backend. [run] is blocking and MUST be called off-main (the single-thread
 *  `inference` dispatcher, PLAN.private.md §2.3). */
interface InferenceBackend {
    val id: BackendId
    val caps: BackendCaps

    /** Load [pte] (weights baked; graph starts from normalized patches) into a runnable handle. */
    fun load(desc: ModelDescriptor, pte: File): LoadedModel

    /** Run one forward to `head_raw`. Blocking; the caller confines it to the inference thread. */
    fun run(m: LoadedModel, x: GraphInput): GraphOutput

    /** Release native resources for [m]. */
    fun close(m: LoadedModel)
}
