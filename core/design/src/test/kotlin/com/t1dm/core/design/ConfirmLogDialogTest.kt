package com.t1dm.core.design

import com.t1dm.core.model.InsulinKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ConfirmLogDialogTest {

    private val utc = ZoneOffset.UTC

    /** 14:32:10Z, mid-slot so the snap is visible. */
    private val atFourteenThirtyTwo = 14 * 3_600_000L + 32 * 60_000L + 10_000L

    private fun fields(pending: PendingLog, nowMs: Long = atFourteenThirtyTwo) =
        confirmFields(pending, nowMs, utc).toMap()

    @Test
    fun `a simple meal restates grams, gi, photo and time`() {
        val f = fields(PendingLog.Meal(grams = 45.0, gi = 60.0))
        assertEquals("45 g", f["Carbs"])
        assertEquals("60", f["Glycemic index"])
        assertEquals("none", f["Photo"])
        assertEquals("14:32 · 5-min slot 14:30", f["Time"])
    }

    @Test
    fun `an attached photo is named, because undo cannot recall it`() {
        val f = fields(PendingLog.Meal(grams = 45.0, gi = 60.0, photoAttached = true))
        assertTrue(f.getValue("Photo").startsWith("attached"))
    }

    @Test
    fun `a builder meal names its components instead of a glycemic index`() {
        val f = fields(PendingLog.Meal(grams = 62.0, gi = null, detail = "Rice 150 g, Chicken 100 g"))
        assertEquals("combined curve (multi-food)", f["Glycemic index"])
        assertEquals("Rice 150 g, Chicken 100 g", f["Foods"])
    }

    @Test
    fun `a note is restated, and a blank one is left out`() {
        assertEquals("hospital canteen", fields(PendingLog.Meal(45.0, 60.0, note = "hospital canteen"))["Note"])
        assertEquals(null, fields(PendingLog.Meal(45.0, 60.0, note = "  "))["Note"])
    }

    @Test
    fun `a bolus restates units, kind and the resolved insulin`() {
        val f = fields(PendingLog.Dose(4.0, InsulinKind.BOLUS, "Aspart · NovoRapid/Novolog"))
        assertEquals("4 U bolus", f["Dose"])
        assertEquals("Aspart · NovoRapid/Novolog", f["Insulin"])
    }

    @Test
    fun `a basal reads as basal`() {
        val f = fields(PendingLog.Dose(18.0, InsulinKind.BASAL, "Glargine U100 · Lantus"))
        assertEquals("18 U basal", f["Dose"])
        assertEquals("Log this basal dose?", confirmTitle(PendingLog.Dose(18.0, InsulinKind.BASAL, "x")))
    }

    @Test
    fun `fractional amounts survive the restatement`() {
        assertEquals("4.5", fmtNum(4.5))
        assertEquals("4", fmtNum(4.0))
        assertEquals("0.5 U bolus", fields(PendingLog.Dose(0.5, InsulinKind.BOLUS, "x")).getValue("Dose"))
    }

    /** Round-to-nearest, matching the repository writer (§4-#1) — not floor. */
    @Test
    fun `the grid line snaps to the nearest slot, in both directions`() {
        val justPastTheHalfStep = 14 * 3_600_000L + 33 * 60_000L
        val f = fields(PendingLog.Meal(20.0, 50.0), justPastTheHalfStep)
        assertEquals("14:33 · 5-min slot 14:35", f["Time"])
    }

    @Test
    fun `titles are sentence case and ask a question`() {
        assertEquals("Log this meal?", confirmTitle(PendingLog.Meal(1.0, 1.0)))
        assertEquals("Log this bolus?", confirmTitle(PendingLog.Dose(1.0, InsulinKind.BOLUS, "x")))
    }
}
