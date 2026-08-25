package com.t1dm.sensors

import com.t1dm.core.model.ExerciseKind
import kotlin.math.roundToInt

/** ACSM walking/running equations at grade 0: `VO2 = 0.1 * S + 3.5`, running `0.2 * S + 3.5`, `S` in
 *  m/min, VO2 in mL O2 per kg per minute. Gross, resting term left in. Display only: no rail,
 *  calculator, alarm or wire may read it. */
object ExerciseEnergy {

    /** Where the walking equation gives way to the running one, in m/min (6 km/h). */
    const val RUN_THRESHOLD_M_PER_MIN = 100.0

    /** m/min. 24 km/h, past marathon record pace: a bicycle, a vehicle or drift, not a gait. */
    const val MAX_RUN_M_PER_MIN = 400.0

    /** Caloric equivalent of oxygen at a mixed substrate — the standard indirect-calorimetry figure. */
    const val KCAL_PER_LITRE_O2 = 5.0

    /** Resting term of both equations, mL O2 per kg per minute; dropping it makes the figure net. */
    const val RESTING_VO2 = 3.5

    /** Scored per segment, never on the bout's average speed; a segment's speed is its metres over
     *  [ExerciseBucket.trackedMs], never over [ExerciseBucket.activeSec]. Null where no figure is
     *  justified: OTHER, no mass, more seconds dropped than scored, or no measured segment. */
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

    /** mL O2 per kg per metre: the equations' slope in `S`, which over a segment multiplies the
     *  metres rather than the speed. */
    private fun vo2PerMetre(speedMPerMin: Double): Double =
        if (speedMPerMin >= RUN_THRESHOLD_M_PER_MIN) 0.2 else 0.1
}
