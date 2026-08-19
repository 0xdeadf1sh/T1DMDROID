package com.t1dm.calc

import com.t1dm.core.model.ModelDescriptor
import com.t1dm.inference.backend.GraphTensors
import com.t1dm.inference.backend.GraphOutput

/**
 * A handle to the SELECTED fp32-authoritative model (SPEC §3.2). `:calc` scores on the selected model
 * only; the `:app` composition root supplies this from the [com.t1dm.inference.InferenceController]'s
 * currently-selected loaded model. [run] must confine the blocking backend forward to the single-thread
 * `inference` dispatcher (§2.3) — the [RollingForecaster] treats it as already-confined.
 */
interface SelectedModelHandle {
    val descriptor: ModelDescriptor
    val backendInfo: BackendInfo
    suspend fun run(input: GraphTensors): GraphOutput

    /**
     * `head_raw` with this model's attached adapter applied, or `null` when it runs frozen.
     *
     * A dose must be scored on the SAME forecaster the panel draws. An adapter that reached the
     * displayed and stored fan but not this one would put the calculator on a different model from
     * the one the patient is looking at — with nothing on either surface saying so.
     *
     * Returning `null` is the frozen model and the ordinary case. It must NOT be used to paper over
     * a failure: a model whose attached adapter cannot be applied throws, and the roll fails closed.
     */
    suspend fun adapt(out: GraphOutput, mSlots: Int): List<Double>? = null
}

/** Fail-closed provider of the selected model; null ⇒ no runnable model (the forecast is MISSING). */
fun interface SelectedModelProvider {
    suspend fun current(): SelectedModelHandle?
}
