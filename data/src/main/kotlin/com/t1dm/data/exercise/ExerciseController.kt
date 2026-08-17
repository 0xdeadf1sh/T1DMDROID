package com.t1dm.data.exercise

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import com.t1dm.core.model.TrackPoint
import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.ExerciseFixEntity
import com.t1dm.data.db.ExerciseSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * Orchestrates the exercise bout store over [T1dmRepository], on the [com.t1dm.data.meals.MealsController]
 * pattern: it is the seam `:app` composes the (pure-Compose) `:feature:exercise` panel against, and
 * the one place `exercise_session`'s raw `kind` TEXT is turned into an [ExerciseKind] and back.
 *
 * It does NOT write the per-bucket magnitude. That goes into the wide sample through
 * [T1dmRepository.recordExerciseCurve], from the recorder in `:sensors`, so it rides the same
 * transactional merge and `INGEST` enqueue every other scalar does.
 */
class ExerciseController(
    private val repository: T1dmRepository,
    private val dispatchers: T1dmDispatchers,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Every recorded bout, newest first. */
    val sessions: Flow<List<ExerciseSession>> =
        repository.observeExerciseSessions().map { rows -> rows.map { it.toModel() } }

    /**
     * Open a bout at [startMs] and return the persisted row, whose id everything downstream addresses
     * it by.
     *
     * [startMs] stays a wall-clock instant — see [T1dmRepository.startExerciseSession] — and the
     * timezone offset is resolved once, here, from the instant the bout began rather than from
     * whatever the clock says when it ends.
     */
    suspend fun start(kind: ExerciseKind, startMs: Long = now()): ExerciseSession =
        withContext(dispatchers.io) {
            repository.startExerciseSession(
                ExerciseSessionEntity(
                    clientId = "",
                    startMs = startMs,
                    endMs = null,
                    tzOffsetMin = TimeZone.getDefault().getOffset(startMs) / 60_000,
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

    /**
     * Close a bout with what the recorder measured. [kcal] is null where no figure could be
     * justified, and stays whatever it was — nothing recomputes it later.
     *
     * [interrupted] is what separates a bout the USER ended from one the app ended for them — the
     * `EXERCISE_MAX_BOUT_MS` limit here, a process death in [reconcileOpenSessions]. Both close at
     * an instant the user did not choose, and the panel says so rather than presenting it as a
     * decision.
     */
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

    /** Append a batch of accepted fixes to a bout's track. */
    suspend fun appendTrack(sessionId: Long, points: List<TrackPoint>) =
        withContext(dispatchers.io) {
            repository.appendExerciseFixes(points.map { it.toEntity(sessionId) })
        }

    suspend fun session(id: Long): ExerciseSession? =
        withContext(dispatchers.io) { repository.exerciseSession(id)?.toModel() }

    suspend fun track(id: Long): List<TrackPoint> =
        withContext(dispatchers.io) { repository.exerciseTrack(id).map { it.toModel() } }

    suspend fun delete(id: Long) = repository.deleteExerciseSession(id)

    /**
     * Close every bout the app never saw stopped, at the newest instant it can prove the bout was
     * still running — the last recorded fix, or the start when the track is empty — and mark it
     * interrupted. Returns how many were closed.
     *
     * Run once at launch. A killed recording is NOT resumed: the location service joins none of the
     * restart paths, because a foreground service restarted from the background would go on recording
     * a bout the user believes ended. Closing at what was recorded is the fail-closed reading of a
     * process that died — the alternative, closing at "now", would credit the bout with every hour
     * the phone spent switched off.
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
 * Where a bout the app never saw stopped is closed: the newest fix it recorded, or its own start when
 * it recorded none.
 *
 * Never "now". The process died at an unknown moment and the phone may have been off for a day
 * since; closing at the current clock would credit the bout with every hour of it. The newest fix is
 * the last instant the app can prove the recording was still running, and a bout with no track at all
 * can prove nothing past its start. Clamped so a track holding a stamp older than the start — which
 * only a hand-edited archive can produce — cannot end a bout before it began.
 */
internal fun interruptedEndMs(startMs: Long, newestFixTsMs: Long?): Long =
    maxOf(newestFixTsMs ?: startMs, startMs)

/** Unknown names decode to [ExerciseKind.OTHER]: `kind` is raw TEXT precisely so a bout recorded by
 *  a later build stays readable, and a `valueOf` here would give that back. */
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
