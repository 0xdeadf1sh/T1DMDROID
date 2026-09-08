package com.t1dm.calc

/** Every threshold is user-set and UNBOUNDED (§3.6); a rail fails closed, threshold tunes where. */

data class TargetRange(
    val lowMgdl: Double = 70.0,
    val highMgdl: Double = 180.0,
    val targetMgdl: Double = 110.0,
)

sealed interface Objective {
    data object MinTimeOutOfRange : Objective

    data object MinKovatchevRisk : Objective

    /** [atMsFromNow] is measured from the roll start. */
    data class HitTargetAtTime(val atMsFromNow: Long) : Objective

    /** Median hypo term, so protection doesn't rest on the disableable predicted-low veto. */
    data class HitTargetBg(val targetMgdl: Double) : Objective
}

/** Both directions score off the median; these weights are the whole hypo/hyper preference. */
data class Asymmetry(
    val hypoWeight: Double = 3.0,
    val hyperWeight: Double = 1.0,
)

/** Disabled rail is a no-op, enabled fails closed (§3.6-C); baselineDegeneracy not toggleable. */
data class RailToggles(
    val predictedLowVeto: Boolean = true,
    val iobCeiling: Boolean = true,
    val mandatoryConfirmation: Boolean = true,
    val hypoTreatment: Boolean = true,
) {
    companion object {
        val ALL_OFF = RailToggles(
            predictedLowVeto = false,
            iobCeiling = false,
            mandatoryConfirmation = false,
            hypoTreatment = false,
        )
    }
}

data class GridSpec(
    val minU: Double = 0.0,
    val maxU: Double = 15.0,
    val stepU: Double = 0.5,
) {
    init {
        require(stepU > 0.0) { "grid step must be > 0" }
        require(maxU >= minU) { "grid maxU < minU" }
    }

    /** Units. Always includes the 0 U baseline. */
    fun doses(): List<Double> {
        val out = sortedSetOf(0.0)
        var d = minU
        // An unbounded maxU is honoured but capped in count.
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

data class SplitSpec(
    val enabled: Boolean = true,
    val maxParts: Int = 2,
    val gapGridMin: List<Int> = listOf(30, 45, 60, 90),
    val firstFractionGrid: List<Double> = listOf(0.5, 0.6, 0.7),
)

/** Past predictionHorizonHours the median is self-fed, uncalibrated; discounted for dosing. */
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

data class CalcConfig(
    val target: TargetRange = TargetRange(),
    val objective: Objective = Objective.MinKovatchevRisk,
    val asymmetry: Asymmetry = Asymmetry(),
    val rails: RailToggles = RailToggles(),
    val grid: GridSpec = GridSpec(),
    val split: SplitSpec = SplitSpec(),
    val horizon: HorizonPolicy = HorizonPolicy(),
    /** Vetoes a dose whose MEDIAN drops below this inside the VALIDATED window. */
    val predictedLowThresholdMgdl: Double = 70.0,
    val iobCeilingU: Double = 12.0,
    /** §3.6-F: a nonzero dose with the last logged dose older than this is mandatory-confirm. */
    val longGapSinceLogMs: Long = 3 * 60 * 60_000L,
    val hypoNowThresholdMgdl: Double = 70.0,
    val rescueTargetLiftMgdl: Double = 50.0,
    val carbSensitivityMgdlPerG: Double = 3.0,
)
