package com.t1dm.sync.nightscout

import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned against what a real Nightscout-compatible host actually returned, not against what the API
 * shape suggests it would. Every literal below is a verbatim response body.
 *
 * The host rewrites more than it keeps: it composes its own `notes`, replaces `enteredBy` with
 * "System", normalises `created_at` to UTC, and materialises `carbs: 0` on a treatment posted without
 * carbs. Each of those defeated a different part of the replay guard when the guard was written
 * against assumptions.
 */
class NightscoutHostEchoTest {

    /** Verbatim: the echo of a `Correction Bolus` this bridge posted with `notes` = a client_id. */
    private val echoedBolus = NsJson.decodeFromString(
        ListSerializer(NsTreatmentDto.serializer()),
        """[{"_id":"DBAdmeGue6r7kE","eventType":"Correction Bolus","created_at":"2026-08-18T12:55:00.000Z",
            "utc_offset":0,"enteredBy":"System","notes":"Bolus: 6u","mills":1787057700000,
            "insulin":6,"carbs":0}]""",
    ).single()

    /** Verbatim: the echo of a `Carb Correction` posted with `notes` = a client_id. */
    private val echoedMeal = NsJson.decodeFromString(
        ListSerializer(NsTreatmentDto.serializer()),
        """[{"_id":"DGBnm4Kclgn1LE","eventType":"Carb Correction","created_at":"2026-08-18T12:56:00.000Z",
            "utc_offset":0,"enteredBy":"System","notes":"#snack Food:  40g","mills":1787057760000,
            "carbs":40,"foodType":"#snack Food:  40g"}]""",
    ).single()

    private val sentBolus = NsTreatmentDto(
        eventType = NsEventType.BOLUS,
        created_at = "2026-08-18T15:55:00.000+03:00",
        insulin = 6.0,
        notes = "afc4b6d7-ecf4-480f-bbb5-38b5184b8ead",
    )

    /**
     * The whole guard, against the real echo. It must recognise its own post despite the host having
     * rewritten the timestamp to UTC, invented `carbs: 0`, and thrown the client_id away.
     */
    @Test
    fun `the guard recognises its own post in a heavily rewritten echo`() {
        assertTrue(echoedBolus.matches(sentBolus))
    }

    /**
     * The host composes `notes` itself, so a non-empty note is NOT evidence the marker survived.
     * Reading it as evidence would require a marker that can never match — the guard would then
     * report "not present" for every replay and duplicate the dose it exists to prevent.
     */
    @Test
    fun `a host-composed note is not mistaken for a surviving marker`() {
        assertFalse(looksLikeClientId("Bolus: 6u"))
        assertFalse(looksLikeClientId("#snack Food:  40g"))
        assertTrue(looksLikeClientId("afc4b6d7-ecf4-480f-bbb5-38b5184b8ead"))
        // …and with the marker gone, demanding one finds nothing.
        assertFalse(echoedBolus.matches(sentBolus, requireMarker = true))
    }

    /** `carbs: 0` invented on a bolus posted without carbs must not read as a different event. */
    @Test
    fun `an invented zero amount is the same as none`() {
        assertTrue(sameAmount(null, 0.0))
        assertTrue(sameAmount(0.0, null))
        assertFalse(sameAmount(null, 40.0))
        assertEquals(0.0, echoedBolus.carbs)
    }

    /** A different event in the echo is still a different event. */
    @Test
    fun `the meal echo does not match the bolus that was sent`() {
        assertFalse(echoedMeal.matches(sentBolus))
    }

    /**
     * The reason a bridged treatment carries `updatedAt`: grid-snapping puts a meal and its bolus on
     * ONE instant, and a host keying treatments by timestamp keeps only the first — acking the second
     * with a 200 and storing nothing. This is the live failure that lost a logged meal.
     */
    @Test
    fun `a meal and its bolus do not share a timestamp`() {
        val meal = LoggedMealEntity(
            clientId = "40c56666-12b0-4dc0-a63e-c8fac7444fbc",
            tsMs = 1_787_057_700_000L, grams = 40.0, gi = 55.0, k = 2.0, theta = 20.0,
            durationMin = 180.0, customCurve = null, tzOffsetMin = 180, note = null,
            updatedAt = 1_787_057_686_929L,
        )
        val dose = LoggedDoseEntity(
            clientId = "afc4b6d7-ecf4-480f-bbb5-38b5184b8ead",
            tsMs = 1_787_057_700_000L, kind = DoseKind.BOLUS, units = 6.0, durationMin = 300.0,
            k = null, theta = null, kaPerHour = null, kePerHour = null, customCurve = null,
            tzOffsetMin = 180, note = null, updatedAt = 1_787_057_677_802L,
        )

        assertEquals("the grid puts both on one instant", meal.tsMs, dose.tsMs)
        assertNotEquals(
            "the bridged copies must not collide",
            meal.toNsTreatment().created_at,
            dose.toNsTreatment()!!.created_at,
        )
    }
}
