package com.t1dm.calc

import com.t1dm.core.model.BackendId
import com.t1dm.inference.InferenceControllerDefaults

/** [doseU] is the TOTAL insulin; [splits], when present, sums back to it. */
data class Candidate(
    val doseU: Double,
    val score: Double,
    val fan: PredFan,
    val splits: List<SplitPart>? = null,
)

/** [offsetMin] is measured from the first part. */
data class SplitPart(val units: Double, val offsetMin: Int)

/** §3.6-F: [requiresConfirmation] is the hard rail flag; even a clean card needs Accept ack. */
data class DecisionCard(
    val ageOfLastRealReadingMin: Long?,
    val interpolatedFraction: Double,
    val warmup: Boolean,
    val backend: BackendId,
    val assumedIobU: Double?,
    val minSinceLastLoggedDose: Long?,
    val bandWidthMgdl: Double?,
    /** Samples. INFERENCE.md §7.1. */
    val smoothingWindow: Int,
    val requiresConfirmation: Boolean,
    val confirmationReasons: List<String>,
) {
    companion object {
        /** Re-exported so `:feature:insulin` need not depend on `:inference`. */
        const val DEFAULT_SMOOTHING_WINDOW = InferenceControllerDefaults.SAVGOL_WINDOW
    }
}

/** Terminal output; no "administer"/"deliver"/"actuate" member, per the no-actuator test. */
sealed interface AdviceResult {

    data class Refused(val reasons: List<String>) : AdviceResult

    /** [ranked] is best-first. [rescueCarbsG] is non-null only on the hypo-treatment path. */
    data class Recommended(
        val best: Candidate,
        val ranked: List<Candidate>,
        val card: DecisionCard,
        val railNotes: List<String>,
        val requiresConfirmation: Boolean,
        val rescueCarbsG: Double? = null,
    ) : AdviceResult
}
