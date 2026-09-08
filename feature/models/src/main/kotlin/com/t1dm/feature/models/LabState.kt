package com.t1dm.feature.models

/** Nothing here is stored, pushed, or read by an alarm/rail/statistic/fit target; thrown away. */
data class LabSynth(
    val seed: Long,
    val gridStartMs: Long,
    val stepMs: Long,
    /** mg/dL per step. */
    val bg: List<Double>,
    /** True where the step came from the phone's own record; the generator fills only the gaps. */
    val real: List<Boolean>,
    val carb: List<Double>,
    val insulin: List<Double>,
    val exercise: List<Double>,
) {
    val syntheticSteps: Int get() = real.count { !it }
}

data class LabUiState(
    val models: List<String> = emptyList(),
    val modelId: String? = null,
    /** Patches the selected model accepts — the length a generated trace is cut to. */
    val contextPatches: Int = 0,
    /** Real context available, in patches. */
    val realPatches: Int = 0,
    val seed: Long = 1,
    val adapters: List<LabAdapter> = emptyList(),
    val generating: Boolean = false,
    val generated: LabSynth? = null,
    val error: String? = null,
) {
    val blocked: String? get() = if (modelId == null) "No model loaded" else null
}

data class LabAdapter(
    val id: Long,
    val modelId: String,
    val name: String,
    val rank: Int,
    val nParams: Int,
    val nTrain: Int,
    val nHoldout: Int,
    val holdoutBefore: Double,
    val holdoutAfter: Double,
    val improved: Boolean,
    val attached: Boolean,
    val updatedAtMs: Long,
    /** Null when it may attach; same predicate as LabController.attach so button/gate agree. */
    val attachRefusal: String? = null,
    /** Marginal dose response kept, as a ratio in risk space; 1.0 is preservation. */
    val guardRetention: Double = 0.0,
    val guardWindows: Int = 0,
    /** mg/dL per unit at the horizon, frozen model and adapted. */
    val guardFrozenMgdl: Double = 0.0,
    val guardAdaptedMgdl: Double = 0.0,
    val guardOverridden: Boolean = false,
)

