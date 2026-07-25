package com.t1dm.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BG input-filter detent contract (INFERENCE.md §7.1). The Rust model-input path
 * (`build_context`) fails CLOSED on a window that is not odd and positive — it will not substitute a
 * default — so every layer that can hand it a number must snap first. These assertions pin the
 * properties the Rust guard relies on plus the coercion that stands between a hand-edited kv row and
 * a mid-cycle refusal.
 */
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
        // The default is today's shipped behaviour: an upgrade must not silently un-smooth the input.
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
        // The catastrophic cases: 0 (an empty tap set ⇒ an all-zero, then clamp-to-20, BG channel —
        // a confident flat line the FORECAST degeneracy guard would not catch) and any even value.
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
