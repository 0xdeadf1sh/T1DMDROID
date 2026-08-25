package com.t1dm.core.model

/**
 * Per-100 g facts; the meal builder scales by portion grams and the curve engine turns
 * `(gi, grams)` into a carb appearance (Ra) gamma. [giOrNull] is 0..100; null is unknown GI and the
 * resolver falls back to medium. [customCurve] is per-5-min buckets summing to 1.0, and overrides
 * the GI-derived gamma.
 */
data class Food(
    val id: Long,
    val name: String,
    /** Variety or preparation, e.g. "boiled", "wholemeal". */
    val brand: String?,
    val carbsPer100g: Double,
    val giOrNull: Double?,
    val category: String,
    /** e.g. "USDA FDC", "user". */
    val source: String,
    val custom: Boolean,
    val customCurve: List<Double>? = null,
)
