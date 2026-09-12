package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import java.io.File
import java.nio.FloatBuffer

data class BackendCaps(
    /** Additive-float struct mask, never a bool one; one-hot selection, never an int64 index. */
    val maskIsExternalStruct: Boolean = true,
)

/** Direct buffers at 0: patches(1,T,PATCH_DIM) z-space, mask(T,T) additive, slotSel(M,T) 1-hot. */
class GraphTensors(
    val patches: FloatBuffer,
    val mask: FloatBuffer,
    val slotSel: FloatBuffer,
    val t: Int,
    val patchDim: Int,
    val mSlots: Int,
)

/** headRaw flat M*S*7 risk-space, C-contig (slot,step,level); timeLogits/hidden null if unset. */
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
