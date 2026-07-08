package com.t1dm.inference.backend

import com.t1dm.core.model.BuiltContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Assembles the fixed `T = 52` graph input from a Rust [BuiltContext] and builds the pad-aware
 * struct mask. This is the runtime half of the §2.4 contract, and it must reproduce the exporter's
 * construction bit-for-bit — the golden `.pte` was traced and verified against exactly this layout
 * (`T1DMAI/exporters/modified_forward.py::build_struct_mask` + the left-pad in
 * `executorch_xnnpack.py::build_representative_input`).
 *
 * **Left-pad, load-bearing.** RoPE is absolute-position dependent, so the real context must occupy
 * the *last* `n_ctx` of the 48 context slots (prediction patches at the right edge, positions
 * 48..51); a shorter unpadded sequence would yield different RoPE phases than training (exporter
 * finding #1). The leading `48 − n_ctx` pad patches are zeros — the struct mask blocks every pad
 * COLUMN, so their values never reach a prediction token.
 */
object GraphIo {
    const val T = 52
    const val MAX_CTX = 48
    const val PRED = 4
    const val PATCH_DIM = 18
    private const val ATTEND = 0.0f

    /** Allocate a native-order direct [FloatBuffer] (ExecuTorch requires direct buffers). */
    fun directFloats(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * java.lang.Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /**
     * Build the `(1,52,18)` patches + `(52,52)` struct mask for [ctx]. The buffers are single-use
     * (consumed by `Tensor.fromBlob`), so call this once per model per cycle. [negFill] comes from
     * the descriptor (`-30000.0`), never `-inf`, for fp16-safe softmax.
     */
    fun graphInput(ctx: BuiltContext, negFill: Double): GraphInput {
        val nCtx = ctx.nCtx
        require(nCtx in 1..MAX_CTX) { "n_ctx $nCtx outside [1,$MAX_CTX]" }
        require(ctx.context.size == nCtx * PATCH_DIM) {
            "context ${ctx.context.size} != n_ctx·PATCH_DIM ${nCtx * PATCH_DIM}"
        }
        require(ctx.pred.size == PRED * PATCH_DIM) {
            "pred ${ctx.pred.size} != PRED·PATCH_DIM ${PRED * PATCH_DIM}"
        }
        val pad0 = MAX_CTX - nCtx
        val patches = directFloats(T * PATCH_DIM)
        // [pad0 zero patches] [n_ctx real context] [PRED prediction patches] — exporter layout.
        repeat(pad0 * PATCH_DIM) { patches.put(0f) }
        for (v in ctx.context) patches.put(v.toFloat())
        for (v in ctx.pred) patches.put(v.toFloat())
        patches.rewind()
        return GraphInput(patches, buildStructMask(nCtx, negFill.toFloat()))
    }

    /**
     * The pad-aware additive struct mask, row-major `(T,T)`, `0.0` attend / [negFill] block —
     * an exact port of `modified_forward.build_struct_mask`. Allowed blocks mirror
     * `create_attention_mask` on the real sub-window: ctx↔ctx bidirectional, pred→ctx full,
     * pred↔pred bidirectional, ctx→pred blocked; every pad column blocked; pad rows may attend to
     * the real context purely so their softmax is never fully-masked (their outputs are discarded,
     * since the head reads only the last `P` tokens).
     */
    fun buildStructMask(nCtx: Int, negFill: Float): FloatBuffer {
        require(nCtx in 1..MAX_CTX) { "n_ctx $nCtx outside [1,$MAX_CTX]" }
        val c = MAX_CTX
        val pad0 = c - nCtx
        val arr = FloatArray(T * T) { negFill }
        fun attend(rLo: Int, rHi: Int, cLo: Int, cHi: Int) {
            for (r in rLo until rHi) {
                val base = r * T
                for (col in cLo until cHi) arr[base + col] = ATTEND
            }
        }
        attend(pad0, c, pad0, c)   // ctx <-> ctx (bidirectional)
        attend(c, T, pad0, c)      // pred -> ctx (full)
        attend(c, T, c, T)         // pred <-> pred (bidirectional)
        if (pad0 > 0) attend(0, pad0, pad0, c) // pad rows -> ctx (anti-NaN; outputs discarded)
        val buf = directFloats(T * T)
        buf.put(arr)
        buf.rewind()
        return buf
    }
}
