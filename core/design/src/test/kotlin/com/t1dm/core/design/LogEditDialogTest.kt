package com.t1dm.core.design

import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.InsulinChoice
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.InsulinType
import com.t1dm.core.model.LoggedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogEditDialogTest {

    @Test
    fun `a blank index clears it rather than failing the field`() {
        assertTrue(giFieldValid(""))
        assertTrue(giFieldValid("   "))
        assertNull(giFieldOrNull(""))
    }

    @Test
    fun `an index outside 0 to 100 is refused, not clamped`() {
        assertFalse(giFieldValid("101"))
        assertFalse(giFieldValid("-1"))
        assertTrue(giFieldValid("0"))
        assertTrue(giFieldValid("100"))
        assertEquals(72.0, giFieldOrNull("72")!!, 0.0)
    }

    @Test
    fun `a fractional index is refused`() {
        // Whole points only; a stored fraction reads back as "GI 54.3".
        assertFalse(giFieldValid("72.5"))
        assertNull(giFieldOrNull("72.5"))
    }

    @Test
    fun `text that is not a number is refused`() {
        assertFalse(giFieldValid("high"))
        assertNull(giFieldOrNull("high"))
    }

    private fun preset(label: String, family: InsulinFamily) = InsulinChoice.Preset(
        InsulinPresetSpec(family, label, 75.0, 360.0, 0.3, 0.07, true, "cite"),
    )

    private fun type(name: String, kind: InsulinKind) =
        InsulinChoice.Type(InsulinType(id = 1, name = name, kind = kind, durationMin = 360.0))

    private val catalogue = listOf(
        preset("Aspart · NovoRapid/Novolog", InsulinFamily.RapidExp),
        preset("Lispro · Humalog", InsulinFamily.RapidExp),
        preset("Glargine U100 · Lantus", InsulinFamily.BasalBateman),
        type("My pen", InsulinKind.BOLUS),
    )

    private fun dose(kind: InsulinKind, note: String?) = LoggedEntry(
        rowId = 1, clientId = "d", kind = CurveKind.INSULIN, insulin = kind, tsMs = 0L,
        tzOffsetMin = 0, amount = 4.0, gi = null, detail = note, updatedAtMs = 0L, mutatedAtMs = null,
    )

    @Test
    fun `only insulins of the row's own kind are offered`() {
        val bolus = offeredInsulins(dose(InsulinKind.BOLUS, null), catalogue)
        assertEquals(
            listOf("Aspart · NovoRapid/Novolog", "Lispro · Humalog", "My pen"),
            bolus.map { it.label },
        )
        assertEquals(
            listOf("Glargine U100 · Lantus"),
            offeredInsulins(dose(InsulinKind.BASAL, null), catalogue).map { it.label },
        )
    }

    @Test
    fun `the insulin the row was logged under opens selected, from either catalogue`() {
        // The regression: the note holds a preset LABEL, which no `insulin_type` name ever equals.
        val entry = dose(InsulinKind.BOLUS, "Lispro · Humalog")
        assertEquals(1, loggedInsulinIndex(entry, offeredInsulins(entry, catalogue)))

        val typed = dose(InsulinKind.BOLUS, "My pen")
        assertEquals(2, loggedInsulinIndex(typed, offeredInsulins(typed, catalogue)))
    }

    @Test
    fun `a note naming no insulin selects nothing rather than the first chip`() {
        val entry = dose(InsulinKind.BOLUS, "backdated")
        assertEquals(-1, loggedInsulinIndex(entry, offeredInsulins(entry, catalogue)))
        assertEquals(-1, loggedInsulinIndex(dose(InsulinKind.BOLUS, null), catalogue))
    }
}
