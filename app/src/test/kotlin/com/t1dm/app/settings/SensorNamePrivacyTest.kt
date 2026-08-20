package com.t1dm.app.settings

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The sensor-name privacy switch's DEFAULT and its PLACEMENT. Both are the load-bearing half of it: the
 * behaviour it gates is one `if`, while a wrong default or a wrong prefix leaks a serial without anyone
 * pressing anything.
 */
class SensorNamePrivacyTest {

    /** Hidden. There is no symmetry here to appeal to: a serial that reaches a screenshot cannot be
     *  recalled, and a name withheld from one costs the user nothing they cannot get from the CGM panel. */
    @Test
    fun `names are hidden until the user asks for them`() {
        assertFalse(SettingsStore.DEFAULT_SHOW_SENSOR_NAMES)
    }

    /**
     * Not exportable, for a stronger reason than the sensor-life knob beside it: a config file is carried
     * to another phone or handed to another person, and a privacy default that silently flipped on import
     * is wrong in one direction only.
     */
    @Test
    fun `the switch does not travel in a config export`() {
        assertFalse(SettingsStore.isConfigKey(SettingsStore.K_CGM_SHOW_SENSOR_NAMES))
        // `cgm.` is not a config PREFIX either, so a second knob added under it cannot join the export by
        // accident.
        assertFalse(SettingsStore.isConfigKey("cgm.anything_else"))
    }
}
