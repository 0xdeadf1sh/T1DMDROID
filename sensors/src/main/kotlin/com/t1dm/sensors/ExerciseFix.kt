package com.t1dm.sensors

import com.t1dm.core.model.TrackPoint

/** Phone wall time, before [ExerciseBucketer] has decided to believe it. [TrackPoint] is the stored
 *  point; [toTrackPoint] is the one crossing, taken only on an accepted fix. */
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

/** [bucketStartMs] is grid-aligned; [activeSec] is this bout's whole seconds in it; [distanceM] is
 *  null when no fix was accepted there. [trackedMs] is the wall clock those metres were covered
 *  over — metres over [activeSec] is not a speed. Never `sample.exercise`, which holds carb grams. */
data class ExerciseBucket(
    val bucketStartMs: Long,
    val activeSec: Int,
    val distanceM: Double?,
    val trackedMs: Long = 0L,
)
