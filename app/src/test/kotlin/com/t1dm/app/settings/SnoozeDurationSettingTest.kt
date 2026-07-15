package com.t1dm.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The snooze-duration setting's persistence contract (§3.6 C1). Exercises the REAL encode/decode the
 * [SettingsStore] routes `setSnoozeMin`/`currentSnoozeMin` through — the Room I/O between them is a
 * pass-through identical to every other alarm knob, so this pins the value round-trip (default, floor,
 * garbage) without an on-device / Robolectric harness.
 */
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
