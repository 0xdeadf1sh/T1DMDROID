package com.t1dm.feature.twin

import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinTrait
import kotlin.math.exp
import kotlin.random.Random

/** Stand-in for the encrypted pipeline: a bounded random walk from the previous scan. */
object MockTwin {
    private val BASE = mapOf(
        TwinTrait.AGE to 34.0,
        TwinTrait.SEX to 0.8,
        TwinTrait.HEIGHT to 178.0,
        TwinTrait.WEIGHT to 76.0,
        TwinTrait.MUSCLE to 0.55,
        TwinTrait.BODY_FAT to 19.0,
    )

    private val STEP = mapOf(
        TwinTrait.AGE to 0.8,
        TwinTrait.SEX to 0.02,
        TwinTrait.HEIGHT to 0.3,
        TwinTrait.WEIGHT to 0.9,
        TwinTrait.MUSCLE to 0.03,
        TwinTrait.BODY_FAT to 0.8,
    )

    private val HORIZON_Y = mapOf(
        TwinTrait.SURVIVAL_1Y to 1.0,
        TwinTrait.SURVIVAL_5Y to 5.0,
        TwinTrait.SURVIVAL_10Y to 10.0,
        TwinTrait.SURVIVAL_20Y to 20.0,
    )

    fun next(previous: TwinScan?, rng: Random): Map<String, Double> {
        val body = BASE.mapValues { (trait, base) ->
            val from = previous?.get(trait) ?: base
            (from + (rng.nextDouble() * 2 - 1) * STEP.getValue(trait)).coerceIn(trait.min, trait.max)
        }
        val age = body.getValue(TwinTrait.AGE)
        val fat = body.getValue(TwinTrait.BODY_FAT)
        val survival = HORIZON_Y.mapValues { (_, years) -> gompertzSurvival(age, years, fat) }
        return (body + survival).mapKeys { it.key.key }
    }

    /** Gompertz S(t | a) = exp(−(A/B)(e^{B(a+t)} − e^{Ba})); A scaled by body fat, mock only. */
    internal fun gompertzSurvival(ageY: Double, years: Double, bodyFatPct: Double): Double {
        val a = GOMPERTZ_A * exp(0.02 * (bodyFatPct - 20.0))
        return exp(-(a / GOMPERTZ_B) * (exp(GOMPERTZ_B * (ageY + years)) - exp(GOMPERTZ_B * ageY)))
    }

    private const val GOMPERTZ_A = 5e-5
    private const val GOMPERTZ_B = 0.085
}
