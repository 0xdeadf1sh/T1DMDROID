package com.t1dm.core.model

/**
 * One food + portion in a meal being built (Phase 4). A component snapshots the
 * food's carbs-per-100 g, GI, and optional custom appearance shape at the moment it was added, so a
 * saved meal survives a later edit or deletion of the underlying [Food]. [foodId] is a soft link
 * back to the dictionary (null for an ad-hoc entry).
 */
data class MealComponent(
    val foodId: Long?,
    val name: String,
    val grams: Double,
    val carbsPer100g: Double,
    val giOrNull: Double?,
    val customCurve: List<Double>? = null,
) {
    /** Absorbable carbohydrate for this portion (g). */
    val carbs: Double get() = carbsPer100g * grams / 100.0
}

/** A named, reusable multi-food meal (Phase 4 "saved meals"). */
data class SavedMeal(
    val id: Long,
    val name: String,
    val components: List<MealComponent>,
) {
    val totalCarbs: Double get() = components.sumOf { it.carbs }
}

/**
 * The resolved combined carb **appearance (Ra)** curve for a set of [MealComponent]s
 * ([model-io-curves]): what the model actually consumes on its carb channel. [values] are
 * grams-per-[stepMs] and integrate to [totalCarbs] over the window; [MealCurveResolver] mixes the
 * per-food gamma/custom shapes into this single curve. Used for the live meal-builder preview and,
 * once logged, as the `logged_meal` appearance override.
 */
data class ResolvedMealCurve(
    val totalCarbs: Double,
    val values: List<Double>,
    val stepMs: Long,
) {
    /** Minutes from the meal start to the appearance peak (the rate maximum). */
    val peakMin: Double
        get() {
            if (values.isEmpty()) return 0.0
            val i = values.indices.maxByOrNull { values[it] } ?: 0
            return (i + 1) * (stepMs / 60_000.0)
        }

    companion object {
        val EMPTY = ResolvedMealCurve(0.0, emptyList(), 300_000L)
    }
}
