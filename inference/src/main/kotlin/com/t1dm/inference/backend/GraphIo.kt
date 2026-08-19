package com.t1dm.inference.backend

import com.t1dm.core.model.GraphInput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Copies a Rust-built [GraphInput] into the direct NIO buffers the runtime requires.
 *
 * There is no geometry here and there must not be. The left-pad, the masked-patch fill, the
 * announcement bit, the attention rule and the slot selection are all built once in
 * `t1dm-core::build_graph_input`, against the exporter's own construction; a second
 * transcription on this side is exactly the copy that drifts, and it drifts silently — every
 * shape still matches and every fan is still monotone.
 */
object GraphIo {
    /** Allocate a native-order direct [FloatBuffer] (the runtime requires direct buffers). */
    fun directFloats(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * java.lang.Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /**
     * The three graph tensors, freshly allocated. `Tensor.fromBlob` reads a direct buffer BY
     * REFERENCE, so each run consumes its own [GraphTensors] — never a shared or rewound one.
     */
    fun tensors(gi: GraphInput): GraphTensors {
        require(gi.patches.size == gi.t * gi.patchDim) {
            "patches ${gi.patches.size} != T·PATCH_DIM ${gi.t * gi.patchDim}"
        }
        require(gi.attnMask.size == gi.t * gi.t) {
            "attn_mask ${gi.attnMask.size} != T² ${gi.t * gi.t}"
        }
        require(gi.slotSel.size == gi.mSlots * gi.t) {
            "slot_sel ${gi.slotSel.size} != M·T ${gi.mSlots * gi.t}"
        }
        fun buf(src: FloatArray): FloatBuffer = directFloats(src.size).apply { put(src); rewind() }
        return GraphTensors(
            patches = buf(gi.patches),
            mask = buf(gi.attnMask),
            slotSel = buf(gi.slotSel),
            t = gi.t,
            patchDim = gi.patchDim,
            mSlots = gi.mSlots,
        )
    }
}
