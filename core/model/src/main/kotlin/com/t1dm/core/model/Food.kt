package com.t1dm.core.model

/** Per-100g facts; [giOrNull] 0..100, null=unknown→medium; [customCurve] per-5-min sums to 1.0 */
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
