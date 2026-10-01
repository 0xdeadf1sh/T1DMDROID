package com.t1dm.sensors

import com.t1dm.core.model.TrackPoint

/** Phone wall time, unvetted by ExerciseBucketer; toTrackPoint crosses only on accepted fix. */
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

/** distanceM null = no fix accepted; never sample.exercise, which holds carb grams instead. */
data class ExerciseBucket(
    val bucketStartMs: Long,
    val activeSec: Int,
    val distanceM: Double?,
    val trackedMs: Long = 0L,
)
