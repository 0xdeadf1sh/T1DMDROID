package com.t1dm.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log-driven re-forecast debounce's persistence contract. The Room I/O around it is a
 * pass-through like every other `inference.` knob, so what is worth pinning is what a mistake would
 * cost quietly: the window governs how promptly a logged meal or dose reaches the model, a value past
 * the 5-min grid would let the "prompt" cycle land after the tick it exists to anticipate, and an
 * `inference.` key is invisible to config export unless hand-listed.
 */
class ReforecastDebounceSettingTest {

    @Test
    fun `defaults to a four-second window`() {
        assertEquals(4, SettingsStore.DEFAULT_LOG_REFORECAST_DEBOUNCE_S)
        assertEquals(4, SettingsStore.decodeLogReforecastDebounceS(null))
    }

    @Test
    fun `the ceiling stays well under the five-minute grid`() {
        assertTrue(
            "a debounce at or past the grid defers the cycle past the tick it pre-empts",
            SettingsStore.MAX_LOG_REFORECAST_DEBOUNCE_S < 5 * 60,
        )
    }

    @Test
    fun `both directions clamp, and zero survives as re-run at once`() {
        assertEquals("0", SettingsStore.encodeLogReforecastDebounceS(0))
        assertEquals(0, SettingsStore.decodeLogReforecastDebounceS("0"))
        assertEquals(
            SettingsStore.MAX_LOG_REFORECAST_DEBOUNCE_S.toString(),
            SettingsStore.encodeLogReforecastDebounceS(9_000),
        )
        assertEquals("0", SettingsStore.encodeLogReforecastDebounceS(-30))
        // A value a wider-ceilinged build (or a hand-edited backup) persisted cannot outlive this one.
        assertEquals(
            SettingsStore.MAX_LOG_REFORECAST_DEBOUNCE_S,
            SettingsStore.decodeLogReforecastDebounceS("9000"),
        )
        assertEquals(0, SettingsStore.decodeLogReforecastDebounceS("-30"))
    }

    @Test
    fun `garbage reads as the default rather than throwing on a settings page`() {
        assertEquals(4, SettingsStore.decodeLogReforecastDebounceS("soon"))
        assertEquals(4, SettingsStore.decodeLogReforecastDebounceS(""))
    }

    @Test
    fun `the key is exportable despite living under the non-exported inference prefix`() {
        assertTrue(SettingsStore.K_INF_LOG_DEBOUNCE_S.startsWith("inference."))
        assertTrue(
            "an inference.* key must be hand-listed in CONFIG_EXACT_KEYS to survive a config backup",
            SettingsStore.isConfigKey(SettingsStore.K_INF_LOG_DEBOUNCE_S),
        )
    }
}
