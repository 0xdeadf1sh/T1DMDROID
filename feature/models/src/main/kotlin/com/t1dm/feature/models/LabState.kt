package com.t1dm.feature.models

/**
 * What the Lab screen draws. Everything here is the result of ONE generate and is thrown away on
 * the next: nothing in the Lab is stored, pushed, or read by an alarm, a rail or a statistic.
 */

/**
 * One generated synthetic trace — the Lab's whole output.
 *
 * The Lab generates and nothing else. Reconstruction, promotion and the τ sweep all live on the BG
 * panel now, where the curve being changed is the one on screen; keeping a second surface that
 * could also write a fill meant two places to look for where one came from.
 *
 * Nothing here is stored, pushed, or read by an alarm, a rail, a statistic or a fit target. It is a
 * synthetic patient drawn on a chart, thrown away on the next generate.
 */
data class LabSynth(
    val seed: Long,
    val gridStartMs: Long,
    val stepMs: Long,
    /** mg/dL per step. */
    val bg: List<Double>,
    /** Which steps came from this phone's own record instead of the generator — the generator fills
     *  only what the history LACKS, so a trace on a well-covered phone is mostly real. */
    val real: List<Boolean>,
    val carb: List<Double>,
    val insulin: List<Double>,
    val exercise: List<Double>,
) {
    val syntheticSteps: Int get() = real.count { !it }
}

/** The Lab's whole surface state. */
data class LabUiState(
    val models: List<String> = emptyList(),
    val modelId: String? = null,
    /** Context patches the selected model accepts — the length a generated trace is cut to. */
    val contextPatches: Int = 0,
    /** Real context available, in patches — how much of a trace the generator will NOT invent. */
    val realPatches: Int = 0,
    val seed: Long = 1,
    val adapters: List<LabAdapter> = emptyList(),
    val generating: Boolean = false,
    val generated: LabSynth? = null,
    /** Why the last attempt produced nothing. */
    val error: String? = null,
) {
    /** Why Generate is unavailable, or null when it is. */
    val blocked: String? get() = if (modelId == null) "No model loaded" else null
}

/** An adapter as the Lab and the adapter panel list it. */
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
    /**
     * Why this adapter may not be attached, or null when it may.
     *
     * Resolved once, by the same pure predicate `LabController.attach` enforces, so the button and
     * the gate cannot disagree. The panel renders it; it does not decide it.
     */
    val attachRefusal: String? = null,
    /** How much of the model's marginal dose response the adapter kept — 1.0 is preservation.
     *  A ratio in risk space; the pair below is the same responses in the units a dose is read in. */
    val guardRetention: Double = 0.0,
    val guardWindows: Int = 0,
    /** mg/dL per unit at the horizon, frozen model and adapted. Shown beside the ratio, because a
     *  ratio alone hides which of the two moved. */
    val guardFrozenMgdl: Double = 0.0,
    val guardAdaptedMgdl: Double = 0.0,
    /** True once the user has deliberately overridden a refusal on this row. */
    val guardOverridden: Boolean = false,
)

