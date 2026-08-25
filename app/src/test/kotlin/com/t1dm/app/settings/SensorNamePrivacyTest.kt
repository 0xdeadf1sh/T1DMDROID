package com.t1dm.app.settings

import org.junit.Assert.assertFalse
import org.junit.Test

class SensorNamePrivacyTest {

    /** Hidden by default: a serial that reaches a screenshot cannot be recalled. */
    @Test
    fun `names are hidden until the user asks for them`() {
        assertFalse(SettingsStore.DEFAULT_SHOW_SENSOR_NAMES)
    }

    /** A privacy default that flipped on import is wrong in one direction only. */
    @Test
    fun `the switch does not travel in a config export`() {
        assertFalse(SettingsStore.isConfigKey(SettingsStore.K_CGM_SHOW_SENSOR_NAMES))
        // `cgm.` is not a config prefix either, so a later knob under it cannot join the export.
        assertFalse(SettingsStore.isConfigKey("cgm.anything_else"))
    }
}
