package com.t1dm.calc

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.Precision

/**
 * One scored candidate in the ranked result (PLAN "ranked-candidate result"). [doseU] is the total
 * insulin; [splits] is present only for a split-bolus arrangement (the parts summing to [doseU]).
 */
data class Candidate(
    val doseU: Double,
    val score: Double,
    val fan: PredFan,
    val splits: List<SplitPart>? = null,
)

/** One part of a split bolus: [units] delivered [offsetMin] minutes after the first part. */
data class SplitPart(val units: Double, val offsetMin: Int)

/**
 * The §3.6-F point-of-decision cross-check card. It carries every decision-relevant fact to the
 * moment of choice; the UI gates Accept behind acknowledging it. [requiresConfirmation] is the *hard*
 * mandatory-confirmation flag (long log gap + nonzero dose); [confirmationReasons] and the always-on
 * acknowledgement are distinct — even a clean card must be acknowledged before Accept.
 */
data class DecisionCard(
    val ageOfLastRealReadingMin: Long?,
    val interpolatedFraction: Double,
    val warmup: Boolean,
    val backend: BackendId,
    val precision: Precision,
    val agreementOk: Boolean?,
    val assumedIobU: Double?,
    val minSinceLastLoggedDose: Long?,
    val bandWidthMgdl: Double?,
    val requiresConfirmation: Boolean,
    val confirmationReasons: List<String>,
)

/**
 * The outcome of a calculator run. Either the whole recommendation is [Refused] (a global rail
 * blocked — freshness / degeneracy), or a dose is [Recommended] with its ranked alternatives, the
 * decision card, and any rail notes. A [Recommended] result with [rescueCarbsG] set is the
 * hypo-treatment carb-rescue path (insulin is withheld).
 *
 * Note the deliberate absence of any "administer" / "deliver" / "actuate" member — this type is the
 * terminal output of the module and it only *advises*. The structural no-actuator test asserts this.
 */
sealed interface AdviceResult {

    /** A global fail-closed rail refused the recommendation; [reasons] are human-readable. */
    data class Refused(val reasons: List<String>) : AdviceResult

    /**
     * A ranked recommendation. [best] is the top candidate that survived the per-candidate rails;
     * [ranked] the full scored list (best-first) for the UI. [railNotes] records every rail decision.
     * [requiresConfirmation] mirrors the card. [rescueCarbsG] is non-null only on the hypo path.
     */
    data class Recommended(
        val best: Candidate,
        val ranked: List<Candidate>,
        val card: DecisionCard,
        val railNotes: List<String>,
        val requiresConfirmation: Boolean,
        val rescueCarbsG: Double? = null,
    ) : AdviceResult
}
