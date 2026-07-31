package com.t1dm.core.design

import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.LogState
import com.t1dm.core.model.LoggedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a mark on the BG panel says when it is opened. Its whole job is to restate rows that were
 * written some time ago, so the wording is the thing worth pinning: a dialog that printed "GI 0" for a
 * builder meal, or read a free-text note as the name of the insulin injected, would be read as the
 * record itself — nothing downstream would ever contradict it, and the row it misdescribes is what IOB
 * and every rail that reads IOB are computed from. Pure functions precisely so this needs no
 * composition.
 */
class LoggedEntryDialogTest {

    /** 1970-01-02T03:04:00Z, and the offset is the row's own: +02:00 ⇒ 05:04 on the same day. */
    private val ts = 24 * 3_600_000L + 3 * 3_600_000L + 4 * 60_000L

    private fun meal(grams: Double, gi: Double?, detail: String? = null) = LoggedEntry(
        rowId = 1, clientId = "m", kind = CurveKind.CARB, insulin = null,
        tsMs = ts, tzOffsetMin = 120, amount = grams, gi = gi, detail = detail,
        state = LogState.DELIVERED,
    )

    private fun dose(units: Double, kind: InsulinKind, insulin: String?) = LoggedEntry(
        rowId = 2, clientId = "d", kind = CurveKind.INSULIN, insulin = kind,
        tsMs = ts, tzOffsetMin = 120, amount = units, gi = null, detail = insulin,
        state = LogState.COMMITTED,
    )

    private fun fields(entry: LoggedEntry) = logEntryFields(entry).toMap()

    // ── the headline ─────────────────────────────────────────────────────────────────────────────

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

    // ── what the row carries, and what it does not ───────────────────────────────────────────────

    @Test
    fun `a meal states its glycemic index`() {
        assertEquals("60", fields(meal(45.0, 60.0))["Glycemic index"])
    }

    @Test
    fun `a meal with no glycemic index says so rather than showing a number`() {
        // A builder meal's appearance curve is the combination of its components; it has no single
        // index, and "GI 0" would read as a legitimately low-GI meal.
        assertEquals("not recorded", fields(meal(62.0, null))["Glycemic index"])
    }

    @Test
    fun `a note is stated as a note, whatever the writer put in it`() {
        // The local writers fill a dose's note with the insulin they resolved, but the column is free
        // text — the backdate hook writes "backdated", and a row synced from another client carries
        // whatever that client wrote. Calling it "Insulin" would assert which insulin was injected on
        // the authority of a column that never promised one.
        assertEquals("NovoRapid", fields(dose(4.0, InsulinKind.BOLUS, "NovoRapid"))["Note"])
        assertEquals("backdated", fields(dose(4.0, InsulinKind.BOLUS, "backdated"))["Note"])
        assertNull(fields(dose(4.0, InsulinKind.BOLUS, null))["Insulin"])
    }

    @Test
    fun `a row with no note has no note field at all`() {
        // Nothing was withheld — a row simply has no note — so there is no field to say so against.
        assertNull(fields(dose(4.0, InsulinKind.BOLUS, null))["Note"])
        assertNull(fields(dose(4.0, InsulinKind.BOLUS, "  "))["Note"])
        assertNull(fields(meal(45.0, 60.0))["Note"])
    }

    @Test
    fun `a meal's note is stated too, beside its index`() {
        // Only the preset writers fill a meal's note today, but the sync mapper copies the wire's
        // verbatim, and before this the column had no reader on any surface.
        val m = meal(45.0, null, detail = "hospital canteen")
        assertEquals("hospital canteen", fields(m)["Note"])
        assertEquals("not recorded", fields(m)["Glycemic index"])
    }

    @Test
    fun `the time is the offset the row was written at, not the reader's`() {
        // +02:00 at the row, so 03:04 UTC reads as 05:04 — the wall clock the user acted on.
        assertEquals("Jan 2 · 05:04", fields(meal(45.0, 60.0))["Time"])
        assertEquals("Jan 2 · 03:04", logTimeLabel(ts, 0))
    }

    // ── the list's second line ───────────────────────────────────────────────────────────────────

    @Test
    fun `the detail line is the index for a meal and the note for a dose`() {
        assertEquals("GI 60", logDetailLabel(meal(45.0, 60.0)))
        assertEquals("NovoRapid", logDetailLabel(dose(4.0, InsulinKind.BOLUS, "NovoRapid")))
    }

    @Test
    fun `a meal with no index falls back to its note rather than dropping it`() {
        assertEquals("hospital canteen", logDetailLabel(meal(62.0, null, detail = "hospital canteen")))
        // The index wins where the row has both: it is the number the panel is read for.
        assertEquals("GI 60", logDetailLabel(meal(45.0, 60.0, detail = "hospital canteen")))
    }

    @Test
    fun `a row with nothing to add has no detail line at all`() {
        assertNull(logDetailLabel(meal(62.0, null)))
        assertNull(logDetailLabel(dose(4.0, InsulinKind.BOLUS, null)))
    }

    // ── one mark, several logs ───────────────────────────────────────────────────────────────────

    @Test
    fun `a single log titles itself and several are counted`() {
        val m = meal(45.0, 60.0)
        val d = dose(4.0, InsulinKind.BOLUS, "NovoRapid")
        assertEquals("45 g carbs", logEntriesTitle(listOf(m)))
        assertEquals("2 logs", logEntriesTitle(listOf(m, d)))
        assertEquals("5 logs", logEntriesTitle(listOf(m, d, m, d, m)))
    }
}
