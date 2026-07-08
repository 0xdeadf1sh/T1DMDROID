package com.t1dm.data.meals

import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.MealComponent
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.GiToGamma
import com.t1dm.data.curve.MealCurveResolver
import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the GI→gamma mapping and the multi-food carb-appearance mixer over
 * [StubNativeCore] (the Kotlin port of `t1dm-core::curve`). Per PLAN §3.3 the real fidelity check
 * is counterfactual sign/monotonicity — more carbs ⇒ larger curve, higher GI ⇒ earlier peak — not
 * byte-equality against a stochastic run.
 */
class MealCurveResolverTest {

    private val dispatchers = DefaultT1dmDispatchers(
        main = Dispatchers.Unconfined,
        default = Dispatchers.Unconfined,
        io = Dispatchers.Unconfined,
        inference = Dispatchers.Unconfined,
    )
    private val engine = CurveEngine(StubNativeCore(), dispatchers)
    private val resolver = MealCurveResolver(engine)

    private fun food(name: String, grams: Double, gi: Double?, carbsPer100g: Double = 100.0) =
        MealComponent(foodId = null, name = name, grams = grams, carbsPer100g = carbsPer100g, giOrNull = gi)

    @Test
    fun giToGamma_highGi_peaks_earlier_and_sharper_than_lowGi() {
        val high = GiToGamma.paramsForGi(85.0) // juice-like
        val low = GiToGamma.paramsForGi(30.0)  // legume-like
        assertTrue("high-GI gamma shape k smaller", high.k < low.k)
        assertTrue("high-GI scale theta smaller", high.theta < low.theta)
        // gamma mode = (k-1)·theta; high GI should peak earlier.
        assertTrue((high.k - 1) * high.theta < (low.k - 1) * low.theta)
        // null GI falls back to the medium default.
        assertEquals(GiToGamma.paramsForGi(GiToGamma.DEFAULT_GI), GiToGamma.paramsForGiOrDefault(null))
    }

    @Test
    fun singleComponent_curve_integrates_to_its_carbs() = runTest {
        val comp = food("Apple", grams = 200.0, gi = 40.0, carbsPer100g = 14.0) // 28 g carbs
        val curve = resolver.resolveCombined(listOf(comp), startMs = 0L)
        assertEquals(28.0, curve.totalCarbs, 1e-9)
        assertEquals(28.0, curve.values.sum(), 1e-6)
        assertTrue(curve.values.all { it >= 0.0 })
    }

    @Test
    fun combined_meal_sums_components_and_higher_gi_peaks_earlier() = runTest {
        val juice = food("Juice", grams = 100.0, gi = 90.0)  // 100 g carbs, fast
        val lentils = food("Lentils", grams = 100.0, gi = 25.0) // 100 g carbs, slow
        val combined = resolver.resolveCombined(listOf(juice, lentils), startMs = 0L)
        assertEquals(200.0, combined.totalCarbs, 1e-9)
        assertEquals(200.0, combined.values.sum(), 1e-6)

        val juiceOnly = resolver.resolveCombined(listOf(juice), startMs = 0L)
        val lentilOnly = resolver.resolveCombined(listOf(lentils), startMs = 0L)
        assertTrue("high-GI juice peaks earlier than low-GI lentils", juiceOnly.peakMin < lentilOnly.peakMin)
    }

    @Test
    fun customCurve_component_scales_normalized_shape_to_carbs() = runTest {
        // A normalized 3-bucket shape (sums to 1.0) for a 50 g-carb portion.
        val comp = MealComponent(
            foodId = null, name = "Custom", grams = 100.0, carbsPer100g = 50.0, giOrNull = null,
            customCurve = listOf(0.2, 0.5, 0.3),
        )
        val curve = resolver.resolveCombined(listOf(comp), startMs = 0L)
        assertEquals(50.0, curve.values.sum(), 1e-9)
        // Shape preserved: middle bucket is the peak.
        assertEquals(25.0, curve.values[1], 1e-9)
    }

    @Test
    fun zeroCarb_component_contributes_nothing_to_appearance() = runTest {
        val chicken = food("Chicken", grams = 150.0, gi = null, carbsPer100g = 0.0)
        val curve = resolver.resolveCombined(listOf(chicken), startMs = 0L)
        assertEquals(0.0, curve.totalCarbs, 1e-12)
        assertTrue(curve.values.all { it == 0.0 })
    }
}
