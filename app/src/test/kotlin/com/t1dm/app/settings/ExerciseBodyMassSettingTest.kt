package com.t1dm.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The body-mass knob's placement and its round trip. Placement is the load-bearing half: this is the
 * one number that turns a bout into a kcal figure, and it is the user's own — a config export carries
 * settings to another phone or another person, and a body mass is neither.
 */
class ExerciseBodyMassSettingTest {

    @Test
    fun `body mass is not part of the exportable config set`() {
        assertFalse(SettingsStore.isConfigKey(SettingsStore.K_EXERCISE_BODY_MASS_KG))
        // `exercise.` is not a config PREFIX — the one exportable key under it is listed exactly, so
        // a second knob added to the panel does not join the export by accident.
        assertFalse(SettingsStore.isConfigKey("exercise.anything_else"))
    }

    @Test
    fun `a supplied mass round-trips`() {
        assertEquals(72.5, SettingsStore.decodeBodyMassKg(SettingsStore.encodeBodyMassKg(72.5))!!, 1e-9)
    }

    @Test
    fun `an unsupplied mass is absent, not zero`() {
        assertEquals("", SettingsStore.encodeBodyMassKg(null))
        assertNull(SettingsStore.decodeBodyMassKg(null))
        assertNull(SettingsStore.decodeBodyMassKg(""))
    }

    @Test
    fun `a value under the sanity floor is refused on both sides`() {
        assertEquals("", SettingsStore.encodeBodyMassKg(1.0))
        assertNull(SettingsStore.decodeBodyMassKg("1.0"))
        assertNull(SettingsStore.decodeBodyMassKg("-70"))
        assertNull(SettingsStore.decodeBodyMassKg("NaN"))
    }

    @Test
    fun `there is no upper limit`() {
        // Unbounded above, by the same rule that leaves the alarm thresholds unbounded.
        assertEquals(300.0, SettingsStore.decodeBodyMassKg("300.0")!!, 1e-9)
    }

    @Test
    fun `a garbled row reads as unsupplied rather than throwing on a panel`() {
        assertNull(SettingsStore.decodeBodyMassKg("seventy"))
    }
}
