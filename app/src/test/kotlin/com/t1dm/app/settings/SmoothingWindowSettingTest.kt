package com.t1dm.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BG input-filter setting's placement and default contract. The Room I/O around it is a
 * pass-through identical to every other `inference.` knob, so what is worth pinning here is what a
 * mistake would cost silently: a default other than 7 would un-smooth the model input on every
 * existing install at upgrade with no visible cause, and an `inference.` key is invisible to config
 * export unless it is hand-listed (there is deliberately no blanket `inference.` prefix, since that
 * would sweep runtime telemetry into the backup).
 */
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
        // ...and the prefix really is not blanket-exported, which is why the entry is needed.
        assertTrue(!SettingsStore.isConfigKey("inference.some_runtime_telemetry"))
    }
}
