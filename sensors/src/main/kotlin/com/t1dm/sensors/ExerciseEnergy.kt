package com.t1dm.sensors

import com.t1dm.core.model.ExerciseKind
import kotlin.math.roundToInt

/**
 * Gross energy expenditure for a recorded bout, from the ACSM metabolic equations.
 *
 * The suite carries no heart-rate source (`docs/WATCH_BLE.md` — the watch is a display, it never
 * transmits to the phone) and stores no anthropometrics, so the only honest inputs are the bout's own
 * GPS-measured speed and a body mass the user supplies. Absent either, there is no figure: this
 * returns null and the panel says so, on the `AppContainer.sensitivity` precedent. It is display
 * only — no rail, no calculator, no alarm, no wire may read it.
 *
 * The equations (ACSM's *Guidelines for Exercise Testing and Prescription*), at grade 0 because GPS
 * altitude is far too noisy to differentiate and no barometer is read:
 *
 * ```
 * walking  VO2 = 0.1 * S + 3.5      (S < 100 m/min)
 * running  VO2 = 0.2 * S + 3.5      (S >= 100 m/min)
 * ```
 *
 * with `S` the speed in m/min and `VO2` in mL O2 per kg per minute. Energy follows from the standard
 * caloric equivalent of oxygen, [KCAL_PER_LITRE_O2] kcal per litre consumed:
 *
 * ```
 * kcal = VO2 * massKg / 1000 * KCAL_PER_LITRE_O2 * minutes
 * ```
 *
 * **They are equations for two gaits and nothing else.** Both were fitted to a body carrying its own
 * mass over ground, so speed alone cannot stand in for the activity: a cycle at 20 km/h reads as a
 * 333 m/min run and comes out at roughly 2.5x the energy it actually costs, because a rider's mass is
 * on the bicycle. The bout's own [ExerciseKind] is therefore an input, and [ExerciseKind.OTHER] — the
 * label that covers exactly the activities these equations do not describe — gets no figure at all.
 * That is the same refusal a missing body mass gets, for the same reason.
 *
 * **A bout is scored segment by segment, never on its average speed.** The segments are the bout's own
 * five-minute buckets, which already carry the metres, the seconds and the interval the metres were
 * measured over. Both equations are linear in speed, so for a bout held inside one gait the sum is
 * identical to scoring the whole bout at once — summing `0.1 * d_i` is summing the metres. What the
 * average destroys is everything else: a WALK bout left running through a car journey — 800 m walked
 * in 10 min, then 8 km driven in 15 — averages 352 m/min, under [MAX_RUN_M_PER_MIN], and the running
 * equation then states roughly 650 kcal for a walk that cost about 50. Per segment the drive is
 * 533 m/min, reaches no gait, and is dropped. Nothing recomputes a stored kcal, so a figure written on
 * that average is permanent.
 *
 * **A segment's speed is its metres over [ExerciseBucket.trackedMs], never over its own seconds.**
 * Those are different intervals: a GPS segment straddling a bucket boundary is charged whole to the
 * bucket the fix arrives in, whose seconds start at that boundary. A first fix landing one second into
 * a new bucket leaves 13 m of running against 1 s — 800 m/min read as a speed, a vehicle read as a
 * run, and the figure withdrawn mid-bout. [ExerciseBucket.trackedMs] is the interval those metres were
 * really covered over, and only that ratio is a speed.
 *
 * Speed is needed for two things and nothing else: which gait's equation applies, and whether
 * [MAX_RUN_M_PER_MIN] is exceeded. The energy never divides, because `VO2 * minutes` expands into a
 * distance term and a resting term — `0.1 * d + 3.5 * minutes`, or `0.2 * d + 3.5 * minutes` running.
 * The metres are scored as metres, and only the resting term is charged over time, over the seconds
 * the bout was open ([ExerciseBucket.activeSec]).
 *
 * **Gross, not net of resting metabolism** — the resting term is left in, which is what a fitness app
 * means by "calories spent". Making it net is a one-term edit here and nowhere else.
 */
object ExerciseEnergy {

    /** Where the walking equation gives way to the running one, in m/min (6 km/h). */
    const val RUN_THRESHOLD_M_PER_MIN = 100.0

    /**
     * Above this speed, in m/min, a segment was not covered on foot whatever the bout was labelled —
     * 24 km/h is past the world marathon record pace, so it is a bicycle, a vehicle, or a receiver
     * that drifted. The running equation extrapolated there states a large number with nothing
     * behind it, so the segment is dropped instead.
     */
    const val MAX_RUN_M_PER_MIN = 400.0

    /** Caloric equivalent of oxygen at a mixed substrate — the standard indirect-calorimetry figure. */
    const val KCAL_PER_LITRE_O2 = 5.0

    /** The resting term both equations carry, in mL O2 per kg per minute. Dropping it is what would
     *  make the figure net rather than gross. */
    const val RESTING_VO2 = 3.5

    /**
     * Gross kcal for a [kind] bout at [bodyMassKg], summed over its [segments] — the bout's own
     * [ExerciseBucket]s, each carrying the metres measured in it, the interval they were measured over
     * and the seconds the bout was open.
     *
     * A segment above [MAX_RUN_M_PER_MIN] is **dropped, not fatal**: it scores no distance and no
     * resting term, so a cable car, a lift home, or the one wild fix that slipped past
     * [ExerciseBucketer]'s own filter costs the bout that stretch and nothing more. It is never
     * quietly kept — the equations do not describe it, so no number is invented for it.
     *
     * Null when the figure cannot be justified, and the causes do not blend:
     * - an activity the equations do not describe ([ExerciseKind.OTHER]), or no body mass — the bout
     *   has no figure at all;
     * - **more of the bout dropped above [MAX_RUN_M_PER_MIN] than was scored** — whatever it was, it
     *   was mostly not the exercise it was labelled, and a figure for the minority that was left would
     *   be read as the bout's;
     * - no segment with both measured metres and an interval to have measured them over — a bout
     *   indoors, or with the track refused: its seconds are recorded, its energy is not invented.
     *
     * A segment with seconds but no metres (a receiver still acquiring, a tunnel) is skipped rather
     * than voiding the bout: it contributes no energy, which understates the resting term for that
     * stretch by ~2 kcal per five minutes, and inventing one for ground that was never measured is
     * the worse error.
     */
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

    /** mL O2 per kg per metre covered, on whichever gait's equation covers [speedMPerMin] — the
     *  equations' slope in `S`, which over a segment multiplies the metres rather than the speed. */
    private fun vo2PerMetre(speedMPerMin: Double): Double =
        if (speedMPerMin >= RUN_THRESHOLD_M_PER_MIN) 0.2 else 0.1
}
