package com.t1dm.core.model

/** Per-5-min magnitude in `exercise` scalar, phone-local; [kcal] null if mass/dist gone. */
enum class ExerciseKind { WALK, RUN, OTHER }

/** Longer than any real bout, short enough a forgotten one won't hold GPS for days; shared cap. */
const val EXERCISE_MAX_BOUT_MS: Long = 12L * 60L * 60L * 1_000L

/** [startMs]/[endMs] wall-clock, not grid-snapped; [interrupted] closes at last recorded thing. */
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

/** [speedMps] is the receiver's figure if reported; distance is between fixes, never from this. */
data class TrackPoint(
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
)

/** [lastFixAgeMs]/[degraded]: suspended location holds the session; a stalled track looks empty. */
data class ActiveExercise(
    val session: ExerciseSession,
    val elapsedMs: Long,
    val distanceM: Double,
    val paceSecPerKm: Double?,
    val kcal: Int?,
    val lastFixAgeMs: Long?,
    val degraded: String?,
)
