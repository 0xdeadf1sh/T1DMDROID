package com.t1dm.feature.models

/**
 * What the Lab screen draws. Everything here is the result of ONE run and is thrown away on the
 * next: nothing in the Lab is stored, pushed, or read by an alarm, a rail or a statistic.
 */

/** One masked span, in context-relative patch coordinates — what the user painted. */
data class LabSpan(val startPatch: Int, val patches: Int) {
    val endPatch: Int get() = startPatch + patches
}

/** One decoded span, ready to draw: its position on the grid and the fan the model gave it. */
data class LabFan(
    val startStep: Int,
    /** mg/dL per step, at the level the τ slider currently reads. */
    val line: List<Double>,
    /** The τ.05 and τ.95 edges — the fan's own outer bounds, whatever the slider says. */
    val lo: List<Double>,
    val hi: List<Double>,
    val isForecast: Boolean,
)

/**
 * A completed run. [tauLadder] holds the fan read at every fifth percentile, computed once in the
 * core: the slider picks a precomputed line rather than interpolating here, so the one
 * implementation of "read the fan at τ" stays in Rust where the fan was assembled.
 */
data class LabRun(
    val modelId: String,
    val synthetic: Boolean,
    val adapterName: String?,
    val statusNote: String?,
    val latencyMs: Double,
    /** Context BG per step, null where the step is masked or absent — the trace the model saw. */
    val contextBg: List<Double?>,
    val gridStartMs: Long,
    val stepMs: Long,
    val patchSize: Int,
    val nCtx: Int,
    /** Per masked span: the decoded rows, in ascending grid position. */
    val fans: List<LabFanLadder>,
)

/** One span's fans at every τ on the ladder, plus its outer edges. */
data class LabFanLadder(
    val startStep: Int,
    val isForecast: Boolean,
    val lo: List<Double>,
    val hi: List<Double>,
    /** τ → line, ascending in τ. */
    val ladder: List<Pair<Double, List<Double>>>,
) {
    /** The line nearest [tau] — the ladder's own step is the slider's resolution. */
    fun at(tau: Double): List<Double> =
        ladder.minByOrNull { kotlin.math.abs(it.first - tau) }?.second ?: emptyList()
}

/** The Lab's whole surface state. */
data class LabUiState(
    val models: List<String> = emptyList(),
    val modelId: String? = null,
    /** Context patches the selected model accepts, and the envelope its sampler ever drew. */
    val contextPatches: Int = 0,
    val maxMaskedPatches: Int = 0,
    val maxSpans: Int = 0,
    val maxSpanPatches: Int = 0,
    val synthetic: Boolean = false,
    val seed: Long = 1,
    val spans: List<LabSpan> = emptyList(),
    val withForecast: Boolean = true,
    val adapters: List<LabAdapter> = emptyList(),
    val adapterId: Long? = null,
    val tau: Double = 0.5,
    val running: Boolean = false,
    val run: LabRun? = null,
    /** Why the last attempt did not produce a run. */
    val error: String? = null,
    /** Real context available, in patches — what makes synthetic mode necessary or not. */
    val realPatches: Int = 0,
    /** The forecast span's length, from the model's own horizon — it costs head slots too. */
    val forecastPatches: Int = 0,
) {
    val maskedPatches: Int get() = spans.sumOf { it.patches }

    /** Empty when the masked set is inside everything the model was trained on. */
    val outOfDistribution: String?
        get() = when {
            spans.size > maxSpans -> "${spans.size} spans (trained to $maxSpans)"
            spans.any { it.patches > maxSpanPatches } -> "span over $maxSpanPatches patches"
            else -> null
        }

    /** Why Run is unavailable, or null when it is. */
    val blocked: String?
        get() = when {
            modelId == null -> "No model loaded"
            !synthetic && realPatches < contextPatches ->
                "Need $contextPatches patches of history, have $realPatches"
            spans.isEmpty() && !withForecast -> "Nothing masked"
            maskedPatches + (if (withForecast) forecastPatches else 0) > maxMaskedPatches ->
                "Over $maxMaskedPatches masked patches"
            else -> null
        }
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
)

/**
 * One gap in the measured signal, as the repair offers it. [filled] marks a gap this phone has
 * already reconstructed — a reconstruction is not evidence, so refilling one changes nothing but
 * which model's guess is stored.
 */
data class LabGap(
    val startMs: Long,
    val endMs: Long,
    val steps: Int,
    val label: String,
    val filled: Boolean,
)
