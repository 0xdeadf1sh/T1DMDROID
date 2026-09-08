package com.t1dm.sensors

import com.t1dm.core.model.ExerciseKind
import kotlin.math.roundToInt

/** ACSM grade-0: VO2=0.1S+3.5, run 0.2S+3.5 (m/min,mL/kg/min,gross); display-only, no rail/calc. */
object ExerciseEnergy {

    /** Where the walking equation gives way to the running one, in m/min (6 km/h). */
    const val RUN_THRESHOLD_M_PER_MIN = 100.0

    /** m/min. 24 km/h, past marathon record pace: a bicycle, a vehicle or drift, not a gait. */
    const val MAX_RUN_M_PER_MIN = 400.0

    /** Caloric equivalent of O2 at a mixed substrate — the standard indirect-calorimetry figure. */
    const val KCAL_PER_LITRE_O2 = 5.0

    /** Resting term of both equations, mL O2/kg/min; dropping it makes the figure net. */
    const val RESTING_VO2 = 3.5

    /** Per-segment, never bout avg; speed=m/trackedMs; null if OTHER/no mass/mostly-dropped. */
    fun kcal(kind: ExerciseKind, segments: Iterable<ExerciseBucket>, bodyMassKg: Double?): Int? {
        if (kind == ExerciseKind.OTHER) return null
        val mass = bodyMassKg ?: return null
        if (!mass.isFinite() || mass <= 0.0) return null

        var vo2MlPerKg = 0.0
        var measured = false
        var scoredSec = 0
        var droppedSec = 0
        for (s in segments) {
            val metres = s.distanceM ?: continue
            if (!metres.isFinite() || metres <= 0.0 || s.trackedMs <= 0L) continue
            val speedMPerMin = metres / (s.trackedMs / 60_000.0)
            val openSec = s.activeSec.coerceAtLeast(0)
            if (speedMPerMin > MAX_RUN_M_PER_MIN) {
                droppedSec += openSec
                continue
            }
            vo2MlPerKg += vo2PerMetre(speedMPerMin) * metres + RESTING_VO2 * (openSec / 60.0)
            scoredSec += openSec
            measured = true
        }
        if (!measured || droppedSec > scoredSec) return null
        return (vo2MlPerKg * mass / 1000.0 * KCAL_PER_LITRE_O2).roundToInt()
    }

    /** mL O2/kg/metre: equations' slope in S; over a segment multiplies metres, not speed. */
    private fun vo2PerMetre(speedMPerMin: Double): Double =
        if (speedMPerMin >= RUN_THRESHOLD_M_PER_MIN) 0.2 else 0.1
}
