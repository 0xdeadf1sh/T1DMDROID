package com.t1dm.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings-search history's persistence contract, extracted from [SettingsStore] for the same
 * reason the snooze pair is: the round-trip and the fail-soft read are then host-testable without Room.
 *
 * The read is deliberately tolerant. `importJson` writes allowlisted keys through unvalidated, and a
 * hand-edited or truncated row must degrade to "no history" rather than throw on a settings page.
 */
class RecentSearchesTest {

    @Test
    fun `round-trips newest first`() {
        val encoded = SettingsStore.encodeRecentSearches(listOf("dnd", "buzz", "savgol"))
        assertEquals(listOf("dnd", "buzz", "savgol"), SettingsStore.decodeRecentSearches(encoded))
    }

    @Test
    fun `keeps only the last three`() {
        val encoded = SettingsStore.encodeRecentSearches(listOf("a", "b", "c", "d", "e"))
        assertEquals(listOf("a", "b", "c"), SettingsStore.decodeRecentSearches(encoded))
    }

    @Test
    fun `trims and drops empties`() {
        val encoded = SettingsStore.encodeRecentSearches(listOf("  theme  ", "", "   ", "font"))
        assertEquals(listOf("theme", "font"), SettingsStore.decodeRecentSearches(encoded))
    }

    @Test
    fun `an unset or blank row reads as no history`() {
        assertEquals(emptyList<String>(), SettingsStore.decodeRecentSearches(null))
        assertEquals(emptyList<String>(), SettingsStore.decodeRecentSearches(""))
        assertEquals(emptyList<String>(), SettingsStore.decodeRecentSearches("   "))
        assertEquals(emptyList<String>(), SettingsStore.decodeRecentSearches("\n\n\n"))
    }

    @Test
    fun `a hand-written row is read as far as it goes rather than refused`() {
        // `importJson` writes allowlisted keys through unvalidated, so the decoder must be total.
        assertEquals(listOf("theme"), SettingsStore.decodeRecentSearches("theme"))
        assertEquals(listOf("a", "b", "c"), SettingsStore.decodeRecentSearches("a\nb\nc\nd\ne"))
    }

    @Test
    fun `a smuggled newline cannot become a second entry`() {
        val encoded = SettingsStore.encodeRecentSearches(listOf("one\ntwo", "three"))
        assertEquals(listOf("one two", "three"), SettingsStore.decodeRecentSearches(encoded))
    }

    @Test
    fun `an empty history clears the row rather than leaving a stale one`() {
        val encoded = SettingsStore.encodeRecentSearches(emptyList())
        assertEquals(emptyList<String>(), SettingsStore.decodeRecentSearches(encoded))
    }

    @Test
    fun `the history key is not exportable`() {
        // A `ui.` key would ship the user's typed queries inside every configuration backup they hand
        // to someone; the same deliberate exclusion covers cgm.sensor_life_days and death.enabled.
        assertTrue(!SettingsStore.isConfigKey("search.recent_settings"))
    }
}
