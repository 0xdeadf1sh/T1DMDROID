package com.t1dm.calc

import com.t1dm.core.model.GraphInput
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.inference.backend.GraphTensors
import com.t1dm.inference.backend.GraphOutput

/** SPEC §3.2. [run] MUST confine the blocking backend forward to the single-thread `inference`
 *  dispatcher (§2.3); [RollingForecaster] treats it as already confined. */
interface SelectedModelHandle {
    val descriptor: ModelDescriptor
    val backendInfo: BackendInfo
    suspend fun run(input: GraphTensors): GraphOutput

    /** `head_raw` with this model's attached adapter applied; null ⇒ it runs frozen. Null must NOT
     *  paper over a failure: an attached adapter that cannot be applied throws. */
    suspend fun adapt(out: GraphOutput, gi: GraphInput): List<Double>? = null
}

/** null ⇒ no runnable model, and the forecast is MISSING. */
fun interface SelectedModelProvider {
    suspend fun current(): SelectedModelHandle?
}
