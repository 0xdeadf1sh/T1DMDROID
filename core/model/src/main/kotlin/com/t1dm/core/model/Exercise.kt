package com.t1dm.core.model

/**
 * The logged-exercise vocabulary shared by `:app`, `:data`, `:sensors` and `:feature:exercise`.
 *
 * Exercise is not a new concept in this suite: the per-5-minute magnitude a session produces lands in
 * the existing wide-sample `exercise` scalar, beside bg/hr/steps/sleep/mood, and rides the ingest row
 * that already exists. Nothing here is a second notion of it — a [ExerciseSession] and its
 * [TrackPoint]s are the phone-local record of *how* those seconds were spent, and they cross no wire.
 *
 * [kcal] is nullable everywhere on purpose. The figure needs a body mass the user may not have
 * supplied and a distance a session indoors will not have, and a number that cannot be justified is
 * withheld rather than guessed.
 */
enum class ExerciseKind { WALK, RUN, OTHER }

/**
 * The longest a single bout may run before the recorder closes it on its own.
 *
 * Longer than any bout a person logs in one go, and short enough that one begun and forgotten does
 * not hold the GPS receiver open for days. It lives here because two modules need the same number:
 * `:app`'s foreground service enforces it, and the review panel reads it to say that is why a bout
 * ended where it did — a second copy of it would let those two disagree about which bouts were cut.
 */
const val EXERCISE_MAX_BOUT_MS: Long = 12L * 60L * 60L * 1_000L

/**
 * One start-to-stop bout, as the panel reads it.
 *
 * [startMs]/[endMs] are wall-clock instants, NOT snapped to the five-minute grid — a bout begins when
 * the user says so. Only the derived per-bucket sample write is on the grid.
 *
 * [activeSec] is the whole seconds of the bout that were recorded, which is the same quantity summed
 * across the buckets the session wrote. [interrupted] marks a session the app never saw stopped: it
 * was closed at the last thing actually recorded rather than at a time it may not have run to.
 */
data class ExerciseSession(
    val id: Long,
    val startMs: Long,
    val endMs: Long?,
    val tzOffsetMin: Int,
    val kind: ExerciseKind,
    val activeSec: Int,
    val distanceM: Double?,
    val kcal: Int?,
    val interrupted: Boolean,
)

/** One accepted GPS fix on a session's track. [speedMps] is the receiver's own figure where it
 *  reported one; the track's distance is measured between fixes, never from this. */
data class TrackPoint(
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
)

/**
 * The live view of the session being recorded now.
 *
 * [lastFixAgeMs] and [degraded] are the honesty channel: a location service that has been suspended
 * or denied still holds a session open, and a track that simply stops growing is indistinguishable
 * from a walk that went nowhere unless the panel is told why.
 */
data class ActiveExercise(
    val session: ExerciseSession,
    val elapsedMs: Long,
    val distanceM: Double,
    val paceSecPerKm: Double?,
    val kcal: Int?,
    val lastFixAgeMs: Long?,
    val degraded: String?,
)
