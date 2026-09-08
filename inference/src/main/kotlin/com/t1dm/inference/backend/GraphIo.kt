package com.t1dm.inference.backend

import com.t1dm.core.model.GraphInput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Copies [GraphInput] into direct NIO buffers; no geometry here — all in build_graph_input. */
object GraphIo {
    /** Direct and native-order; the runtime accepts nothing else. */
    fun directFloats(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * java.lang.Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** `Tensor.fromBlob` reads a buffer BY REFERENCE; each run needs its own [GraphTensors]. */
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
