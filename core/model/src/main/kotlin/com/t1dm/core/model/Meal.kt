package com.t1dm.core.model

/** Snapshots the food's carbs-per-100 g, GI and custom shape as they were when it was added, so a
 *  saved meal survives a later edit or deletion of the [Food]. [foodId] is a soft link back to the
 *  dictionary, null for an ad-hoc entry. */
data class MealComponent(
    val foodId: Long?,
    val name: String,
    val grams: Double,
    val carbsPer100g: Double,
    val giOrNull: Double?,
    val customCurve: List<Double>? = null,
) {
    /** Grams of carbohydrate in this portion. */
    val carbs: Double get() = carbsPer100g * grams / 100.0
}

data class SavedMeal(
    val id: Long,
    val name: String,
    val components: List<MealComponent>,
) {
    val totalCarbs: Double get() = components.sumOf { it.carbs }
}

/** The combined carb appearance (Ra) curve the model consumes on its carb channel: [values] are
 *  grams per [stepMs] and integrate to [totalCarbs] over the window. */
data class ResolvedMealCurve(
    val totalCarbs: Double,
    val values: List<Double>,
    val stepMs: Long,
) {
    /** Minutes from the meal start to the rate maximum. */
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
