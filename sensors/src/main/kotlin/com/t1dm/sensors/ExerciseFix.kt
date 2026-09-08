package com.t1dm.sensors

import com.t1dm.core.model.TrackPoint

/** Phone wall time, before [ExerciseBucketer] believes it. [toTrackPoint] crosses on accept. */
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

/** [distanceM] null ⇒ no fix accepted. [trackedMs] is real time; metres/[activeSec] isnt speed. */
data class ExerciseBucket(
    val bucketStartMs: Long,
    val activeSec: Int,
    val distanceM: Double?,
    val trackedMs: Long = 0L,
)
