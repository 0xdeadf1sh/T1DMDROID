package com.t1dm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KovatchevScale] against the Rust core's golden fixture, `crates/t1dm-core/src/testdata/golden.json`.
 *  Tolerance 1e-9: both reach the platform libm, which is not guaranteed bit-identical. */
class KovatchevScaleTest {

    private val golden: String by lazy {
        requireNotNull(javaClass.classLoader.getResourceAsStream("golden.json")) {
            "golden.json missing from the test classpath (core/common/build.gradle.kts wires it in)"
        }.bufferedReader().use { it.readText() }
    }

    /** Flat arrays of doubles; a bracket slice beats a JSON parser this module does not need. */
    private fun doubles(key: String): List<Double> {
        val at = golden.indexOf("\"$key\"")
        assertTrue("golden.json has no \"$key\"", at >= 0)
        val open = golden.indexOf('[', at)
        val close = golden.indexOf(']', open)
        assertTrue("\"$key\" is not a flat array", open in 0 until close)
        return golden.substring(open + 1, close).split(',').map { it.trim().toDouble() }
    }

    private fun assertPairs(riskKey: String, mgdlKey: String) {
        val risk = doubles(riskKey)
        val mgdl = doubles(mgdlKey)
        assertEquals("$riskKey/$mgdlKey length mismatch", risk.size, mgdl.size)
        assertTrue("$riskKey is empty", risk.isNotEmpty())
        for (i in risk.indices) {
            assertEquals("fInv($riskKey[$i])", mgdl[i], KovatchevScale.fInv(risk[i]), 1e-9)
            assertEquals("f($mgdlKey[$i])", risk[i], KovatchevScale.f(mgdl[i]), 1e-9)
        }
    }

    @Test fun matches_golden_quantile_bands_both_ways() = assertPairs("q_tau_risk", "bands_mgdl")

    @Test fun matches_golden_median_both_ways() = assertPairs("median_risk", "median_bg")

    @Test fun matches_inference_md_reference_values() {
        val cases = listOf(
            20.0 to -3.1629,
            70.0 to -0.8806,
            100.0 to -0.2196,
            180.0 to 0.8792,
            400.0 to 2.3884,
            500.0 to 2.8133,
        )
        for ((g, want) in cases) assertEquals("f($g)", want, KovatchevScale.f(g), 1e-3)
        for ((r, want) in listOf(-3.1629 to 20.0, -0.2196 to 100.0, 0.8792 to 180.0)) {
            assertEquals("fInv($r)", want, KovatchevScale.fInv(r), 0.05)
        }
    }

    @Test fun risk_bounds_are_the_clamp_endpoints() {
        assertEquals(KovatchevScale.f(KovatchevScale.BG_MIN), KovatchevScale.RISK_MIN, 0.0)
        assertEquals(KovatchevScale.f(KovatchevScale.BG_MAX), KovatchevScale.RISK_MAX, 0.0)
        assertEquals(-3.1629338977967274, KovatchevScale.RISK_MIN, 1e-12)
        assertEquals(2.8133326390541624, KovatchevScale.RISK_MAX, 1e-12)
    }

    @Test fun round_trips_across_the_physical_range() {
        for (g in listOf(20.0, 55.0, 70.0, 100.0, 120.0, 180.0, 250.0, 400.0, 500.0)) {
            assertEquals("fInv(f($g))", g, KovatchevScale.fInv(KovatchevScale.f(g)), 1e-6)
        }
    }

    /** The display chrome calls these off a persisted snapshot and must never read out NaN. */
    @Test fun guards_are_total() {
        for (r in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1e9, 1e9)) {
            val g = KovatchevScale.fInv(r)
            assertTrue("fInv($r) = $g", g.isFinite() && g >= KovatchevScale.BG_MIN && g <= KovatchevScale.BG_MAX)
        }
        for (g in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -50.0, 1e9)) {
            assertTrue("f($g)", KovatchevScale.f(g).isFinite())
        }
        // NaN is scrubbed to the LOW bound, not propagated (the crate's `kovatchev_f` contract).
        assertEquals(KovatchevScale.RISK_MIN, KovatchevScale.f(Double.NaN), 0.0)
        assertEquals(KovatchevScale.RISK_MAX, KovatchevScale.f(Double.POSITIVE_INFINITY), 0.0)
    }
}
