package com.t1dm.feature.models

import com.t1dm.core.model.CgEgaRegion
import com.t1dm.core.model.ClarkeZone
import com.t1dm.core.model.DtsZone
import com.t1dm.core.model.PointBlock
import com.t1dm.core.model.ScoredPoint
import com.t1dm.core.model.TrendMatrix
import com.t1dm.core.model.TREND_BINS
import com.t1dm.core.model.ZoneLattice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// Nothing classifies a pair here: boundaries live in t1dm-core::accuracy; lattices are synthetic.
class AccuracyFiguresTest {

    private fun block(a: Double, ab: Double, d: Double, e: Double) = PointBlock(
        rmsePoint = 20.0, maePoint = 15.0, rmseWinmean = 18.0, maeWinmean = 13.0, mard = 9.0,
        clarkeA = a, clarkeAb = ab, clarkeD = d, clarkeE = e,
        dtsA = 0.0, dtsB = 0.0, dtsC = 0.0, dtsD = 0.0, dtsE = 0.0, dtsMeanAbsRisk = 0.0,
        skillPoint = 0.3, points = emptyList(),
    )

    private fun dtsBlock(a: Double, b: Double, c: Double, d: Double, e: Double) = PointBlock(
        rmsePoint = 20.0, maePoint = 15.0, rmseWinmean = 18.0, maeWinmean = 13.0, mard = 9.0,
        clarkeA = 0.0, clarkeAb = 0.0, clarkeD = 0.0, clarkeE = 0.0,
        dtsA = a, dtsB = b, dtsC = c, dtsD = d, dtsE = e, dtsMeanAbsRisk = 0.21,
        skillPoint = 0.3, points = emptyList(),
    )

    @Test
    fun `the five zones partition the window`() {
        val s = clarkeShares(block(a = 82.0, ab = 96.0, d = 3.0, e = 1.0))
        assertEquals(listOf(82f, 14f, 0f, 3f, 1f), s)
        assertEquals(100f, s.sum(), 1e-3f)
    }

    /** C is not published by the core; it is what the other four leave. */
    @Test
    fun `zone C absorbs the remainder`() {
        val s = clarkeShares(block(a = 70.0, ab = 80.0, d = 5.0, e = 2.0))
        assertEquals(13f, s[2], 1e-4f)
        assertEquals(100f, s.sum(), 1e-3f)
    }

    @Test
    fun `an over-full partition clamps rather than inverts`() {
        val s = clarkeShares(block(a = 90.0, ab = 100.0000001, d = 0.0, e = 0.0))
        assertTrue(s.all { it >= 0f })
        assertEquals(0f, s[2], 1e-4f)
    }

    @Test
    fun `a non-finite input yields no bar at all`() {
        assertEquals(emptyList<Float>(), clarkeShares(block(a = Double.NaN, ab = 96.0, d = 3.0, e = 1.0)))
        assertEquals(emptyList<Float>(), clarkeShares(block(a = 82.0, ab = 96.0, d = 3.0, e = Double.NaN)))
    }

    @Test
    fun `an empty CG-EGA region draws nothing and carries its zero`() {
        val row = cgEgaRow("hypo", CgEgaRegion(apPct = null, bePct = null, epPct = null, nAp = 0, nBe = 0, nEp = 0))
        assertEquals(emptyList<Float>(), row.shares)
        assertEquals("n=0", row.note)
    }

    @Test
    fun `a populated CG-EGA region carries its own denominator`() {
        val row = cgEgaRow("eu", CgEgaRegion(apPct = 91.0, bePct = 7.0, epPct = 2.0, nAp = 910, nBe = 70, nEp = 20))
        assertEquals(listOf(91f, 7f, 2f), row.shares)
        assertEquals("n=1000", row.note)
    }

    /** Real lattice's structure — diagonal A band, 4 zones lobed above/below — no zone algebra. */
    private fun syntheticGrid(cells: Int = 40): ZoneLattice {
        val zones = ArrayList<ClarkeZone>(cells * cells)
        for (ti in 0 until cells) for (pi in 0 until cells) {
            val d = pi - ti
            zones += when {
                abs(d) <= 3 -> ClarkeZone.A
                ti < 6 && pi > cells - 8 -> ClarkeZone.E
                ti > cells - 7 && pi < 6 -> ClarkeZone.E
                ti < 8 && pi in 8..15 -> ClarkeZone.D
                ti > cells - 9 && pi in 8..15 -> ClarkeZone.D
                abs(d) > 12 -> ClarkeZone.C
                else -> ClarkeZone.B
            }
        }
        return ZoneLattice.of(400.0, cells, zones)
    }

    private fun asymmetric(truth: List<Double>, pred: List<Double>): List<ClarkeZone> =
        truth.flatMap { t -> pred.map { p -> if (p > t) ClarkeZone.D else ClarkeZone.B } }

    @Test
    fun `the run encoding reproduces the lattice exactly`() {
        val grid = syntheticGrid()
        val runs = zoneRuns(grid)
        assertTrue(runs.all { it.predUntil > it.predFrom })
        for (ti in 0 until grid.cells) {
            val column = runs.filter { it.truthIndex == ti }.sortedBy { it.predFrom }
            assertEquals(0, column.first().predFrom)
            assertEquals(grid.cells, column.last().predUntil)
            column.zipWithNext { a, b -> assertEquals(a.predUntil, b.predFrom) }
            column.zipWithNext { a, b -> assertTrue(a.zone != b.zone) }
            column.forEach { run ->
                (run.predFrom until run.predUntil).forEach { pi ->
                    assertEquals(run.zone, grid.ordinalAt(ti, pi))
                }
            }
        }
    }

    @Test
    fun `every zone letter sits in its own zone`() {
        val grid = syntheticGrid()
        val anchors = zoneAnchors(grid)
        assertTrue(anchors.isNotEmpty())
        anchors.forEach { a ->
            val ti = grid.indexOf(a.truthMgdl)
            val pi = grid.indexOf(a.predMgdl)
            assertNotNull(ti)
            assertNotNull(pi)
            assertEquals("letter ${a.zone} at (${a.truthMgdl}, ${a.predMgdl})", a.zone, grid.ordinalAt(ti!!, pi!!))
        }
    }

    /** A glyph has extent: anchored a cell from a boundary it is drawn over the neighbour. */
    @Test
    fun `every zone letter stands clear of its own boundary`() {
        val grid = syntheticGrid()
        val clear = anchorClearanceCells(grid.cells)
        assertTrue("the lattice must be coarse enough for the clearance to bite", clear >= 1)
        zoneAnchors(grid).forEach { a ->
            val ti = grid.indexOf(a.truthMgdl)!!
            val pi = grid.indexOf(a.predMgdl)!!
            for (dt in -clear..clear) for (dp in -clear..clear) {
                assertEquals(
                    "letter ${a.zone} at ($ti, $pi) overhangs ($dt, $dp)",
                    a.zone,
                    grid.ordinalAt(ti + dt, pi + dp),
                )
            }
        }
    }

    @Test
    fun `a lobe thinner than the clearance is still lettered`() {
        val cells = 40
        val clear = anchorClearanceCells(cells)
        val zones = ArrayList<ClarkeZone>(cells * cells)
        for (ti in 0 until cells) for (pi in 0 until cells) {
            zones += if (pi in 20 until 20 + clear) ClarkeZone.E else ClarkeZone.A
        }
        val anchors = zoneAnchors(ZoneLattice.of(400.0, cells, zones))
        assertTrue("the thin lobe kept no letter", anchors.any { it.zone == ClarkeZone.E.ordinal })
    }

    /** A straddles the diagonal, so its 2 candidates collapse to one; the other 4 lobe twice. */
    @Test
    fun `a zone that lobes twice is lettered twice and one that straddles is lettered once`() {
        val byZone = zoneAnchors(syntheticGrid()).groupBy { it.zone }
        assertEquals(1, byZone[ClarkeZone.A.ordinal]?.size)
        listOf(ClarkeZone.B, ClarkeZone.C, ClarkeZone.D, ClarkeZone.E).forEach {
            assertEquals("zone $it", 2, byZone[it.ordinal]?.size)
        }
    }

    @Test
    fun `an empty lattice paints nothing`() {
        assertTrue(ZoneLattice.EMPTY.isEmpty)
        assertEquals(emptyList<ZoneRun>(), zoneRuns(ZoneLattice.EMPTY))
        assertEquals(emptyList<ZoneAnchor>(), zoneAnchors(ZoneLattice.EMPTY))
        // A zone list that does not match the declared size is refused whole.
        assertTrue(ZoneLattice.of(400.0, 4, listOf(ClarkeZone.A)).isEmpty)
    }

    /** [zoneAnchors] sizes arrays here, indexed by ordinal; undershoot throws, not fail-closed. */
    @Test
    fun `zoneCount is derived from the cells it actually holds`() {
        assertEquals(ClarkeZone.values().size, syntheticGrid().zoneCount)
        val cells = 20
        val zones = List(cells * cells) { if (it % 2 == 0) ClarkeZone.A else ClarkeZone.B }
        val sparse = ZoneLattice.of(400.0, cells, zones)
        assertEquals(2, sparse.zoneCount)
        assertTrue(zoneAnchors(sparse).all { it.zone < sparse.zoneCount })
        assertTrue(zoneRuns(sparse).all { it.zone < sparse.zoneCount })
    }

    @Test
    fun `the lattice keeps the classifier's orientation`() {
        val grid = ZoneLattice.build(::asymmetric)
        assertTrue(!grid.isEmpty)
        for (ti in listOf(0, 37, 91, ZoneLattice.CELLS - 1)) {
            for (pi in listOf(0, 12, 140, ZoneLattice.CELLS - 1)) {
                assertEquals(
                    asymmetric(listOf(grid.coordAt(ti)), listOf(grid.coordAt(pi))).single().ordinal,
                    grid.ordinalAt(ti, pi),
                )
            }
        }
    }

    /** A transposed lattice is a well-formed picture of a mirrored grid, so it is refused, not
     *  painted. */
    @Test
    fun `a transposed or short classifier yields no lattice`() {
        assertTrue(ZoneLattice.build { t, p -> asymmetric(p, t) }.isEmpty)
        assertTrue(ZoneLattice.build { _, _ -> listOf(ClarkeZone.A) }.isEmpty)
        assertTrue(ZoneLattice.build<ClarkeZone> { _, _ -> emptyList() }.isEmpty)
    }

    private fun points(vararg zones: Pair<ClarkeZone, Int>): List<ScoredPoint> =
        zones.flatMap { (z, n) ->
            List(n) { ScoredPoint(pred = 120.0, truth = 110.0, clarke = z, dts = DtsZone.A, dtsRisk = 0.1) }
        }

    private fun clarkeZoneShares(pts: List<ScoredPoint>) = zoneShares(pts) { it.clarke.ordinal }

    @Test
    fun `the scatter's shares partition its own points`() {
        val s = clarkeZoneShares(points(ClarkeZone.A to 82, ClarkeZone.B to 14, ClarkeZone.D to 3, ClarkeZone.E to 1))
        assertEquals(listOf(82f, 14f, 0f, 3f, 1f), s)
        assertEquals(100f, s.sum(), 1e-3f)
    }

    /** Off points here against the core's totals; disagreement puts 2 clashing figures onscreen. */
    @Test
    fun `the two reductions of one population agree`() {
        val pts = points(ClarkeZone.A to 70, ClarkeZone.B to 10, ClarkeZone.C to 13, ClarkeZone.D to 5, ClarkeZone.E to 2)
        val fromTotals = clarkeShares(block(a = 70.0, ab = 80.0, d = 5.0, e = 2.0))
        clarkeZoneShares(pts).zip(fromTotals).forEach { (a, b) -> assertEquals(a, b, 1e-4f) }
    }

    @Test
    fun `an empty scatter has no shares`() {
        assertEquals(emptyList<Float>(), clarkeZoneShares(emptyList()))
    }

    /** The wrong selector draws the other grid's picture under this one's letters. */
    @Test
    fun `the two grids' selectors read different columns of one series`() {
        val pts = listOf(
            ScoredPoint(pred = 200.0, truth = 100.0, clarke = ClarkeZone.C, dts = DtsZone.D, dtsRisk = 1.9),
            ScoredPoint(pred = 105.0, truth = 100.0, clarke = ClarkeZone.A, dts = DtsZone.A, dtsRisk = 0.1),
        )
        assertEquals(listOf(50f, 0f, 50f, 0f, 0f), zoneShares(pts) { it.clarke.ordinal })
        assertEquals(listOf(50f, 0f, 0f, 50f, 0f), zoneShares(pts) { it.dts.ordinal })
    }

    /** All five come off the core: a pass-through, no remainder, and no A+B for a caller to reach
     *  for. */
    @Test
    fun `the DTS shares are published whole`() {
        val s = dtsShares(dtsBlock(a = 93.1, b = 5.2, c = 1.0, d = 0.5, e = 0.2))
        assertEquals(listOf(93.1f, 5.2f, 1.0f, 0.5f, 0.2f), s)
        assertEquals(100f, s.sum(), 1e-3f)
    }

    @Test
    fun `a non-finite DTS share yields no bar at all`() {
        assertEquals(emptyList<Float>(), dtsShares(dtsBlock(Double.NaN, 5.0, 1.0, 0.5, 0.2)))
        assertEquals(emptyList<Float>(), dtsShares(dtsBlock(93.0, 5.0, 1.0, 0.5, Double.NaN)))
    }

    private fun matrix(counts: List<Int>, pct: List<Double>) =
        TrendMatrix(counts, List(5) { 0 }, pct, counts.sum())

    @Test
    fun `bin labels are built from the core's edges`() {
        assertEquals(
            listOf("<-2", "-2..-1", "-1..1", "1..2", ">2"),
            trendBinLabels(listOf(-2.0, -1.0, 1.0, 2.0)),
        )
    }

    /** Stub core supplies the empty list; a guessed axis caption is worse than unlabelled. */
    @Test
    fun `a wrong-length edge list labels nothing`() {
        assertEquals(emptyList<String>(), trendBinLabels(emptyList()))
        assertEquals(emptyList<String>(), trendBinLabels(listOf(-1.0, 1.0)))
    }

    /** Truth-major; a transposed read is well-formed and describes the opposite failure. */
    @Test
    fun `the matrix is read truth-major`() {
        val counts = MutableList(TREND_BINS * TREND_BINS) { 0 }
        counts[4 * TREND_BINS + 2] = 7 // truth rising fast, forecast flat
        val m = matrix(counts, List(5) { 0.0 })
        assertEquals(7, m.countAt(4, 2))
        assertEquals(0, m.countAt(2, 4))
        assertEquals(7, m.peak)
        // Off the table is 0, never an exception.
        assertEquals(0, m.countAt(9, 9))
    }

    @Test
    fun `an empty matrix draws no partition`() {
        assertTrue(TrendMatrix.EMPTY.isEmpty)
        assertEquals(emptyList<Float>(), trendCategoryShares(TrendMatrix.EMPTY))
    }

    @Test
    fun `a populated matrix's categories partition it`() {
        val s = trendCategoryShares(matrix(List(TREND_BINS * TREND_BINS) { 1 }, listOf(72.0, 14.0, 11.0, 2.0, 1.0)))
        assertEquals(listOf(72f, 14f, 11f, 2f, 1f), s)
        assertEquals(100f, s.sum(), 1e-3f)
    }
}
