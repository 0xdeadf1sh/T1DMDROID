package com.t1dm.feature.models

import com.t1dm.core.model.ClarkePoint
import com.t1dm.core.model.ClarkeZone
import com.t1dm.core.model.ExcursionAccuracy
import com.t1dm.core.model.HorizonMetrics
import com.t1dm.core.model.PointBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Clarke error grid's horizon choice, which is the only thing on that section a user drives.
 *
 * Nothing here scores a forecast — the horizons are synthetic records standing in for whatever
 * `forecast_metrics_suite` returned. What is under test is the resolution: which horizon the picker
 * opens on, which one a selection lands on, and what happens when the one selected did not earn
 * enough windows to be drawn. That last case is the reason the picker cannot simply index the
 * sufficient horizons: a figure labelled with one horizon and plotted from another is a lie the
 * reader has no way to catch, so the choice resolves to a whole record and the label, the `n` and
 * the pairs are three readings of it.
 */
class ClarkeGridPickerTest {

    private fun points(n: Int): List<ClarkePoint> =
        List(n) { ClarkePoint(pred = 120.0 + it, truth = 118.0 + it, zone = ClarkeZone.A) }

    private fun block(nPoints: Int) = PointBlock(
        rmsePoint = 20.0, maePoint = 15.0, rmseWinmean = 18.0, maeWinmean = 13.0, mard = 9.0,
        clarkeA = 82.0, clarkeAb = 96.0, clarkeD = 3.0, clarkeE = 1.0, skillPoint = 0.3,
        clarkePoints = points(nPoints),
    )

    private val excursion = ExcursionAccuracy(recall = 0.8, precision = 0.7, nTrue = 5, nPred = 6)

    /** The median line is the basis the grid draws, so only it carries a per-point series (§6.2). */
    private fun horizon(min: Int, n: Int, sufficient: Boolean = true) = HorizonMetrics(
        horizonMin = min,
        n = n,
        sufficient = sufficient,
        band = block(0),
        medianLine = block(n),
        rmsePersistPoint = 30.0,
        rmsePersistWinmean = 28.0,
        bandCov50 = 0.5,
        bandWidth50 = 40.0,
        bandCov90 = 0.9,
        bandWidth90 = 90.0,
        hypo = excursion,
        hyper = excursion,
    )

    private fun suite(vararg hs: HorizonMetrics) = hs.toList()

    // ── The default ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the grid opens on 60 minutes`() {
        assertEquals(60, CLARKE_GRID_DEFAULT_MIN)
        val pick = clarkeGridPick(suite(horizon(30, 40), horizon(60, 40), horizon(120, 40)), CLARKE_GRID_DEFAULT_MIN)
        assertEquals(60, pick.selected?.horizonMin)
    }

    /** The section used to plot `maxByOrNull { horizonMin }`; the default must not be that again. */
    @Test
    fun `the default is not the longest horizon`() {
        val hs = suite(horizon(30, 40), horizon(60, 40), horizon(120, 40))
        val pick = clarkeGridPick(hs, CLARKE_GRID_DEFAULT_MIN)
        assertNotEquals(hs.maxOf { it.horizonMin }, pick.selected?.horizonMin)
    }

    @Test
    fun `every scored horizon is offered, ascending, whatever order it arrived in`() {
        val pick = clarkeGridPick(suite(horizon(120, 40), horizon(30, 40), horizon(60, 40)), CLARKE_GRID_DEFAULT_MIN)
        assertEquals(listOf(30, 60, 120), pick.options)
    }

    // ── The selection ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a chosen horizon is the one resolved`() {
        val hs = suite(horizon(30, 40), horizon(60, 40), horizon(120, 40))
        assertEquals(30, clarkeGridPick(hs, 30).selected?.horizonMin)
        assertEquals(60, clarkeGridPick(hs, 60).selected?.horizonMin)
        assertEquals(120, clarkeGridPick(hs, 120).selected?.horizonMin)
    }

    /**
     * The whole point of resolving to a record: the caption's horizon, its `n` and its scatter must
     * come off ONE object, so a figure cannot name 60 min over 120's pairs.
     */
    @Test
    fun `the scatter, its n and its label come off one record`() {
        val hs = suite(horizon(30, 11), horizon(60, 22), horizon(120, 33))
        hs.forEach { h ->
            val sel = clarkeGridPick(hs, h.horizonMin).selected
            assertSame(h, sel)
            assertEquals(h.horizonMin, sel?.horizonMin)
            // What ClarkeGridFigure prints as `n=` against what the tables print in the `n` column.
            assertEquals(sel?.n, sel?.medianLine?.clarkePoints?.size)
        }
    }

    /** A choice is minutes, not a position, so it survives a suite that gains or drops a horizon. */
    @Test
    fun `a choice absent from the suite resolves to the nearest offered horizon`() {
        assertEquals(30, clarkeGridPick(suite(horizon(30, 40), horizon(120, 40)), 60).selected?.horizonMin)
        assertEquals(120, clarkeGridPick(suite(horizon(30, 40), horizon(120, 40)), 100).selected?.horizonMin)
    }

    @Test
    fun `a tie resolves to the shorter horizon`() {
        assertEquals(45, clarkeGridPick(suite(horizon(45, 40), horizon(75, 40)), 60).selected?.horizonMin)
    }

    @Test
    fun `no scored horizon offers no choice and selects nothing`() {
        val pick = clarkeGridPick(emptyList(), CLARKE_GRID_DEFAULT_MIN)
        assertEquals(emptyList<Int>(), pick.options)
        assertNull(pick.selected)
        assertNull(pick.refusal(6))
    }

    // ── The refusal ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an insufficient choice is refused by name, against its own n`() {
        val hs = suite(horizon(30, 40), horizon(60, 4, sufficient = false), horizon(120, 40))
        val pick = clarkeGridPick(hs, 60)
        assertEquals("60 min: n=4, need 6", pick.refusal(6))
    }

    /** A figure labelled 60 that plots 120 is the failure this section must not have. */
    @Test
    fun `an insufficient choice is never redrawn as a neighbouring horizon`() {
        val hs = suite(horizon(30, 40), horizon(60, 4, sufficient = false), horizon(120, 40))
        val pick = clarkeGridPick(hs, 60)
        assertEquals(60, pick.selected?.horizonMin)
        assertTrue(pick.selected?.sufficient == false)
    }

    /** Refusing one horizon must not withdraw the others, or the reader is stuck on the refusal. */
    @Test
    fun `a refused horizon keeps the picker usable`() {
        val hs = suite(horizon(30, 40), horizon(60, 4, sufficient = false), horizon(120, 40))
        val pick = clarkeGridPick(hs, 60)
        assertEquals(listOf(30, 60, 120), pick.options)
        assertNull(clarkeGridPick(hs, 120).refusal(6))
        assertEquals(120, clarkeGridPick(hs, 120).selected?.horizonMin)
    }

    @Test
    fun `a sufficient choice refuses nothing`() {
        val hs = suite(horizon(30, 40), horizon(60, 40), horizon(120, 40))
        assertNull(clarkeGridPick(hs, 60).refusal(6))
    }
}
