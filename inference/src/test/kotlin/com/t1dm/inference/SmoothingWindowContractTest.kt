package com.t1dm.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** INFERENCE.md §7.1. `build_context` fails closed on a window that is not odd and positive, so
 *  every caller must snap first. */
class SmoothingWindowContractTest {

    @Test
    fun `every offered stop is odd, positive, and includes the default`() {
        assertTrue("stops must be non-empty", InferenceControllerDefaults.SAVGOL_STOPS.isNotEmpty())
        InferenceControllerDefaults.SAVGOL_STOPS.forEach {
            assertTrue("window $it must be >= 1", it >= 1)
            assertTrue("window $it must be odd", it % 2 == 1)
        }
        assertTrue(
            "the default must itself be an offered stop",
            InferenceControllerDefaults.SAVGOL_WINDOW in InferenceControllerDefaults.SAVGOL_STOPS,
        )
        // An upgrade must not silently un-smooth the input.
        assertEquals(7, InferenceControllerDefaults.SAVGOL_WINDOW)
        assertEquals(listOf(1, 7, 13, 19, 25), InferenceControllerDefaults.SAVGOL_STOPS)
    }

    @Test
    fun `an offered stop survives coercion unchanged`() {
        InferenceControllerDefaults.SAVGOL_STOPS.forEach {
            assertEquals(it, InferenceControllerDefaults.nearestSmoothingStop(it))
        }
    }

    @Test
    fun `a garbage window snaps onto a legal stop rather than reaching the Rust guard`() {
        // 0 empties the tap set ⇒ a flat clamp-to-20 BG channel the degeneracy guard misses.
        listOf(0, -1, -1000, 2, 8, 12, 26, 400, Int.MIN_VALUE, Int.MAX_VALUE).forEach { bad ->
            val snapped = InferenceControllerDefaults.nearestSmoothingStop(bad)
            assertTrue("$bad snapped to $snapped, not an offered stop", snapped in InferenceControllerDefaults.SAVGOL_STOPS)
        }
        assertEquals(1, InferenceControllerDefaults.nearestSmoothingStop(0))
        assertEquals(1, InferenceControllerDefaults.nearestSmoothingStop(Int.MIN_VALUE))
        assertEquals(25, InferenceControllerDefaults.nearestSmoothingStop(Int.MAX_VALUE))
        assertEquals(7, InferenceControllerDefaults.nearestSmoothingStop(8))
        assertEquals(13, InferenceControllerDefaults.nearestSmoothingStop(12))
    }
}
