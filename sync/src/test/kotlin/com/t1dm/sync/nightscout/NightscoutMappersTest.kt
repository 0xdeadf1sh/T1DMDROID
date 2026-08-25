package com.t1dm.sync.nightscout

import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutMappersTest {

    /** Fail closed: `sgv` claims sensor signal, and the host has no route to take a record back out. */
    @Test
    fun `a promoted reconstruction is never bridged`() {
        val recon = sample(112).copy(bgProvenance = ReadingProvenance.RECONSTRUCTED)
        assertNull("a reconstruction must not reach the bridge", recon.toNsEntry(null))
        assertEquals(112, sample(112).toNsEntry(null)?.sgv)
    }

    private fun sample(bg: Int?, ts: Long = 1_787_000_000_000L, tz: Int = 180) = SampleEntity(
        ts = ts,
        tzOffsetMin = tz,
        bgMgdl = bg,
        bgSource = "src",
        bgProvenance = bg?.let { ReadingProvenance.MEASURED },
        bgFlag = bg?.let { ReadingFlag.NORMAL },
        steps = 42,
        mood = 3,
        hr = null,
        sleep = null,
        exercise = 12.5,
        updatedAt = ts,
    )

    /** Trend is TENTHS of mg/dL per minute; read as whole units, every reading double-arrows. */
    @Test
    fun `direction cuts at tenths, not whole units`() {
        assertEquals("Flat", nsDirection(0))
        assertEquals("Flat", nsDirection(9))
        assertEquals("FortyFiveUp", nsDirection(10))
        assertEquals("SingleUp", nsDirection(20))
        assertEquals("DoubleUp", nsDirection(30))
        assertEquals("DoubleUp", nsDirection(120))
        assertEquals("Flat", nsDirection(-9))
        assertEquals("FortyFiveDown", nsDirection(-10))
        assertEquals("SingleDown", nsDirection(-20))
        assertEquals("DoubleDown", nsDirection(-30))
    }

    @Test
    fun `null trend yields no direction, not Flat`() {
        assertNull(nsDirection(null))
        assertNull(sample(120).toNsEntry(null)?.direction)
    }

    @Test
    fun `bg crosses in mg per dL unconverted`() {
        val e = sample(137).toNsEntry(15)!!
        assertEquals(137, e.sgv)
        assertEquals(1_787_000_000_000L, e.date)
        assertEquals("sgv", e.type)
        assertEquals("FortyFiveUp", e.direction)
        assertEquals(180, e.utcOffset)
    }

    @Test
    fun `slot without bg yields no entry`() {
        assertNull(sample(null).toNsEntry(10))
    }

    /** `exercise` is carbohydrate EQUIVALENT, opposite in sign to a meal: near a carb field a bout of
     *  exercise would read as food eaten. */
    @Test
    fun `entry carries bg only, never exercise or steps`() {
        val json = NsJson.encodeToString(NsEntryDto.serializer(), sample(100).toNsEntry(0)!!)
        assertTrue(json.contains("\"sgv\":100"))
        assertTrue("exercise leaked into the entry", !json.contains("12.5"))
        assertTrue("steps leaked into the entry", !json.contains("42"))
        assertTrue("carbs field on an entry", !json.contains("carbs"))
    }

    @Test
    fun `iso keeps the phone's own offset`() {
        assertEquals("2026-08-17T23:53:20+03:00", nsIso(1_787_000_000_000L, 180))
        assertEquals("2026-08-17T20:53:20Z", nsIso(1_787_000_000_000L, 0))
    }

    private fun meal(grams: Double, note: String? = null) = LoggedMealEntity(
        clientId = "cid-meal-1",
        tsMs = 1_787_000_000_000L,
        grams = grams,
        gi = 55.0,
        k = 2.0,
        theta = 20.0,
        durationMin = 180.0,
        customCurve = null,
        tzOffsetMin = 180,
        note = note,
        updatedAt = 1_787_000_000_000L,
    )

    private fun dose(kind: DoseKind, units: Double) = LoggedDoseEntity(
        clientId = "cid-dose-1",
        tsMs = 1_787_000_000_000L,
        kind = kind,
        units = units,
        durationMin = 300.0,
        k = null,
        theta = null,
        kaPerHour = if (kind == DoseKind.BASAL) 0.4 else null,
        kePerHour = if (kind == DoseKind.BASAL) 0.12 else null,
        customCurve = null,
        tzOffsetMin = 180,
        note = null,
        updatedAt = 1_787_000_000_000L,
    )

    @Test
    fun `meal becomes a carb correction in grams`() {
        val t = meal(45.0).toNsTreatment()
        assertEquals(NsEventType.CARBS, t.eventType)
        assertEquals(45.0, t.carbs!!, 0.0)
        assertNull(t.insulin)
        assertTrue(t.notes!!.contains("cid-meal-1"))
    }

    @Test
    fun `bolus becomes a correction bolus in units`() {
        val t = dose(DoseKind.BOLUS, 6.5).toNsTreatment()!!
        assertEquals(NsEventType.BOLUS, t.eventType)
        assertEquals(6.5, t.insulin!!, 0.0)
        assertNull(t.carbs)
    }

    /** A T1DM basal is units DELIVERED, Nightscout's a RATE with a duration — no mapping between them
     *  without a factor-of-duration error. */
    @Test
    fun `basal is declined, not guessed at`() {
        assertNull(dose(DoseKind.BASAL, 22.0).toNsTreatment())
    }

    @Test
    fun `client id survives with and without a user note`() {
        assertEquals("cid", noteWithClientId(null, "cid"))
        assertEquals("cid", noteWithClientId("  ", "cid"))
        assertEquals("pizza [cid]", noteWithClientId("pizza", "cid"))
        assertEquals("cid", clientIdMarker(noteWithClientId(null, "cid")))
        assertEquals("cid", clientIdMarker(noteWithClientId("pizza", "cid")))
        assertNull(clientIdMarker(null))
        assertNull(clientIdMarker("  "))
    }
}
