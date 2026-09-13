package com.t1dm.feature.exercise

import com.t1dm.core.model.TrackPoint

/** Degrees. Not osmdroid's `GeoPoint`: resolves in a host JVM test, no Android on classpath. */
data class TrackFix(val lat: Double, val lon: Double)

/** 4× LocationSource's low-power fix interval; a wider gap is a stop (5 m floor) or lost signal. */
internal const val TRACK_INTERPOLATE_MAX_MS = 60_000L

/** Null outside the track's span; track ascends by tsMs. A wider gap holds the earlier fix. */
internal fun trackPositionAt(
    track: List<TrackPoint>,
    cursorMs: Long,
    maxInterpolateMs: Long = TRACK_INTERPOLATE_MAX_MS,
): TrackFix? {
    if (track.isEmpty()) return null
    if (cursorMs < track.first().tsMs || cursorMs > track.last().tsMs) return null
    var lo = 0
    var hi = track.size - 1
    while (lo + 1 < hi) {
        val mid = (lo + hi) ushr 1
        if (track[mid].tsMs <= cursorMs) lo = mid else hi = mid
    }
    val a = track[lo]
    val b = track[hi]
    if (cursorMs >= b.tsMs) return TrackFix(b.lat, b.lon)
    val gap = b.tsMs - a.tsMs
    if (gap <= 0L || gap > maxInterpolateMs) return TrackFix(a.lat, a.lon)
    val t = (cursorMs - a.tsMs).toDouble() / gap.toDouble()
    return TrackFix(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
}
