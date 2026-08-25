package com.t1dm.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** §3.6 C1. Pins the encode/decode round-trip; the Room I/O between them is a pass-through. */
class SnoozeDurationSettingTest {

    @Test
    fun `defaults to 15 minutes when unset`() {
        assertEquals(15, SettingsStore.DEFAULT_SNOOZE_MIN)
        assertEquals(15, SettingsStore.decodeSnoozeMin(null))
    }

    @Test
    fun `a set value round-trips`() {
        assertEquals(30, SettingsStore.decodeSnoozeMin(SettingsStore.encodeSnoozeMin(30)))
        assertEquals(60, SettingsStore.decodeSnoozeMin(SettingsStore.encodeSnoozeMin(60)))
        assertEquals(1, SettingsStore.decodeSnoozeMin(SettingsStore.encodeSnoozeMin(1)))
    }

    @Test
    fun `writes floor the snooze at 1 minute so it is always time-bounded`() {
        assertEquals(1, SettingsStore.decodeSnoozeMin(SettingsStore.encodeSnoozeMin(0)))
        assertEquals(1, SettingsStore.decodeSnoozeMin(SettingsStore.encodeSnoozeMin(-5)))
    }

    @Test
    fun `garbage or empty falls back to the default`() {
        assertEquals(15, SettingsStore.decodeSnoozeMin("xyz"))
        assertEquals(15, SettingsStore.decodeSnoozeMin(""))
    }
}
