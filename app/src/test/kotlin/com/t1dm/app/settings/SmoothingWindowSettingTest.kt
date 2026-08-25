package com.t1dm.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothingWindowSettingTest {

    @Test
    fun `defaults to the shipped 7-sample window`() {
        assertEquals(7, SettingsStore.DEFAULT_SAVGOL_WINDOW)
        assertTrue(SettingsStore.DEFAULT_SAVGOL_WINDOW in SettingsStore.SAVGOL_STOPS)
    }

    @Test
    fun `every offered stop is odd and positive`() {
        // The Rust model-input guard rejects anything else outright rather than substituting.
        SettingsStore.SAVGOL_STOPS.forEach {
            assertTrue("window $it must be >= 1", it >= 1)
            assertTrue("window $it must be odd", it % 2 == 1)
        }
    }

    @Test
    fun `the key is exportable despite living under the non-exported inference prefix`() {
        assertTrue(SettingsStore.K_INF_SAVGOL_WINDOW.startsWith("inference."))
        assertTrue(
            "an inference.* key must be hand-listed in CONFIG_EXACT_KEYS to survive a config backup",
            SettingsStore.isConfigKey(SettingsStore.K_INF_SAVGOL_WINDOW),
        )
        assertTrue(!SettingsStore.isConfigKey("inference.some_runtime_telemetry"))
    }
}
