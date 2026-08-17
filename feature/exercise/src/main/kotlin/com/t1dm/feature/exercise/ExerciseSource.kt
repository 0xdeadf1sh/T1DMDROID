package com.t1dm.feature.exercise

import com.t1dm.core.model.ActiveExercise
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import com.t1dm.core.model.TrackPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The `:feature:exercise` port, in `:core:model` types only, on the `:feature:stats` `StatsSource`
 * pattern: `:app` composes the `:data` session store with the recorder that drives the foreground
 * service, and this module stays free of both.
 *
 * [bodyMassKg] is panel-owned per-device state rather than a Settings knob — it is the one input the
 * energy figure cannot derive, and it is deliberately outside the shareable config export.
 */
interface ExerciseSource {

    /** Every recorded session, newest first. */
    val sessions: Flow<List<ExerciseSession>>

    /** The session being recorded now, or null when nothing is running. */
    val active: StateFlow<ActiveExercise?>

    val bodyMassKg: Flow<Double?>

    suspend fun setBodyMassKg(kg: Double?)

    suspend fun start(kind: ExerciseKind)

    suspend fun stop()

    suspend fun session(id: Long): ExerciseSession?

    /** The session's accepted fixes, ascending in time. Empty for a bout recorded without location. */
    suspend fun track(id: Long): List<TrackPoint>

    suspend fun delete(id: Long)
}
