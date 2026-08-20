package com.t1dm.feature.exercise

import com.t1dm.core.model.TrackPoint

/**
 * A point on a bout's track, in degrees.
 *
 * Deliberately not osmdroid's `GeoPoint`: that is `Parcelable`, and this has to resolve in a host
 * JVM test with no Android on the classpath.
 */
data class TrackFix(val lat: Double, val lon: Double)

/**
 * Where the bout was at [cursorMs], or null when nothing recorded says.
 *
 * Four decisions, each of which has a plausible-looking alternative that is wrong:
 *
 * **No clamping to the ends.** The review window opens half an hour before the bout and closes two
 * hours after it, so most of the slider's travel has no fixes at all. Pinning the dot to the last
 * fix would show the patient standing at the finish line for two hours — the same defect the BG
 * read-out's own catchment guards against.
 *
 * **Interpolation, not nearest-fix.** The recorder only emits a fix once the phone has moved five
 * metres, so a genuinely stationary stretch emits none at all — and the two fixes bracketing such a
 * stretch are the same place. Interpolating across it is right where a nearest-fix catchment would
 * blink the dot out for standing still.
 *
 * **The bound is the distance to the NEARER bracketing fix, not the width of the bracket.** Those
 * are different predicates, and the bracket version withholds the dot at instants where a fix was
 * recorded seconds away: a five-minute-ten-second stop puts one detent inside a bracket wider than
 * a grid slot, and the dot would vanish five seconds from a real fix. This is the half-slot
 * catchment the read-out's BG row uses, applied to the same instant.
 *
 * Note what that does NOT buy, whatever the two bounds have in common: glucose samples and GPS
 * fixes are independent series, so "the same instant either has both or neither" is not a property
 * any bound here can deliver, and the panel must not be written as though it were.
 *
 * The binary search is [com.t1dm.ui.graph.GraphFrame]'s, kept in the same shape on purpose. The
 * track is ascending by `tsMs` — `ExerciseFixDao.forSession` is `ORDER BY tsMs, id`.
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
