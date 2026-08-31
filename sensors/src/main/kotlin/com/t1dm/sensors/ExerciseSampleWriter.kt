package com.t1dm.sensors

import com.t1dm.core.model.TrackPoint
import com.t1dm.data.ExerciseCurveBucket
import com.t1dm.data.T1dmRepository
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.exerciseCurveBuckets
import com.t1dm.data.curve.ExerciseDisposal
import com.t1dm.data.exercise.ExerciseController
import java.util.TimeZone

interface ExerciseSampleWriter {
    /** Replaces everything this writer previously recorded for the bout. A duration, not a bucket:
     *  `SPEC/invariants.md` §5 makes the magnitude a function of the whole bout. One per bout. */
    suspend fun record(startMs: Long, durationMin: Double)
}

/** Through [T1dmRepository.recordExerciseCurve], never `SampleDao` — the bare upsert
 *  [RoomStepSampleWriter] uses is why measured step buckets never reach the server. One instance per
 *  bout; the curve is rebuilt each call, so recording the same duration twice writes nothing. */
class RepositoryExerciseSampleWriter(
    private val repository: T1dmRepository,
    private val curves: CurveEngine,
    private val carbEquivPerMin: suspend () -> Double,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffsetMinAt: (Long) -> Int = { ms -> TimeZone.getDefault().getOffset(ms) / 60_000 },
) : ExerciseSampleWriter {

    /** What this bout has already put in each grid slot, in grams. */
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

/** Separate from [ExerciseSampleWriter]: the magnitude syncs, the track is phone-local. */
interface ExerciseTrackWriter {
    /** In the order recorded. */
    suspend fun append(sessionId: Long, points: List<TrackPoint>)
}

class ControllerExerciseTrackWriter(
    private val controller: ExerciseController,
) : ExerciseTrackWriter {

    override suspend fun append(sessionId: Long, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        controller.appendTrack(sessionId, points)
    }
}
