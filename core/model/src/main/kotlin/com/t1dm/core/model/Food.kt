package com.t1dm.core.model

/**
 * One entry of the glycemic dictionary (Phase 4 deliverable 3). A food is a
 * per-100 g nutrition fact plus an optional glycemic index; the meal builder scales it by a
 * portion (grams) and the curve engine turns `(gi, grams)` into a carb **appearance (Ra)** gamma
 * ([model-io-curves]). Nutrient facts are not copyrightable — see `FoodSeed` for provenance.
 *
 * [giOrNull] is the measured glycemic index (0..100); `null` means "unknown GI" and the resolver
 * falls back to a medium-GI default (or [customCurve] when present). [customCurve], when set, is a
 * user-authored **normalized appearance shape** (per-5-min buckets summing to 1.0) that OVERRIDES
 * the GI-derived gamma for this food; scaling it by the portion's carbs yields the Ra curve. This
 * is the food-builder's "draw your own curve" path (Phase 4 deliverable 4).
 */
data class Food(
    val id: Long,
    val name: String,
    /** Optional qualifier (variety / preparation), e.g. "boiled", "wholemeal". */
    val brand: String?,
    val carbsPer100g: Double,
    val giOrNull: Double?,
    val category: String,
    /** Provenance tag for the value's origin (e.g. "USDA FDC", "user"). */
    val source: String,
    /** True for a user-added food (editable/deletable); false for a bundled seed row. */
    val custom: Boolean,
    val customCurve: List<Double>? = null,
)
