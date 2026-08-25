package com.t1dm.feature.exercise

import com.t1dm.core.model.TrackPoint

/** Degrees. Not osmdroid's `GeoPoint`: that is `Parcelable`, and this must resolve in a host JVM
 *  test with no Android on the classpath. */
data class TrackFix(val lat: Double, val lon: Double)

/**
 * Null outside the track's own span, and beyond half a slot from the NEARER bracketing fix — not
 * the bracket width, which would withhold the dot seconds from a real fix. Interpolates, because a
 * stationary stretch emits no fixes at all. [track] ascending by `tsMs`.
 */
internal fun trackPositionAt(
    track: List<TrackPoint>,
    cursorMs: Long,
    maxBracketMs: Long,
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
    if (b.tsMs <= a.tsMs) return TrackFix(a.lat, a.lon)
    val nearest = minOf(cursorMs - a.tsMs, b.tsMs - cursorMs)
    if (nearest > maxBracketMs / 2) return null
    val t = (cursorMs - a.tsMs).toDouble() / (b.tsMs - a.tsMs).toDouble()
    return TrackFix(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
}
