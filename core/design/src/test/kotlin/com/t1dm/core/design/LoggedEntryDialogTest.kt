package com.t1dm.core.design

import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.LoggedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoggedEntryDialogTest {

    /** 1970-01-02T03:04:00Z; at the row's +02:00 that is 05:04. */
    private val ts = 24 * 3_600_000L + 3 * 3_600_000L + 4 * 60_000L

    private fun meal(grams: Double, gi: Double?, detail: String? = null) = LoggedEntry(
        rowId = 1, clientId = "m", kind = CurveKind.CARB, insulin = null,
        tsMs = ts, tzOffsetMin = 120, amount = grams, gi = gi, detail = detail,
        updatedAtMs = ts, mutatedAtMs = null,
    )

    private fun dose(units: Double, kind: InsulinKind, insulin: String?) = LoggedEntry(
        rowId = 2, clientId = "d", kind = CurveKind.INSULIN, insulin = kind,
        tsMs = ts, tzOffsetMin = 120, amount = units, gi = null, detail = insulin,
        updatedAtMs = ts, mutatedAtMs = null,
    )

    private fun fields(entry: LoggedEntry) = logEntryFields(entry).toMap()

    @Test
    fun `an amount keeps its unit and its shape`() {
        assertEquals("45 g carbs", logAmountLabel(meal(45.0, 60.0)))
        assertEquals("4 U bolus", logAmountLabel(dose(4.0, InsulinKind.BOLUS, "NovoRapid")))
        assertEquals("18 U basal", logAmountLabel(dose(18.0, InsulinKind.BASAL, "Lantus")))
    }

    @Test
    fun `a half unit is not rounded away`() {
        assertEquals("4.5 U bolus", logAmountLabel(dose(4.5, InsulinKind.BOLUS, "NovoRapid")))
        assertEquals("12.5 g carbs", logAmountLabel(meal(12.5, 60.0)))
    }

    @Test
    fun `a meal states its glycemic index`() {
        assertEquals("60", fields(meal(45.0, 60.0))["Glycemic index"])
    }

    @Test
    fun `a meal with no glycemic index says so rather than showing a number`() {
        // A builder meal has no single index; "GI 0" would read as a legitimately low-GI one.
        assertEquals("not recorded", fields(meal(62.0, null))["Glycemic index"])
    }

    @Test
    fun `a note is stated as a note, whatever the writer put in it`() {
        // The note column is free text; calling it "Insulin" would assert what it never promised.
        assertEquals("NovoRapid", fields(dose(4.0, InsulinKind.BOLUS, "NovoRapid"))["Note"])
        assertEquals("backdated", fields(dose(4.0, InsulinKind.BOLUS, "backdated"))["Note"])
        assertNull(fields(dose(4.0, InsulinKind.BOLUS, null))["Insulin"])
    }

    @Test
    fun `a row with no note has no note field at all`() {
        // Absent, not withheld: no field rather than "not recorded".
        assertNull(fields(dose(4.0, InsulinKind.BOLUS, null))["Note"])
        assertNull(fields(dose(4.0, InsulinKind.BOLUS, "  "))["Note"])
        assertNull(fields(meal(45.0, 60.0))["Note"])
    }

    @Test
    fun `a meal's note is stated too, beside its index`() {
        val m = meal(45.0, null, detail = "hospital canteen")
        assertEquals("hospital canteen", fields(m)["Note"])
        assertEquals("not recorded", fields(m)["Glycemic index"])
    }

    @Test
    fun `the time is the offset the row was written at, not the reader's`() {
        assertEquals("Jan 2 · 05:04", fields(meal(45.0, 60.0))["Time"])
        assertEquals("Jan 2 · 03:04", logTimeLabel(ts, 0))
    }

    @Test
    fun `the detail line is the index for a meal and the note for a dose`() {
        assertEquals("GI 60", logDetailLabel(meal(45.0, 60.0)))
        assertEquals("NovoRapid", logDetailLabel(dose(4.0, InsulinKind.BOLUS, "NovoRapid")))
    }

    @Test
    fun `a meal states its index and its note, either alone or both`() {
        assertEquals("hospital canteen", logDetailLabel(meal(62.0, null, detail = "hospital canteen")))
        assertEquals("GI 60 · hospital canteen", logDetailLabel(meal(45.0, 60.0, detail = "hospital canteen")))
    }

    @Test
    fun `a fractional index left by an older slider still reads whole`() {
        assertEquals("GI 54", logDetailLabel(meal(45.0, 54.317)))
        assertEquals("54", fields(meal(45.0, 54.317))["Glycemic index"])
    }

    @Test
    fun `a row with nothing to add has no detail line at all`() {
        assertNull(logDetailLabel(meal(62.0, null)))
        assertNull(logDetailLabel(dose(4.0, InsulinKind.BOLUS, null)))
    }

    @Test
    fun `a single log titles itself and several are counted`() {
        val m = meal(45.0, 60.0)
        val d = dose(4.0, InsulinKind.BOLUS, "NovoRapid")
        assertEquals("45 g carbs", logEntriesTitle(listOf(m)))
        assertEquals("2 logs", logEntriesTitle(listOf(m, d)))
        assertEquals("5 logs", logEntriesTitle(listOf(m, d, m, d, m)))
    }
}
