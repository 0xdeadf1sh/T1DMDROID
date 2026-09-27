package com.t1dm.app.di

import com.t1dm.app.settings.SettingsStore
import com.t1dm.core.model.InsulinChoice
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.InsulinType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsulinPresetResolutionTest {

    private fun rapid(label: String, theta: Double) =
        InsulinPresetSpec(InsulinFamily.RapidGamma, label, 3.0, theta, 5.6, 0.0, 0.0, 0.0, "cite")

    private fun basal(label: String, actionH: Double) =
        InsulinPresetSpec(InsulinFamily.BasalBateman, label, 0.0, 0.0, 0.0, 0.2, 0.03, actionH * 60.0, "cite")

    private val catalog = listOf(
        rapid("Aspart · NovoRapid/Novolog", 45.0),
        rapid("Faster aspart · Fiasp", 52.0),
        rapid("Lispro · Humalog", 45.1),
        rapid("Ultra-rapid lispro · Lyumjev", 52.1),
        basal("Glargine U100 · Lantus", 73.0),
        basal("Glargine U300 · Toujeo", 101.0),
        basal("Degludec · Tresiba", 133.0),
    )

    private fun resolve(family: InsulinFamily, requested: String?, lastLogged: String? = null) =
        resolveInsulinPreset(catalog, family, requested, lastLogged)

    @Test
    fun `what the panel picked is what the writer commits`() {
        val picked = resolve(InsulinFamily.RapidGamma, "Faster aspart · Fiasp")
        assertEquals("Faster aspart · Fiasp", picked?.label)
        assertEquals(52.0, picked!!.gammaTheta, 0.0)
    }

    @Test
    fun `the pick beats the remembered insulin`() {
        val picked = resolve(InsulinFamily.RapidGamma, "Lispro · Humalog", lastLogged = "Faster aspart · Fiasp")
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
        assertEquals("Aspart · NovoRapid/Novolog", resolve(InsulinFamily.RapidGamma, null)?.label)
        assertEquals("Glargine U100 · Lantus", resolve(InsulinFamily.BasalBateman, null)?.label)
    }

    @Test
    fun `an unknown label falls through instead of failing`() {
        assertEquals(
            "Aspart · NovoRapid/Novolog",
            resolve(InsulinFamily.RapidGamma, "Insulin That Was Renamed", lastLogged = "Also Gone")?.label,
        )
    }

    /** A basal label selecting a rapid curve would commit a 42 h Bateman as a bolus. */
    @Test
    fun `a label from the other family is not honoured`() {
        assertEquals("Aspart · NovoRapid/Novolog", resolve(InsulinFamily.RapidGamma, "Degludec · Tresiba")?.label)
        assertEquals("Glargine U100 · Lantus", resolve(InsulinFamily.BasalBateman, "Lispro · Humalog")?.label)
    }

    /** Null means no catalogue; the caller must fail closed rather than substitute one. */
    @Test
    fun `an empty catalogue resolves to nothing at all`() {
        assertNull(resolveInsulinPreset(emptyList(), InsulinFamily.RapidGamma, "Lispro · Humalog", null))
        assertNull(resolveInsulinPreset(catalog.filter { it.family == InsulinFamily.RapidGamma }, InsulinFamily.BasalBateman, null, null))
    }

    /** Per-device state, not config: an export would set another install's next dose curve. */
    @Test
    fun `the last-used insulin is not exportable configuration`() {
        assertTrue(!SettingsStore.isConfigKey(SettingsStore.K_LAST_RAPID_PRESET))
        assertTrue(!SettingsStore.isConfigKey(SettingsStore.K_LAST_BASAL_PRESET))
    }

    private val novorapid = InsulinType(1L, "Novorapid", InsulinKind.BOLUS, 240.0, builtin = true)
    private val customGamma = InsulinType(2L, "Slow", InsulinKind.BOLUS, 300.0, k = 2.0, theta = 40.0)
    private val choices = catalog.map(InsulinChoice::Preset) +
        listOf(novorapid, customGamma).map(InsulinChoice::Type)

    private fun own(kind: InsulinKind, label: String) = doseScaledOwnChoice(choices, kind, label)

    /** SPEC/invariants.md §5: a units edit must re-derive θ and duration for these. */
    @Test
    fun `a units edit re-resolves a rapid preset or a builtin bolus`() {
        assertEquals("Lispro · Humalog", own(InsulinKind.BOLUS, "Lispro · Humalog")?.label)
        assertEquals(InsulinChoice.Type(novorapid), own(InsulinKind.BOLUS, "Novorapid"))
    }

    @Test
    fun `a linear shape keeps its stored curve`() {
        assertNull(own(InsulinKind.BASAL, "Degludec · Tresiba"))
        assertNull(own(InsulinKind.BOLUS, "Slow"))
    }

    @Test
    fun `a kind mismatch or an unknown label resolves nothing`() {
        assertNull(own(InsulinKind.BASAL, "Lispro · Humalog"))
        assertNull(own(InsulinKind.BOLUS, "Insulin That Was Renamed"))
    }
}
