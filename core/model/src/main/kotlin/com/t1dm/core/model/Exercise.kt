package com.t1dm.core.model

/** Per-5-min magnitude in exercise scalar, phone-local; kcal null if mass/distance missing. */
enum class ExerciseKind { WALK, RUN, OTHER }

/** Longer than any bout, short enough a forgotten one does not hold GPS open for days. */
const val EXERCISE_MAX_BOUT_MS: Long = 12L * 60L * 60L * 1_000L

/** startMs/endMs wall-clock, NOT grid-snapped; only the derived bucket write is. */
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

/** speedMps is the receiver's own figure; track distance is measured between fixes, not this. */
data class TrackPoint(
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
)

/** lastFixAgeMs/degraded: a suspended location service still holds a session open. */
data class ActiveExercise(
    val session: ExerciseSession,
    val elapsedMs: Long,
    val distanceM: Double,
    val paceSecPerKm: Double?,
    val kcal: Int?,
    val lastFixAgeMs: Long?,
    val degraded: String?,
)
