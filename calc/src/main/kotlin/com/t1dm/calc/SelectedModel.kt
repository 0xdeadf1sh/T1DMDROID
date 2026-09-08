package com.t1dm.calc

import com.t1dm.core.model.GraphInput
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.inference.backend.GraphTensors
import com.t1dm.inference.backend.GraphOutput

/** SPEC §3.2: run MUST confine the blocking forward to the single-thread inference dispatcher. */
interface SelectedModelHandle {
    val descriptor: ModelDescriptor
    val backendInfo: BackendInfo
    suspend fun run(input: GraphTensors): GraphOutput

    /** head_raw with adapter applied; null=frozen, never papers over a failed adapter (throws). */
    suspend fun adapt(out: GraphOutput, gi: GraphInput): List<Double>? = null
}

/** null ⇒ no runnable model, and the forecast is MISSING. */
fun interface SelectedModelProvider {
    suspend fun current(): SelectedModelHandle?
}
