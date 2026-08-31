package com.t1dm.data.curve

import com.t1dm.data.ExerciseCurveBucket
import com.t1dm.data.T1dmRepository

/**
 * Bucket `i` of [values] is the five minutes beginning `i` steps after [startMs]'s slot; the snap is
 * the repository's, per `../T1DMCOMMON/SPEC/invariants.md` §1. [written] is what the SAME event last
 * claimed in each slot, which makes the write idempotent within an event and additive across events.
 * A slot whose value has not moved is dropped — the write would mint the row and an `INGEST` push
 * for nothing.
 */
fun exerciseCurveBuckets(
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
        out.add(ExerciseCurveBucket(gridTs, tzOffsetMinAt(gridTs), values[i], prior))
    }
    return out
}

/** The slots a curve laid at [gridStart] ADDS to: nothing of it is in them yet. */
internal fun exerciseCurveLaid(
    gridStart: Long,
    values: DoubleArray,
    tzOffsetMinAt: (Long) -> Int,
): List<ExerciseCurveBucket> = exerciseCurveBuckets(gridStart, values, emptyMap(), tzOffsetMinAt)

/**
 * The slots a curve laid at [gridStart] REMOVES from: it claimed [values] and now claims nothing, so
 * `priorGrams` is its own share alone and an overlapping event's is left exactly where it is.
 */
internal fun exerciseCurveTaken(
    gridStart: Long,
    values: DoubleArray,
    tzOffsetMinAt: (Long) -> Int,
): List<ExerciseCurveBucket> {
    val claimed = HashMap<Long, Double>(values.size * 2)
    for (i in values.indices) claimed[gridStart + i * T1dmRepository.GRID_MS] = values[i]
    return exerciseCurveBuckets(gridStart, DoubleArray(values.size), claimed, tzOffsetMinAt)
}
