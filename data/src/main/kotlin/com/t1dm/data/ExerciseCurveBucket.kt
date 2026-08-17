package com.t1dm.data

/**
 * One five-minute slot of a bout's glucose-disposal curve, as [T1dmRepository.recordExerciseCurve]
 * takes it: the grid instant, the local offset at that instant, the grams of carbohydrate equivalent
 * THIS bout now claims there, and the grams it claimed there before.
 *
 * The pair is what makes the write idempotent within a bout and additive across bouts — a bout's
 * curve is rewritten whole every time its duration grows, so every slot has to say which part of the
 * stored value is its own to replace. See [T1dmRepository.mergedExerciseGrams].
 *
 * [gridTs] may sit AHEAD of the clock. §5's curve runs ninety minutes past the session, and a curve
 * that stops at `now` does not sum to its event's total.
 */
data class ExerciseCurveBucket(
    val gridTs: Long,
    val tzOffsetMin: Int,
    val grams: Double,
    val priorGrams: Double,
)
