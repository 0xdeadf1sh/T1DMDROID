package com.t1dm.app.di

import com.t1dm.app.settings.SettingsStore
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsulinPresetResolutionTest {

    private fun rapid(label: String, peak: Double) =
        InsulinPresetSpec(InsulinFamily.RapidExp, label, peak, 360.0, 0.0, 0.0, true, "cite")

    private fun basal(label: String, diaH: Double) =
        InsulinPresetSpec(InsulinFamily.BasalBateman, label, 0.0, diaH * 60.0, 0.30, 0.07, true, "cite")

    private val catalog = listOf(
        rapid("Aspart · NovoRapid/Novolog", 75.0),
        rapid("Faster aspart · Fiasp", 55.0),
        rapid("Lispro · Humalog", 75.0),
        rapid("Ultra-rapid lispro · Lyumjev", 45.0),
        basal("Glargine U100 · Lantus", 24.0),
        basal("Glargine U300 · Toujeo", 36.0),
        basal("Degludec · Tresiba", 42.0),
    )

    private fun resolve(family: InsulinFamily, requested: String?, lastLogged: String? = null) =
        resolveInsulinPreset(catalog, family, requested, lastLogged)

    @Test
    fun `what the panel picked is what the writer commits`() {
        val picked = resolve(InsulinFamily.RapidExp, "Faster aspart · Fiasp")
        assertEquals("Faster aspart · Fiasp", picked?.label)
        assertEquals(55.0, picked!!.peakMin, 0.0)
    }

    @Test
    fun `the pick beats the remembered insulin`() {
        val picked = resolve(InsulinFamily.RapidExp, "Lispro · Humalog", lastLogged = "Faster aspart · Fiasp")
        assertEquals("Lispro · Humalog", picked?.label)
    }

    @Test
    fun `a caller with no pick inherits the last logged insulin of that kind`() {
        assertEquals(
            "Degludec · Tresiba",
            resolve(InsulinFamily.BasalBateman, requested = null, lastLogged = "Degludec · Tresiba")?.label,
        )
    }

    @Test
    fun `a fresh install falls back to the head of the family`() {
        assertEquals("Aspart · NovoRapid/Novolog", resolve(InsulinFamily.RapidExp, null)?.label)
        assertEquals("Glargine U100 · Lantus", resolve(InsulinFamily.BasalBateman, null)?.label)
    }

    @Test
    fun `an unknown label falls through instead of failing`() {
        assertEquals(
            "Aspart · NovoRapid/Novolog",
            resolve(InsulinFamily.RapidExp, "Insulin That Was Renamed", lastLogged = "Also Gone")?.label,
        )
    }

    /** A basal label selecting a rapid curve would commit a 42 h Bateman as a bolus. */
    @Test
    fun `a label from the other family is not honoured`() {
        assertEquals("Aspart · NovoRapid/Novolog", resolve(InsulinFamily.RapidExp, "Degludec · Tresiba")?.label)
        assertEquals("Glargine U100 · Lantus", resolve(InsulinFamily.BasalBateman, "Lispro · Humalog")?.label)
    }

    /** Null means no catalogue; the caller must fail closed rather than substitute one. */
    @Test
    fun `an empty catalogue resolves to nothing at all`() {
        assertNull(resolveInsulinPreset(emptyList(), InsulinFamily.RapidExp, "Lispro · Humalog", null))
        assertNull(resolveInsulinPreset(catalog.filter { it.family == InsulinFamily.RapidExp }, InsulinFamily.BasalBateman, null, null))
    }

    /** Per-device state, not config: an export would set another install's next dose curve. */
    @Test
    fun `the last-used insulin is not exportable configuration`() {
        assertTrue(!SettingsStore.isConfigKey(SettingsStore.K_LAST_RAPID_PRESET))
        assertTrue(!SettingsStore.isConfigKey(SettingsStore.K_LAST_BASAL_PRESET))
    }
}
