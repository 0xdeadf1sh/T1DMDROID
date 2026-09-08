package com.t1dm.app.exercise

import com.t1dm.app.settings.SettingsStore
import com.t1dm.core.model.ActiveExercise
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import com.t1dm.core.model.TrackPoint
import com.t1dm.data.exercise.ExerciseController
import com.t1dm.feature.exercise.ExerciseSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [start]/[stop] only signal the foreground service; the bout row opens/closes there. */
class AppExerciseSource(
    private val controller: ExerciseController,
    private val settings: SettingsStore,
    active: MutableStateFlow<ActiveExercise?>,
    private val onStart: (ExerciseKind) -> Unit,
    private val onStop: () -> Unit,
) : ExerciseSource {

    override val sessions: Flow<List<ExerciseSession>> = controller.sessions
    override val active: StateFlow<ActiveExercise?> = active.asStateFlow()
    override val bodyMassKg: Flow<Double?> = settings.bodyMassKg

    override suspend fun setBodyMassKg(kg: Double?) = settings.setBodyMassKg(kg)

    override suspend fun start(kind: ExerciseKind) = onStart(kind)

    override suspend fun stop() = onStop()

    override suspend fun session(id: Long): ExerciseSession? = controller.session(id)

    override suspend fun track(id: Long): List<TrackPoint> = controller.track(id)

    override suspend fun delete(id: Long) = controller.delete(id)
}
