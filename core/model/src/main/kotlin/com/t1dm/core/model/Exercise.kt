package com.t1dm.core.model

/**
 * The per-5-minute magnitude lands in the wide-sample `exercise` scalar; sessions and track points
 * are phone-local and cross no wire. [ExerciseSession.kcal] is null wherever body mass or distance
 * is missing — withheld rather than guessed.
 */
enum class ExerciseKind { WALK, RUN, OTHER }

/** Longer than any bout logged in one go, short enough that a forgotten one does not hold the GPS
 *  open for days. Shared: the foreground service enforces it, the review panel explains it. */
const val EXERCISE_MAX_BOUT_MS: Long = 12L * 60L * 60L * 1_000L

/**
 * [startMs]/[endMs] are wall-clock, NOT snapped to the five-minute grid; only the derived
 * per-bucket sample write is. [activeSec] is whole seconds recorded. [interrupted] marks a session
 * the app never saw stopped, closed at the last thing actually recorded.
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

/** [speedMps] is the receiver's own figure where it reported one; the track's distance is measured
 *  between fixes, never from this. */
data class TrackPoint(
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
)

/** [lastFixAgeMs] and [degraded]: a suspended or denied location service still holds a session
 *  open, and a track that stops growing otherwise looks like a walk that went nowhere. */
data class ActiveExercise(
    val session: ExerciseSession,
    val elapsedMs: Long,
    val distanceM: Double,
    val paceSecPerKm: Double?,
    val kcal: Int?,
    val lastFixAgeMs: Long?,
    val degraded: String?,
)
