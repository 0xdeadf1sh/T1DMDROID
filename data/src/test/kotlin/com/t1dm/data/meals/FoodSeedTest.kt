package com.t1dm.data.meals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM integrity checks on the bundled glycemic dictionary ([FoodSeed]) — pure Kotlin, no device.
 * Guards the invariants the re-seed migration ([com.t1dm.data.db.MigrationRunner.MIGRATION_5_6]) and
 * the GI→gamma mapping depend on: a full catalogue, a UNIQUE `(name, brand)` natural key (the
 * migration's `NOT EXISTS` dedup matches on it), and GI/carb values inside the ranges the curve
 * engine tolerates. The on-device count + FTS search live in the instrumented `FoodSeedDbTest`.
 */
class FoodSeedTest {

    @Test
    fun catalogueSpansTheRequestedBreadth() {
        assertTrue("expected a grown ~300-500 catalogue, got ${FoodSeed.ROWS.size}", FoodSeed.ROWS.size >= 300)
        // A diet-spanning spread: every headline food group is represented.
        val categories = FoodSeed.ROWS.map { it.category }.toSet()
        val expected = setOf(
            "Fruit", "Beverage", "Bread", "Bakery", "Cereal", "Grain", "Pasta", "Vegetable",
            "Legume", "Dairy", "Protein", "Snack", "Sweet", "Sugar", "Spread", "Nut", "Seed", "Prepared",
        )
        assertTrue("missing categories: ${expected - categories}", categories.containsAll(expected))
    }

    @Test
    fun naturalKeyIsUnique() {
        // MIGRATION_5_6 dedups on (name, brand); a collision would silently drop a food on upgrade.
        val keys = FoodSeed.ROWS.map { it.name to it.brand }
        assertEquals("duplicate (name, brand) rows present", keys.size, keys.toSet().size)
    }

    @Test
    fun carbAndGiValuesAreInRange() {
        FoodSeed.ROWS.forEach { r ->
            assertTrue("negative carbs for ${r.name}", r.carbsPer100g >= 0.0)
            assertTrue("carbs over 100g for ${r.name}", r.carbsPer100g <= 100.0)
            assertTrue("blank category for ${r.name}", r.category.isNotBlank())
            r.gi?.let { assertTrue("GI out of [0,110] for ${r.name}: $it", it in 0.0..110.0) }
        }
    }
}
