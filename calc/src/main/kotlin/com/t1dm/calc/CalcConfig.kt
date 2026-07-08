package com.t1dm.calc

/**
 * The user-configurable policy for the dose calculators (PLAN.private.md §3.6, Phase 4
 * deliverable 5/6). Every numeric threshold here is **user-set and deliberately UNBOUNDED** —
 * the user overrode the compiled ceiling, and the advisory-only + manual-administration model is
 * the terminal safety net (safety-posture.md). The *rails* still exist and still fail closed; a
 * threshold merely tunes where an enabled rail trips.
 *
 * Nothing in this module actuates insulin. A [CalcConfig] only shapes which candidate dose the
 * grid search *recommends* to the human.
 */

/** The user's glycaemic goal in mg/dL. [target] is the single-point aim for HitTargetAtTime. */
data class TargetRange(
    val lowMgdl: Double = 70.0,
    val highMgdl: Double = 180.0,
    val targetMgdl: Double = 110.0,
)

/** The selectable scoring objective for the forecast fan (PLAN Phase 4 §5). */
sealed interface Objective {
    /** Minimise the horizon-weighted count of steps predicted out of [TargetRange]. */
    data object MinTimeOutOfRange : Objective

    /** Minimise horizon-weighted Kovatchev blood-glucose risk (LBGI off the lower band + HBGI). */
    data object MinKovatchevRisk : Objective

    /** Drive the median to [TargetRange.targetMgdl] at [atMsFromNow] ms past the roll start. */
    data class HitTargetAtTime(val atMsFromNow: Long) : Objective
}

/**
 * Relative penalties for the two failure directions (PLAN "configurable hypo/hyper asymmetry").
 * Unbounded and independent: the user may punish hypo far harder than hyper, or vice-versa. Hypo
 * is always scored off the **lower** quantile band, hyper off the median (§ 5h-roll finding).
 */
data class Asymmetry(
    val hypoWeight: Double = 3.0,
    val hyperWeight: Double = 1.0,
)

/**
 * Per-rail enable switches (safety-posture.md — "guard-rail toggles exist"). A disabled rail is a
 * no-op; an **enabled** rail always fails closed on missing / degenerate / stale / collapsed input
 * (PLAN §3.6-C). [degeneracyGate] is intentionally not disableable through the normal path — scoring
 * a degenerate fan is meaningless — and is enforced structurally regardless of this flag.
 */
data class RailToggles(
    val freshnessGate: Boolean = true,
    val predictedLowVeto: Boolean = true,
    val iobCeiling: Boolean = true,
    val mandatoryConfirmation: Boolean = true,
    val hypoTreatment: Boolean = true,
) {
    companion object {
        /** All optional rails disabled — the "all-rails-off = identity" CI invariant (PLAN Phase 4 §7). */
        val ALL_OFF = RailToggles(
            freshnessGate = false,
            predictedLowVeto = false,
            iobCeiling = false,
            mandatoryConfirmation = false,
            hypoTreatment = false,
        )
    }
}

/** The bounded candidate grid for the bolus search. [maxU] is user-set and unbounded. */
data class GridSpec(
    val minU: Double = 0.0,
    val maxU: Double = 15.0,
    val stepU: Double = 0.5,
) {
    init {
        require(stepU > 0.0) { "grid step must be > 0" }
        require(maxU >= minU) { "grid maxU < minU" }
    }

    /** The candidate doses, always including a 0 U baseline (the do-nothing counterfactual). */
    fun doses(): List<Double> {
        val out = sortedSetOf(0.0)
        var d = minU
        // Guard against a runaway grid; the user's unbounded maxU is honoured but capped in count.
        var n = 0
        while (d <= maxU + 1e-9 && n < MAX_CANDIDATES) {
            out.add(((d * 1e6).toLong() / 1e6)) // de-noise fp accumulation
            d += stepU; n++
        }
        return out.toList()
    }

    companion object {
        const val MAX_CANDIDATES = 512
    }
}

/**
 * The split-bolus search envelope (PLAN Phase 4 §6 — "coarse fraction × gap grid under a
 * configurable cap"). [maxParts] and [maxGapMin] are the user's cap; [gapGridMin] the coarse gaps.
 */
data class SplitSpec(
    val enabled: Boolean = true,
    val maxParts: Int = 2,
    val gapGridMin: List<Int> = listOf(30, 45, 60, 90),
    val firstFractionGrid: List<Double> = listOf(0.5, 0.6, 0.7),
)

/**
 * The horizon-weighting policy (PLAN "§ 5h-roll finding"). Contributions beyond the validated
 * [predictionHorizonHours] are discounted to [beyondWindowWeight] when *selecting* a dose — the
 * far, self-fed median is the least reliable and carry_spread widening is a heuristic, not a
 * calibrated quantile. The full [fullRollHours] roll is still produced for context.
 */
data class HorizonPolicy(
    val predictionHorizonHours: Double = 2.0,
    val fullRollHours: Double = 5.0,
    val beyondWindowWeight: Double = 0.15,
) {
    val validatedSteps: Int get() = (predictionHorizonHours * STEPS_PER_HOUR).toInt()
    val fullRollSteps: Int get() = (fullRollHours * STEPS_PER_HOUR).toInt()

    companion object {
        const val STEPS_PER_HOUR = 12 // 5-min grid
    }
}

/**
 * The complete calculator policy. All thresholds are user-set and unbounded; the rails they tune
 * still fail closed (PLAN §3.6-C). Conservative *defaults* per open-Q10 sign-off.
 */
data class CalcConfig(
    val target: TargetRange = TargetRange(),
    val objective: Objective = Objective.MinKovatchevRisk,
    val asymmetry: Asymmetry = Asymmetry(),
    val rails: RailToggles = RailToggles(),
    val grid: GridSpec = GridSpec(),
    val split: SplitSpec = SplitSpec(),
    val horizon: HorizonPolicy = HorizonPolicy(),
    // ── Rail thresholds (user-set, UNBOUNDED) ──────────────────────────────────────────
    /** §3.6-D: refuse a recommendation when the last MEASURED reading is older than this. */
    val freshnessMaxAgeMs: Long = 15 * 60_000L,
    /** §3.6-D: refuse when more than this fraction of the recent anchor context is interpolated/warmup. */
    val maxInterpolatedFraction: Double = 0.34,
    /** Predicted-low veto: block a dose whose lower band dips below this within the full roll. */
    val predictedLowThresholdMgdl: Double = 70.0,
    /** IOB ceiling: block when assumed IOB + candidate dose exceeds this. */
    val iobCeilingU: Double = 12.0,
    /** §3.6-F: a nonzero recommendation with the last logged dose older than this is mandatory-confirm. */
    val longGapSinceLogMs: Long = 3 * 60 * 60_000L,
    /** Hypo-treatment path: current or near-term predicted BG below this switches to carb rescue. */
    val hypoNowThresholdMgdl: Double = 70.0,
    /** Rescue carbs are dosed to lift BG by ~[rescueTargetLiftMgdl] at the assumed [carbSensitivityMgdlPerG]. */
    val rescueTargetLiftMgdl: Double = 50.0,
    val carbSensitivityMgdlPerG: Double = 3.0,
)
