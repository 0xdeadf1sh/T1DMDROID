package com.t1dm.sensors

import com.t1dm.core.model.TrackPoint

/**
 * One reading off the location receiver, stamped with phone wall time — *before* [ExerciseBucketer]
 * has decided whether to believe it.
 *
 * Deliberately not [TrackPoint], which is the STORED point: a fix with a 200 m accuracy circle or a
 * 60 m/s implied speed is a fix, and is not a track point. [toTrackPoint] is the single crossing
 * between the two, taken by the recorder on the ones the bucketer accepted.
 */
data class ExerciseFix(
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
)

fun ExerciseFix.toTrackPoint() = TrackPoint(
    tsMs = tsMs,
    lat = lat,
    lon = lon,
    accuracyM = accuracyM,
    speedMps = speedMps,
)

/**
 * One closed-or-updated 5-minute exercise bucket, the counterpart of [StepBucket]. [bucketStartMs] is
 * grid-aligned (`% 300_000 == 0`); [activeSec] is the whole-second total THIS BOUT has recorded in
 * that bucket.
 *
 * **It is not `sample.exercise` and never becomes it.** That column holds grams of carbohydrate
 * equivalent — the disposal §5's exercise gamma spreads across the bout and the ninety minutes after
 * it, a function of the bout's WHOLE duration and not of any one bucket's seconds. These buckets
 * feed two other things entirely: the bout's own `activeSec` total, and the per-segment speeds
 * [ExerciseEnergy] scores its kcal figure over.
 *
 * That is also where it parts company with [StepBucket], whose figure IS the stored one because the
 * step counter is one continuous hardware total.
 *
 * [distanceM] is null when no accepted fix landed in the bucket — the honest reading for a bout
 * indoors or with location denied, which still accrues seconds. It is not persisted per bucket: only
 * the bout's total reaches `exercise_session`. It is still the unit the energy figure is computed
 * over, though — [ExerciseEnergy] scores a bout segment by segment because a whole-bout average speed
 * hides a stretch that was not the labelled exercise — so [ExerciseRecorder] keeps the buckets for the
 * life of the bout.
 *
 * [trackedMs] is the wall-clock those metres were covered over: the fix-to-fix intervals of the very
 * segments charged here, summed. **It is not [activeSec], and dividing the metres by [activeSec] does
 * not give a speed.** A segment straddling a boundary is charged whole to the bucket the fix arrives
 * in, while that bucket's seconds start at the boundary — so the first fix of a bucket can leave 13 m
 * of running against 1 s, which is 800 m/min and is nothing that happened. [ExerciseEnergy] takes
 * every speed over [trackedMs].
 */
data class ExerciseBucket(
    val bucketStartMs: Long,
    val activeSec: Int,
    val distanceM: Double?,
    val trackedMs: Long = 0L,
)
