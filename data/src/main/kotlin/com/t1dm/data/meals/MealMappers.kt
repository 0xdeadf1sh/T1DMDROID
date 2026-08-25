package com.t1dm.data.meals

import com.t1dm.core.model.Food
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinType
import com.t1dm.core.model.MealComponent
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.SavedMealItemEntity
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.toDoubleList

internal fun FoodEntity.toModel(): Food = Food(
    id = id,
    name = name,
    brand = brand,
    carbsPer100g = carbsPer100g,
    giOrNull = gi,
    category = category,
    source = source,
    custom = custom,
    customCurve = customCurve?.toDoubleList(),
)

internal fun Food.toCustomEntity(nowMs: Long): FoodEntity = FoodEntity(
    id = id,
    name = name,
    brand = brand,
    carbsPer100g = carbsPer100g,
    gi = giOrNull,
    category = category.ifBlank { "Custom" },
    source = "user",
    custom = true,
    customCurve = customCurve?.toBlob(),
    updatedAt = nowMs,
)

internal fun Food.toComponent(grams: Double): MealComponent = MealComponent(
    foodId = id,
    name = name,
    grams = grams,
    carbsPer100g = carbsPer100g,
    giOrNull = giOrNull,
    customCurve = customCurve,
)

internal fun SavedMealItemEntity.toComponent(): MealComponent = MealComponent(
    foodId = foodId,
    name = name,
    grams = grams,
    carbsPer100g = carbsPer100g,
    giOrNull = gi,
    customCurve = customCurve?.toDoubleList(),
)

internal fun MealComponent.toItemEntity(mealId: Long): SavedMealItemEntity = SavedMealItemEntity(
    mealId = mealId,
    foodId = foodId,
    name = name,
    grams = grams,
    carbsPer100g = carbsPer100g,
    gi = giOrNull,
    customCurve = customCurve?.toBlob(),
)

internal fun InsulinTypeEntity.toModel(): InsulinType = InsulinType(
    id = id,
    name = name,
    kind = if (kind == DoseKind.BOLUS) InsulinKind.BOLUS else InsulinKind.BASAL,
    durationMin = durationMin,
    k = k,
    theta = theta,
    kaPerHour = kaPerHour,
    kePerHour = kePerHour,
    customCurve = customCurve?.toDoubleList(),
    builtin = builtin,
)

internal fun InsulinType.toEntity(nowMs: Long): InsulinTypeEntity = InsulinTypeEntity(
    id = id,
    name = name,
    kind = if (kind == InsulinKind.BOLUS) DoseKind.BOLUS else DoseKind.BASAL,
    durationMin = durationMin,
    k = k,
    theta = theta,
    kaPerHour = kaPerHour,
    kePerHour = kePerHour,
    customCurve = customCurve?.toBlob(),
    builtin = builtin,
    updatedAt = nowMs,
)
