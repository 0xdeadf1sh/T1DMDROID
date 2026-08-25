package com.t1dm.data.meals

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.Food
import com.t1dm.core.model.MealComponent
import com.t1dm.core.model.ResolvedMealCurve
import com.t1dm.core.model.SavedMeal
import com.t1dm.data.T1dmRepository
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.MealCurveResolver
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.toBlob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.TimeZone

class MealsController(
    private val repository: T1dmRepository,
    private val resolver: MealCurveResolver,
    private val dispatchers: T1dmDispatchers,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun seedIfEmpty() = withContext(dispatchers.io) {
        if (repository.foodCount() == 0) {
            val ts = now()
            repository.seedFoods(
                FoodSeed.ROWS.map { r ->
                    FoodEntity(
                        name = r.name,
                        brand = r.brand,
                        carbsPer100g = r.carbsPer100g,
                        gi = r.gi,
                        category = r.category,
                        source = FoodSeed.SOURCE,
                        custom = false,
                        customCurve = null,
                        updatedAt = ts,
                    )
                },
            )
        }
    }

    suspend fun searchFoods(query: String, limit: Int = 30): List<Food> =
        repository.searchFoods(query, limit).map { it.toModel() }

    suspend fun browseFoods(limit: Int = 50): List<Food> =
        repository.browseFoods(limit).map { it.toModel() }

    val customFoods: Flow<List<Food>> =
        repository.observeCustomFoods().map { list -> list.map { it.toModel() } }

    val savedMeals: Flow<List<SavedMeal>> =
        repository.observeSavedMeals().map { headers ->
            headers.map { h ->
                SavedMeal(h.id, h.name, repository.savedMealItems(h.id).map { it.toComponent() })
            }
        }

    suspend fun resolvePreview(components: List<MealComponent>): ResolvedMealCurve =
        resolver.resolveCombined(components, startMs = 0L)

    suspend fun saveCustomFood(food: Food) =
        repository.upsertFood(food.toCustomEntity(now()))

    /** False when the row vanished or is a bundled seed row; see [T1dmRepository.updateCustomFood]. */
    suspend fun updateCustomFood(food: Food): Boolean =
        repository.updateCustomFood(food.toCustomEntity(now()))

    suspend fun deleteCustomFood(id: Long) = repository.deleteCustomFood(id)

    suspend fun saveMeal(name: String, components: List<MealComponent>): Long =
        repository.saveMeal(name, components.map { it.toItemEntity(0) }, now())

    /** False when the meal was deleted while it was being edited; nothing is written then. */
    suspend fun updateMeal(id: Long, name: String, components: List<MealComponent>): Boolean =
        repository.updateSavedMeal(id, name, components.map { it.toItemEntity(id) }, now())

    suspend fun deleteSavedMeal(id: Long) = repository.deleteSavedMeal(id)

    /** The stored [LoggedMealEntity.customCurve] IS the resolved curve (grams = total carbs), so the
     *  carb channel reproduces it exactly whatever the presets become later. */
    suspend fun logMeal(components: List<MealComponent>, tsMs: Long = now()): LoggedMealEntity =
        withContext(dispatchers.io) {
            // Round-to-nearest, not floor, so this lands in the SAME slot as the CGM/single-food/dose
            // writers (`repository.snapToGrid`, `GridStamper.snap`); a floor snap misaligns the carb
            // channel against BG by up to one step.
            val gridTs = Math.floorDiv(tsMs + CurveEngine.STEP_MS / 2, CurveEngine.STEP_MS) * CurveEngine.STEP_MS
            val resolved = resolver.resolveCombined(components, gridTs)
            val tz = TimeZone.getDefault().getOffset(gridTs) / 60_000
            repository.logMeal(
                LoggedMealEntity(
                    clientId = "",
                    tsMs = gridTs,
                    grams = resolved.totalCarbs,
                    gi = null,
                    k = null,
                    theta = null,
                    durationMin = (resolved.values.size * (CurveEngine.STEP_MS / 60_000.0)),
                    customCurve = if (resolved.values.isEmpty()) null else resolved.values.toBlob(),
                    tzOffsetMin = tz,
                    note = null,
                    updatedAt = now(),
                ),
            )
        }
}
