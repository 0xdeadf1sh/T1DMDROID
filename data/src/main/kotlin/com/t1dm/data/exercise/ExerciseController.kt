package com.t1dm.data.exercise

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import com.t1dm.core.model.TrackPoint
import com.t1dm.data.ExerciseCurveBucket
import com.t1dm.data.T1dmRepository
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.ExerciseDisposal
import com.t1dm.data.curve.exerciseCurveLaid
import com.t1dm.data.curve.exerciseCurveTaken
import com.t1dm.data.db.ExerciseFixEntity
import com.t1dm.data.db.ExerciseSessionEntity
import com.t1dm.data.db.LoggedExerciseEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.TimeZone

/** Does NOT write the per-bucket magnitude: that goes into the wide sample through
 *  [T1dmRepository.recordExerciseCurve], from the recorder in `:sensors`. */
class ExerciseController(
    private val repository: T1dmRepository,
    private val dispatchers: T1dmDispatchers,
    private val curves: CurveEngine,
    /** Grams per minute, for re-deriving a bout's magnitude on a delete. */
    private val carbEquivPerMin: suspend () -> Double = { ExerciseDisposal.DEFAULT_CARB_EQUIV_PER_MIN },
    private val now: () -> Long = System::currentTimeMillis,
    /** Per SLOT, not per row: a curve running ninety minutes past its bout can cross a DST step. */
    private val tzOffsetMinAt: (Long) -> Int = { ms -> TimeZone.getDefault().getOffset(ms) / 60_000 },
) {
    /** Every recorded bout, newest first. */
    val sessions: Flow<List<ExerciseSession>> =
        repository.observeExerciseSessions().map { rows -> rows.map { it.toModel() } }

    /** [startMs] stays a wall-clock instant, and the offset is resolved from it rather than from
     *  whatever the clock says when the bout ends. */
    suspend fun start(kind: ExerciseKind, startMs: Long = now()): ExerciseSession =
        withContext(dispatchers.io) {
            repository.startExerciseSession(
                ExerciseSessionEntity(
                    clientId = "",
                    startMs = startMs,
                    endMs = null,
                    tzOffsetMin = tzOffsetMinAt(startMs),
                    kind = kind.name,
                    activeSec = 0,
                    distanceM = null,
                    kcal = null,
                    interrupted = false,
                    note = null,
                    updatedAt = startMs,
                ),
            ).toModel()
        }

    /** [kcal] is null where no figure could be justified, and nothing recomputes it. [interrupted]
     *  marks a bout the app ended: the `EXERCISE_MAX_BOUT_MS` limit, or [reconcileOpenSessions]. */
    suspend fun stop(
        id: Long,
        endMs: Long,
        activeSec: Int,
        distanceM: Double?,
        kcal: Int?,
        interrupted: Boolean = false,
    ) = withContext(dispatchers.io) {
        repository.endExerciseSession(
            id = id,
            endMs = endMs,
            activeSec = activeSec,
            distanceM = distanceM,
            kcal = kcal,
            interrupted = interrupted,
            nowMs = now(),
        )
    }

    suspend fun appendTrack(sessionId: Long, points: List<TrackPoint>) =
        withContext(dispatchers.io) {
            repository.appendExerciseFixes(points.map { it.toEntity(sessionId) })
        }

    suspend fun session(id: Long): ExerciseSession? =
        withContext(dispatchers.io) { repository.exerciseSession(id)?.toModel() }

    suspend fun track(id: Long): List<TrackPoint> =
        withContext(dispatchers.io) { repository.exerciseTrack(id).map { it.toModel() } }

    /**
     * Takes the bout's disposal grams back out of the wide sample. The unwind is re-derived from the
     * bout's own parameters — what it contributed per slot lived only in the recorder's memory — and
     * is exact: the magnitude is a function of duration alone (`SPEC/invariants.md` §5).
     */
    suspend fun delete(id: Long) = withContext(dispatchers.io) {
        val bout = repository.exerciseSession(id)
        val unwind = if (bout == null) {
            emptyList()
        } else {
            val minutes = ((bout.endMs ?: now()) - bout.startMs).coerceAtLeast(0L) / 60_000.0
            val params = ExerciseDisposal.paramsFor(minutes, carbEquivPerMin())
            if (params.grams <= 0.0) {
                emptyList()
            } else {
                val values = curves.gamma(params.grams, params.k, params.theta, params.durationMin)
                val gridStart = T1dmRepository.snapToGrid(bout.startMs)
                values.indices.map { i ->
                    val gridTs = gridStart + i * T1dmRepository.GRID_MS
                    ExerciseCurveBucket(
                        gridTs = gridTs,
                        tzOffsetMin = bout.tzOffsetMin,
                        grams = 0.0,
                        priorGrams = values[i],
                    )
                }
            }
        }
        repository.deleteExerciseSession(id, unwind, now())
    }

    /**
     * [source] laid down again at [startMs]: its kind and its duration, rated at the patient's
     * CURRENT carb-equivalent. Null for a bout with no duration — §5's magnitude is duration times
     * rate, so a zero-length bout disposes of nothing and a row for it would unwind nothing.
     */
    suspend fun replay(source: ExerciseSession, startMs: Long): LoggedExerciseEntity? =
        logExercise(source.kind, source.activeSec / 60.0, startMs, source.id)

    /** Writes the §5 disposal gamma into `sample.exercise` and the row that owns it, in one go. */
    suspend fun logExercise(
        kind: ExerciseKind,
        durationMin: Double,
        startMs: Long,
        sourceSessionId: Long? = null,
    ): LoggedExerciseEntity? = withContext(dispatchers.io) {
        val params = ExerciseDisposal.paramsFor(durationMin, carbEquivPerMin())
        if (params.grams <= 0.0) return@withContext null
        val nowMs = now()
        val gridStart = T1dmRepository.snapToGrid(startMs)
        val values = curves.gamma(params.grams, params.k, params.theta, params.durationMin)
        val row = LoggedExerciseEntity(
            clientId = "",
            tsMs = gridStart,
            tzOffsetMin = tzOffsetMinAt(gridStart),
            kind = kind.name,
            durationMin = durationMin,
            grams = params.grams,
            k = params.k,
            theta = params.theta,
            curveDurationMin = params.durationMin,
            sourceSessionId = sourceSessionId,
            updatedAt = nowMs,
            loggedAtMs = nowMs,
        )
        repository.logLoggedExercise(row, exerciseCurveLaid(gridStart, values, tzOffsetMinAt), nowMs)
    }

    /**
     * Moves a replay in time and nothing else: the magnitude is a function of duration alone, so the
     * curve is the same array laid at a new start. Re-derived from what the ROW stores, so a
     * carb-equivalent changed since it was logged cannot rewrite its history.
     */
    suspend fun shiftLoggedExercise(id: Long, tsMs: Long): LoggedExerciseEntity? =
        withContext(dispatchers.io) {
            val row = repository.loggedExerciseById(id) ?: return@withContext null
            val values = curveOf(row)
            val gridStart = T1dmRepository.snapToGrid(tsMs)
            if (gridStart == row.tsMs) return@withContext row
            repository.editLoggedExercise(
                row = row.copy(tsMs = gridStart, tzOffsetMin = tzOffsetMinAt(gridStart)),
                unwind = exerciseCurveTaken(row.tsMs, values, tzOffsetMinAt),
                write = exerciseCurveLaid(gridStart, values, tzOffsetMinAt),
                nowMs = now(),
            )
        }

    /** Takes the row's grams back out of every slot it wrote, then drops it. */
    suspend fun deleteLoggedExercise(id: Long) = withContext(dispatchers.io) {
        val row = repository.loggedExerciseById(id)
        val unwind = if (row == null) emptyList() else exerciseCurveTaken(row.tsMs, curveOf(row), tzOffsetMinAt)
        repository.deleteLoggedExercise(id, unwind, now())
    }

    private suspend fun curveOf(row: LoggedExerciseEntity): DoubleArray =
        curves.gamma(row.grams, row.k, row.theta, row.curveDurationMin)

    /**
     * Close every bout the app never saw stopped, at the newest instant it can prove it was still
     * running, and mark it interrupted. Run once at launch. A killed recording is NOT resumed: a
     * foreground service restarted from the background would record a bout the user believes ended.
     */
    suspend fun reconcileOpenSessions(nowMs: Long): Int = withContext(dispatchers.io) {
        val open = repository.openExerciseSessions()
        for (row in open) {
            repository.endExerciseSession(
                id = row.id,
                endMs = interruptedEndMs(row.startMs, repository.newestExerciseFixTs(row.id)),
                activeSec = row.activeSec,
                distanceM = row.distanceM,
                kcal = row.kcal,
                interrupted = true,
                nowMs = nowMs,
            )
        }
        open.size
    }
}

/**
 * Never "now": the process died at an unknown moment and the phone may have been off since, so the
 * newest fix is the last instant the recording can be proven to have been running. Clamped, so a
 * track holding a stamp older than the start cannot end a bout before it began.
 */
internal fun interruptedEndMs(startMs: Long, newestFixTsMs: Long?): Long =
    maxOf(newestFixTsMs ?: startMs, startMs)

/** Unknown names decode to [ExerciseKind.OTHER]: `kind` is raw TEXT so a bout recorded by a later
 *  build stays readable. */
internal fun ExerciseSessionEntity.toModel() = ExerciseSession(
    id = id,
    startMs = startMs,
    endMs = endMs,
    tzOffsetMin = tzOffsetMin,
    kind = runCatching { ExerciseKind.valueOf(kind) }.getOrNull() ?: ExerciseKind.OTHER,
    activeSec = activeSec,
    distanceM = distanceM,
    kcal = kcal,
    interrupted = interrupted,
)

internal fun ExerciseFixEntity.toModel() = TrackPoint(
    tsMs = tsMs,
    lat = lat,
    lon = lon,
    accuracyM = accuracyM,
    speedMps = speedMps,
)

internal fun TrackPoint.toEntity(sessionId: Long) = ExerciseFixEntity(
    sessionId = sessionId,
    tsMs = tsMs,
    lat = lat,
    lon = lon,
    accuracyM = accuracyM,
    speedMps = speedMps,
)
