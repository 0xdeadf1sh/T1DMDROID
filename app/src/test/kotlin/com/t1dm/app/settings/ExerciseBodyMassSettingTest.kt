package com.t1dm.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The body mass is the user's own number, so it stays out of a config export. */
class ExerciseBodyMassSettingTest {

    @Test
    fun `body mass is not part of the exportable config set`() {
        assertFalse(SettingsStore.isConfigKey(SettingsStore.K_EXERCISE_BODY_MASS_KG))
        // `exercise.` is not a config prefix; the one exportable key under it is listed exactly.
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
        assertEquals(300.0, SettingsStore.decodeBodyMassKg("300.0")!!, 1e-9)
    }

    @Test
    fun `a garbled row reads as unsupplied rather than throwing on a panel`() {
        assertNull(SettingsStore.decodeBodyMassKg("seventy"))
    }
}
