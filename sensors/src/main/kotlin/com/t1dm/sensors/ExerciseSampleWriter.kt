package com.t1dm.sensors

import com.t1dm.core.model.TrackPoint
import com.t1dm.data.ExerciseCurveBucket
import com.t1dm.data.T1dmRepository
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.ExerciseDisposal
import com.t1dm.data.exercise.ExerciseController
import java.util.TimeZone

/**
 * Persistence seam for a bout's glucose-disposal magnitude. An interface free of any curve or Room
 * type, so [ExerciseRecorder] and its tests never bind to either; `:app` wires
 * [RepositoryExerciseSampleWriter] at the composition root.
 */
interface ExerciseSampleWriter {
    /**
     * Record the bout as it stands: [durationMin] minutes of exercise beginning at [startMs],
     * replacing everything this same writer previously recorded for this bout.
     *
     * A duration and not a bucket, because the curve is not a per-bucket quantity the recorder could
     * hand over piecemeal. `SPEC/invariants.md` §5 makes the magnitude a function of the WHOLE bout
     * — `duration_min · carb_equiv_per_min`, spread across `duration_min + 90` minutes — so a bout
     * that has run five minutes longer has a different curve in every bucket, including the ones it
     * already wrote. One writer per bout: it remembers what it last laid down.
     */
    suspend fun record(startMs: Long, durationMin: Double)
}

/**
 * Resolves §5's exercise gamma and writes it through [T1dmRepository.recordExerciseCurve], never
 * through `SampleDao`.
 *
 * That is the whole point of this class, and it is where it differs from [RoomStepSampleWriter]
 * beside it. The repository's merge does the `requireGrid`, the read-modify-write, the `updatedAt`
 * last-writer-wins bump and the `INGEST` outbox enqueue inside ONE write transaction; the step
 * writer reaches past all of that into a bare upsert, which is exactly why measured step buckets
 * never reach the server. Copying that shape here would have copied the defect — and the whole
 * curve rides one transaction besides, so a rewritten curve is never half-visible.
 *
 * **The curve is REBUILT, not extended.** Every call resolves the gamma for the duration as it now
 * stands and diffs it against what this bout last wrote, slot for slot. That is what lets a bout
 * still running contribute its curve so far while staying idempotent: recording the same duration
 * twice writes nothing.
 *
 * **[carbEquivPerMin] is read per call, and a past bout is never rewritten.** The patient's own
 * value can change between bouts, or mid-bout; what is stored is what could be justified when it was
 * written, exactly as the ACSM kcal figure treats a change of body mass. Nothing walks history to
 * re-derive an old bout's curve at a new rate.
 *
 * One instance per bout — it carries that bout's own contribution. The repository and the engine
 * hop off the caller's thread themselves, so there is no `withContext` here.
 */
class RepositoryExerciseSampleWriter(
    private val repository: T1dmRepository,
    private val curves: CurveEngine,
    private val carbEquivPerMin: suspend () -> Double,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffsetMinAt: (Long) -> Int = { ms -> TimeZone.getDefault().getOffset(ms) / 60_000 },
) : ExerciseSampleWriter {

    /** What THIS bout has already put in each grid slot, in grams. One entry per five minutes of
     *  curve, so about thirty for an hour's walk. */
    private val written = HashMap<Long, Double>()

    override suspend fun record(startMs: Long, durationMin: Double) {
        val params = ExerciseDisposal.paramsFor(durationMin, carbEquivPerMin())
        if (params.grams <= 0.0) return
        val values = curves.gamma(params.grams, params.k, params.theta, params.durationMin)
        val buckets = exerciseCurveBuckets(startMs, values, written, tzOffsetMinAt)
        if (buckets.isEmpty()) return
        repository.recordExerciseCurve(buckets, clock())
        for (b in buckets) written[b.gridTs] = b.grams
    }
}

/**
 * Lay a resolved curve on the grid and diff it against what this bout last wrote — the whole of
 * [RepositoryExerciseSampleWriter]'s arithmetic, lifted out so it is testable without a database or
 * a JNI hop.
 *
 * Bucket `i` of [values] is the five minutes beginning `i` steps after the slot the session STARTED
 * in. The snap is the repository's own: `../T1DMCOMMON/SPEC/invariants.md` §1 makes round-versus-floor
 * part of the contract rather than an implementation detail, so this must not be a second spelling of
 * it.
 *
 * A slot whose value has not moved is dropped outright. The write would be a no-op on the stored
 * number and would still mint the row and enqueue an `INGEST` push for it, so this is what makes a
 * second `finish` — or a boundary crossed with the bout paused — cost nothing.
 */
internal fun exerciseCurveBuckets(
    startMs: Long,
    values: DoubleArray,
    written: Map<Long, Double>,
    tzOffsetMinAt: (Long) -> Int,
): List<ExerciseCurveBucket> {
    val gridStart = T1dmRepository.snapToGrid(startMs)
    val out = ArrayList<ExerciseCurveBucket>(values.size)
    for (i in values.indices) {
        val gridTs = gridStart + i * T1dmRepository.GRID_MS
        val prior = written[gridTs] ?: 0.0
        if (values[i] == prior) continue
        out.add(
            ExerciseCurveBucket(
                gridTs = gridTs,
                tzOffsetMin = tzOffsetMinAt(gridTs),
                grams = values[i],
                priorGrams = prior,
            ),
        )
    }
    return out
}

/**
 * Persistence seam for the bout's track, kept separate from [ExerciseSampleWriter] because the two
 * have nothing in common but their timing: the magnitude syncs, the track is phone-local and crosses
 * no wire.
 */
interface ExerciseTrackWriter {
    /** Append accepted fixes to a bout's track, in the order they were recorded. */
    suspend fun append(sessionId: Long, points: List<TrackPoint>)
}

/** Appends through [ExerciseController], which owns the `TrackPoint` ↔ `exercise_fix` mapping. */
class ControllerExerciseTrackWriter(
    private val controller: ExerciseController,
) : ExerciseTrackWriter {

    override suspend fun append(sessionId: Long, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        controller.appendTrack(sessionId, points)
    }
}
