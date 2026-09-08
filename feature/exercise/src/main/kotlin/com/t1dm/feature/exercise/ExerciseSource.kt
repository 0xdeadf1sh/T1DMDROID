package com.t1dm.feature.exercise

import com.t1dm.core.model.ActiveExercise
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import com.t1dm.core.model.TrackPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** [bodyMassKg] is panel-owned per-device state, deliberately outside the shareable config. */
interface ExerciseSource {

    /** Every recorded session, newest first. */
    val sessions: Flow<List<ExerciseSession>>

    val active: StateFlow<ActiveExercise?>

    val bodyMassKg: Flow<Double?>

    suspend fun setBodyMassKg(kg: Double?)

    suspend fun start(kind: ExerciseKind)

    suspend fun stop()

    suspend fun session(id: Long): ExerciseSession?

    /** Ascending in time. Empty for a bout recorded without location. */
    suspend fun track(id: Long): List<TrackPoint>

    suspend fun delete(id: Long)
}
