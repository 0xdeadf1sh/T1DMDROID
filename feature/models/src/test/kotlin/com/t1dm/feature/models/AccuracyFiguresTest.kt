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

/**
 * The partitions and the lattice arithmetic the figures assemble on this side. Everything else they
 * draw is the core's own number, rendered; these are worked out here, so they are the only
 * arithmetic in the figures that can be wrong on its own.
 *
 * Nothing here classifies a pair. The Clarke boundaries live in `t1dm-core::accuracy` and are pinned
 * there against `T1DMAI/realdata/metrics.py`; the lattices below are synthetic fixtures standing in
 * for whatever the core returns, so a change to the zone algebra cannot be masked by a copy of it
 * kept in this file.
 */
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

    /** Float noise must not turn a full partition into a negative slice. */
    @Test
    fun `an over-full partition clamps rather than inverts`() {
        val s = clarkeShares(block(a = 90.0, ab = 100.0000001, d = 0.0, e = 0.0))
        assertTrue(s.all { it >= 0f })
        assertEquals(0f, s[2], 1e-4f)
    }

    /** A partial partition would render as a plausible shape, which is worse than no shape. */
    @Test
    fun `a non-finite input yields no bar at all`() {
        assertEquals(emptyList<Float>(), clarkeShares(block(a = Double.NaN, ab = 96.0, d = 3.0, e = 1.0)))
        assertEquals(emptyList<Float>(), clarkeShares(block(a = 82.0, ab = 96.0, d = 3.0, e = Double.NaN)))
    }

    /** A region that held no point has no triple — an empty track, and the count says why. */
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

    // ── The Clarke error grid ──────────────────────────────────────────────────────────────────

    /**
     * A stand-in lattice with the STRUCTURE the real one has — a diagonal A band whose two halves
     * nearly touch, and four zones in separated lobes above and below it — without a line of the
     * real zone algebra. What is under test is the encoding and the letter placement, never the
     * classification: that is the core's, and is pinned there.
     */
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

    /** A classifier that is NOT symmetric in its two axes — what the orientation probe leans on. */
    private fun asymmetric(truth: List<Double>, pred: List<Double>): List<ClarkeZone> =
        truth.flatMap { t -> pred.map { p -> if (p > t) ClarkeZone.D else ClarkeZone.B } }

    /** The runs must tile every column exactly — no gap the background shows through, no overlap
     *  painting one zone over another, and every cell the zone the lattice gave it. */
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
            // Adjacent runs must differ, or the encoding is emitting a boundary that is not one.
            column.zipWithNext { a, b -> assertTrue(a.zone != b.zone) }
            column.forEach { run ->
                (run.predFrom until run.predUntil).forEach { pi ->
                    assertEquals(run.zone, grid.ordinalAt(ti, pi))
                }
            }
        }
    }

    /**
     * The property that keeps a letter from naming a region it does not sit in: an anchor is always
     * a coordinate the LATTICE puts in that zone, never a remembered position.
     */
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

    /**
     * Sitting in its own zone is not enough: the letter is a glyph with EXTENT, and one anchored a
     * cell from a boundary is drawn mostly over the neighbouring region — which is what a reader
     * takes it to name. So the anchor must be uniform out to the glyph's own reach.
     */
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

    /**
     * A lobe too thin to hold a glyph clear of its own edges keeps its letter anyway. A region
     * named imprecisely still tells a reader which region it is; an unlettered one tells them
     * nothing, and the legend cannot say WHERE.
     */
    @Test
    fun `a lobe thinner than the clearance is still lettered`() {
        val cells = 40
        val clear = anchorClearanceCells(cells)
        val zones = ArrayList<ClarkeZone>(cells * cells)
        for (ti in 0 until cells) for (pi in 0 until cells) {
            // One stripe of E, `clear` cells narrower than the clearance needs, in a field of A.
            zones += if (pi in 20 until 20 + clear) ClarkeZone.E else ClarkeZone.A
        }
        val anchors = zoneAnchors(ZoneLattice.of(400.0, cells, zones))
        assertTrue("the thin lobe kept no letter", anchors.any { it.zone == ClarkeZone.E.ordinal })
    }

    /** Four zones lobe above and below the diagonal and get a letter each side; zone A straddles it,
     *  so its two candidates collapse to one rather than printing an A twice on one band. */
    @Test
    fun `a zone that lobes twice is lettered twice and one that straddles is lettered once`() {
        val byZone = zoneAnchors(syntheticGrid()).groupBy { it.zone }
        assertEquals(1, byZone[ClarkeZone.A.ordinal]?.size)
        listOf(ClarkeZone.B, ClarkeZone.C, ClarkeZone.D, ClarkeZone.E).forEach {
            assertEquals("zone $it", 2, byZone[it.ordinal]?.size)
        }
    }

    /** No lattice ⇒ nothing to paint and nothing to letter — never a half-drawn grid. */
    @Test
    fun `an empty lattice paints nothing`() {
        assertTrue(ZoneLattice.EMPTY.isEmpty)
        assertEquals(emptyList<ZoneRun>(), zoneRuns(ZoneLattice.EMPTY))
        assertEquals(emptyList<ZoneAnchor>(), zoneAnchors(ZoneLattice.EMPTY))
        // A lattice whose zone list does not match its declared size is refused whole.
        assertTrue(ZoneLattice.of(400.0, 4, listOf(ClarkeZone.A)).isEmpty)
    }

    /**
     * `zoneCount` is DERIVED from the cells, never taken on trust.
     *
     * [zoneAnchors] sizes its per-group arrays from it and then indexes them with the very ordinals
     * the lattice stores, so a count that undershot would throw inside composition rather than fail
     * closed. Deriving it means the mismatch cannot be constructed — which is what this pins.
     */
    @Test
    fun `zoneCount is derived from the cells it actually holds`() {
        assertEquals(ClarkeZone.values().size, syntheticGrid().zoneCount)
        // A classifier that only ever returns two zones yields a two-zone lattice, and the anchor
        // pass over it must letter those two and not walk off the end of its own arrays.
        val cells = 20
        val zones = List(cells * cells) { if (it % 2 == 0) ClarkeZone.A else ClarkeZone.B }
        val sparse = ZoneLattice.of(400.0, cells, zones)
        assertEquals(2, sparse.zoneCount)
        assertTrue(zoneAnchors(sparse).all { it.zone < sparse.zoneCount })
        assertTrue(zoneRuns(sparse).all { it.zone < sparse.zoneCount })
    }

    /** The lattice is truth-major, and it is the classifier's answer that says so — not this file. */
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

    /** A transposed lattice is a well-formed picture of a mirrored grid, so it is refused outright
     *  rather than painted — the probes carry no expected zone, only the classifier's own answer. */
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

    /** The shared reducer, pointed at the Clarke column — the selector is the only thing that
     *  differs between the two grids' scatters, and passing the wrong one is what this pins. */
    private fun clarkeZoneShares(pts: List<ScoredPoint>) = zoneShares(pts) { it.clarke.ordinal }

    /** The scatter's shares are counted off the very enums the core derived its percentages from,
     *  so they need no remainder for C and cannot round into a negative slice. */
    @Test
    fun `the scatter's shares partition its own points`() {
        val s = clarkeZoneShares(points(ClarkeZone.A to 82, ClarkeZone.B to 14, ClarkeZone.D to 3, ClarkeZone.E to 1))
        assertEquals(listOf(82f, 14f, 0f, 3f, 1f), s)
        assertEquals(100f, s.sum(), 1e-3f)
    }

    /** The same population reduced both ways — off the points here, off the core's four published
     *  totals in the stacked figure — must agree, or the two Clarke figures disagree on screen. */
    @Test
    fun `the two reductions of one population agree`() {
        val pts = points(ClarkeZone.A to 70, ClarkeZone.B to 10, ClarkeZone.C to 13, ClarkeZone.D to 5, ClarkeZone.E to 2)
        val fromTotals = clarkeShares(block(a = 70.0, ab = 80.0, d = 5.0, e = 2.0))
        clarkeZoneShares(pts).zip(fromTotals).forEach { (a, b) -> assertEquals(a, b, 1e-4f) }
    }

    /** No points ⇒ no shares, so the legend names the zones and claims nothing about them. */
    @Test
    fun `an empty scatter has no shares`() {
        assertEquals(emptyList<Float>(), clarkeZoneShares(emptyList()))
    }

    /** One selector per grid, and they must actually select differently — a scatter drawn with the
     *  wrong column would be a picture of the other grid under this one's letters. */
    @Test
    fun `the two grids' selectors read different columns of one series`() {
        val pts = listOf(
            ScoredPoint(pred = 200.0, truth = 100.0, clarke = ClarkeZone.C, dts = DtsZone.D, dtsRisk = 1.9),
            ScoredPoint(pred = 105.0, truth = 100.0, clarke = ClarkeZone.A, dts = DtsZone.A, dtsRisk = 0.1),
        )
        assertEquals(listOf(50f, 0f, 50f, 0f, 0f), zoneShares(pts) { it.clarke.ordinal })
        assertEquals(listOf(50f, 0f, 0f, 50f, 0f), zoneShares(pts) { it.dts.ordinal })
    }

    // ── The DTS grid's shares ──────────────────────────────────────────────────────────────────

    /** All five come off the core, so this is a pass-through — no remainder, and therefore no A+B
     *  quantity anywhere for a caller to reach for. */
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

    // ── The Trend Accuracy Matrix ──────────────────────────────────────────────────────────────

    private fun matrix(counts: List<Int>, pct: List<Double>) =
        TrendMatrix(counts, List(5) { 0 }, pct, counts.sum())

    /** Labels are derived from the core's own edges, so a change to the binning relabels the axis
     *  rather than leaving it captioning the old one. */
    @Test
    fun `bin labels are built from the core's edges`() {
        assertEquals(
            listOf("<-2", "-2..-1", "-1..1", "1..2", ">2"),
            trendBinLabels(listOf(-2.0, -1.0, 1.0, 2.0)),
        )
    }

    /** An edge list of the wrong length labels nothing: an axis captioned from a guess is worse
     *  than one with no labels, and a stub core supplies exactly that empty list. */
    @Test
    fun `a wrong-length edge list labels nothing`() {
        assertEquals(emptyList<String>(), trendBinLabels(emptyList()))
        assertEquals(emptyList<String>(), trendBinLabels(listOf(-1.0, 1.0)))
    }

    /** The matrix is read TRUTH-MAJOR, and [TrendMatrix.countAt] is what enforces it. A transposed
     *  read is well-formed and describes the opposite failure, so this pins the index arithmetic. */
    @Test
    fun `the matrix is read truth-major`() {
        val counts = MutableList(TREND_BINS * TREND_BINS) { 0 }
        counts[4 * TREND_BINS + 2] = 7 // truth rising fast, forecast flat
        val m = matrix(counts, List(5) { 0.0 })
        assertEquals(7, m.countAt(4, 2))
        assertEquals(0, m.countAt(2, 4))
        assertEquals(7, m.peak)
        // Off the table is 0, never an exception — a figure may probe any cell.
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
