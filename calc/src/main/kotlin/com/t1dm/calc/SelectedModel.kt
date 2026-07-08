package com.t1dm.calc

import com.t1dm.core.model.ModelDescriptor
import com.t1dm.inference.backend.GraphInput
import com.t1dm.inference.backend.GraphOutput

/**
 * A handle to the SELECTED fp32-authoritative model (PLAN §3.2). `:calc` scores on the selected model
 * only; the `:app` composition root supplies this from the [com.t1dm.inference.InferenceController]'s
 * currently-selected loaded model. [run] must confine the blocking backend forward to the single-thread
 * `inference` dispatcher (§2.3) — the [RollingForecaster] treats it as already-confined.
 */
interface SelectedModelHandle {
    val descriptor: ModelDescriptor
    val backendInfo: BackendInfo
    suspend fun run(input: GraphInput): GraphOutput
}

/** Fail-closed provider of the selected model; null ⇒ no runnable model (the forecast is MISSING). */
fun interface SelectedModelProvider {
    suspend fun current(): SelectedModelHandle?
}
