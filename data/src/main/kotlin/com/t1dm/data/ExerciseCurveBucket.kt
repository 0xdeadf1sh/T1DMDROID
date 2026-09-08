package com.t1dm.data

/** 5-min slot; [priorGrams] makes writes idempotent/bout, additive across; §5 runs 90min past. */
data class ExerciseCurveBucket(
    val gridTs: Long,
    val tzOffsetMin: Int,
    val grams: Double,
    val priorGrams: Double,
)
