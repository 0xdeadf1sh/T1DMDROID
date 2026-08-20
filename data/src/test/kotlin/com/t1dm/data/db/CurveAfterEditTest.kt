package com.t1dm.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The rule that keeps a stored action/appearance curve honest across an edit.
 *
 * A curve is stored ABSOLUTE and the engine prefers it over the amount beside it, so a curve
 * carried across an edit unchanged goes on describing the pre-edit dose: 8 U logged as 4 U and then
 * corrected reads 8 U everywhere while IOB keeps returning the 4 U curve, and the ceiling rail then
 * admits roughly the difference.
 */
class CurveAfterEditTest {

    private fun dose(units: Double, curve: List<Double>?, durationMin: Double = 300.0) =
        LoggedDoseEntity(
            clientId = "c", tsMs = 0L, kind = DoseKind.BOLUS, units = units,
            durationMin = durationMin, k = null, theta = null, kaPerHour = null, kePerHour = null,
            customCurve = curve?.toBlob(), tzOffsetMin = 0, note = null, updatedAt = 0L,
        )

    private fun meal(grams: Double, curve: List<Double>?, gi: Double? = null) =
        LoggedMealEntity(
            clientId = "c", tsMs = 0L, grams = grams, gi = gi, k = null, theta = null,
            durationMin = 180.0, customCurve = curve?.toBlob(), tzOffsetMin = 0, note = null,
            updatedAt = 0L,
        )

    @Test
    fun `halving the units halves every bucket`() {
        val old = dose(8.0, listOf(1.0, 3.0, 4.0))
        val next = old.copy(units = 4.0)
        val out = old.curveAfterEdit(next)!!.toDoubleList()
        assertArrayEquals(doubleArrayOf(0.5, 1.5, 2.0), out.toDoubleArray(), 1e-12)
    }

    @Test
    fun `the rescaled curve sums to the edited amount when the original summed to the old one`() {
        val old = dose(8.0, listOf(2.0, 3.0, 3.0))
        val out = old.curveAfterEdit(old.copy(units = 5.0))!!.toDoubleList()
        assertEquals(5.0, out.sum(), 1e-12)
    }

    @Test
    fun `an unchanged amount keeps the stored curve byte for byte`() {
        val old = dose(8.0, listOf(1.0, 2.0))
        val next = old.copy(tsMs = 300_000L)
        assertSame(old.customCurve, old.curveAfterEdit(next))
    }

    @Test
    fun `a shape change drops the curve so the row re-derives from its own params`() {
        val old = dose(8.0, listOf(1.0, 2.0), durationMin = 300.0)
        assertNull(old.curveAfterEdit(old.copy(durationMin = 30.0)))
        assertNull(old.curveAfterEdit(old.copy(k = 2.0)))
    }

    @Test
    fun `a curve the caller re-derived is taken as given`() {
        val old = dose(8.0, listOf(1.0, 2.0))
        val fresh = listOf(9.0, 9.0)
        val next = old.copy(units = 4.0, customCurve = fresh.toBlob())
        assertArrayEquals(
            fresh.toDoubleArray(),
            old.curveAfterEdit(next)!!.toDoubleList().toDoubleArray(),
            1e-12,
        )
    }

    @Test
    fun `a zero or non-finite old amount cannot be scaled from`() {
        assertNull(dose(0.0, listOf(1.0)).curveAfterEdit(dose(0.0, listOf(1.0)).copy(units = 4.0)))
        val nan = dose(Double.NaN, listOf(1.0))
        assertNull(nan.curveAfterEdit(nan.copy(units = 4.0)))
    }

    @Test
    fun `a builder meal rescales on grams and drops on a GI change`() {
        val old = meal(60.0, listOf(20.0, 40.0))
        val out = old.curveAfterEdit(old.copy(grams = 30.0))!!.toDoubleList()
        assertArrayEquals(doubleArrayOf(10.0, 20.0), out.toDoubleArray(), 1e-12)
        assertNull(old.curveAfterEdit(old.copy(gi = 70.0)))
    }

    @Test
    fun `a single-food meal with no stored curve stays without one`() {
        val old = meal(60.0, null)
        assertNull(old.curveAfterEdit(old.copy(grams = 30.0)))
    }
}
