package com.t1dm.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push-hold setting's persistence contract: how long a freshly logged meal/dose waits before its
 * first send attempt, and therefore how long the Logs panel can still withdraw it. Exercises the REAL
 * encode/decode `setPushHoldMin`/`currentPushHoldMin` route through; the Room I/O between them is the
 * same pass-through every other kv knob uses.
 *
 * Zero is the one value that must survive untouched — unlike the snooze, which is floored at a minute
 * because a silence has to be time-bounded, a zero hold is a legitimate choice (push at once, no
 * withdrawal window) and a floor would silently impose a delay nobody asked for.
 */
class PushHoldSettingTest {

    @Test
    fun `defaults to 15 minutes when unset`() {
        assertEquals(15, SettingsStore.DEFAULT_PUSH_HOLD_MIN)
        assertEquals(15, SettingsStore.decodePushHoldMin(null))
    }

    @Test
    fun `a set value round-trips`() {
        for (min in listOf(0, 5, 15, 45, SettingsStore.MAX_PUSH_HOLD_MIN)) {
            assertEquals(min, SettingsStore.decodePushHoldMin(SettingsStore.encodePushHoldMin(min)))
        }
    }

    @Test
    fun `zero survives as off rather than being floored into a delay`() {
        assertEquals(0, SettingsStore.decodePushHoldMin(SettingsStore.encodePushHoldMin(0)))
        assertEquals(0, SettingsStore.decodePushHoldMin("0"))
    }

    @Test
    fun `both directions clamp to the ceiling, so a wider build cannot outlive this one`() {
        val max = SettingsStore.MAX_PUSH_HOLD_MIN
        assertEquals(max, SettingsStore.decodePushHoldMin(SettingsStore.encodePushHoldMin(max + 1)))
        assertEquals(max, SettingsStore.decodePushHoldMin("100000"))
        assertEquals(0, SettingsStore.decodePushHoldMin("-30"))
    }

    @Test
    fun `garbage or empty falls back to the default`() {
        assertEquals(15, SettingsStore.decodePushHoldMin("xyz"))
        assertEquals(15, SettingsStore.decodePushHoldMin(""))
    }

    @Test
    fun `the default is on the slider's own 5-minute grain`() {
        assertEquals(0, SettingsStore.DEFAULT_PUSH_HOLD_MIN % 5)
        assertEquals(0, SettingsStore.MAX_PUSH_HOLD_MIN % 5)
        assertTrue(SettingsStore.MAX_PUSH_HOLD_MIN > SettingsStore.DEFAULT_PUSH_HOLD_MIN)
    }
}
