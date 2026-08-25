package com.t1dm.data

/**
 * One five-minute slot of a bout's glucose-disposal curve. [priorGrams] is what THIS bout claimed
 * at [gridTs] before, which makes the write idempotent within a bout and additive across bouts.
 * [gridTs] may sit AHEAD of the clock: §5's curve runs ninety minutes past the session.
 */
data class ExerciseCurveBucket(
    val gridTs: Long,
    val tzOffsetMin: Int,
    val grams: Double,
    val priorGrams: Double,
)
