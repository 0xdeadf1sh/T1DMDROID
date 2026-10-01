package com.t1dm.data

/** priorGrams: this bout claim at gridTs before, idempotent within, additive across bouts. */
data class ExerciseCurveBucket(
    val gridTs: Long,
    val tzOffsetMin: Int,
    val grams: Double,
    val priorGrams: Double,
)
