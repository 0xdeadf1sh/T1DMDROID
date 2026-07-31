package com.t1dm.feature.models

import com.t1dm.core.model.CgEgaRegion
import com.t1dm.core.model.ClarkePoint
import com.t1dm.core.model.ClarkeZone
import com.t1dm.core.model.ClarkeZoneGrid
import com.t1dm.core.model.PointBlock
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
        clarkeA = a, clarkeAb = ab, clarkeD = d, clarkeE = e, skillPoint = 0.3,
        clarkePoints = emptyList(),
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
    private fun syntheticGrid(cells: Int = 40): ClarkeZoneGrid {
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
        return ClarkeZoneGrid.of(400.0, cells, zones)
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
                    assertEquals(run.zone, grid.zoneAt(ti, pi))
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
            assertEquals("letter ${a.zone} at (${a.truthMgdl}, ${a.predMgdl})", a.zone, grid.zoneAt(ti!!, pi!!))
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
                    grid.zoneAt(ti + dt, pi + dp),
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
        val anchors = zoneAnchors(ClarkeZoneGrid.of(400.0, cells, zones))
        assertTrue("the thin lobe kept no letter", anchors.any { it.zone == ClarkeZone.E })
    }

    /** Four zones lobe above and below the diagonal and get a letter each side; zone A straddles it,
     *  so its two candidates collapse to one rather than printing an A twice on one band. */
    @Test
    fun `a zone that lobes twice is lettered twice and one that straddles is lettered once`() {
        val byZone = zoneAnchors(syntheticGrid()).groupBy { it.zone }
        assertEquals(1, byZone[ClarkeZone.A]?.size)
        listOf(ClarkeZone.B, ClarkeZone.C, ClarkeZone.D, ClarkeZone.E).forEach {
            assertEquals("zone $it", 2, byZone[it]?.size)
        }
    }

    /** No lattice ⇒ nothing to paint and nothing to letter — never a half-drawn grid. */
    @Test
    fun `an empty lattice paints nothing`() {
        assertTrue(ClarkeZoneGrid.EMPTY.isEmpty)
        assertEquals(emptyList<ZoneRun>(), zoneRuns(ClarkeZoneGrid.EMPTY))
        assertEquals(emptyList<ZoneAnchor>(), zoneAnchors(ClarkeZoneGrid.EMPTY))
        // A lattice whose zone list does not match its declared size is refused whole.
        assertTrue(ClarkeZoneGrid.of(400.0, 4, listOf(ClarkeZone.A)).isEmpty)
    }

    /** The lattice is truth-major, and it is the classifier's answer that says so — not this file. */
    @Test
    fun `the lattice keeps the classifier's orientation`() {
        val grid = ClarkeZoneGrid.build(::asymmetric)
        assertTrue(!grid.isEmpty)
        for (ti in listOf(0, 37, 91, ClarkeZoneGrid.CELLS - 1)) {
            for (pi in listOf(0, 12, 140, ClarkeZoneGrid.CELLS - 1)) {
                assertEquals(asymmetric(listOf(grid.coordAt(ti)), listOf(grid.coordAt(pi))).single(), grid.zoneAt(ti, pi))
            }
        }
    }

    /** A transposed lattice is a well-formed picture of a mirrored grid, so it is refused outright
     *  rather than painted — the probes carry no expected zone, only the classifier's own answer. */
    @Test
    fun `a transposed or short classifier yields no lattice`() {
        assertTrue(ClarkeZoneGrid.build { t, p -> asymmetric(p, t) }.isEmpty)
        assertTrue(ClarkeZoneGrid.build { _, _ -> listOf(ClarkeZone.A) }.isEmpty)
        assertTrue(ClarkeZoneGrid.build { _, _ -> emptyList() }.isEmpty)
    }

    private fun points(vararg zones: Pair<ClarkeZone, Int>): List<ClarkePoint> =
        zones.flatMap { (z, n) -> List(n) { ClarkePoint(pred = 120.0, truth = 110.0, zone = z) } }

    /** The scatter's shares are counted off the very enums the core derived its percentages from,
     *  so they need no remainder for C and cannot round into a negative slice. */
    @Test
    fun `the scatter's shares partition its own points`() {
        val s = zoneShares(points(ClarkeZone.A to 82, ClarkeZone.B to 14, ClarkeZone.D to 3, ClarkeZone.E to 1))
        assertEquals(listOf(82f, 14f, 0f, 3f, 1f), s)
        assertEquals(100f, s.sum(), 1e-3f)
    }

    /** The same population reduced both ways — off the points here, off the core's four published
     *  totals in the stacked figure — must agree, or the two Clarke figures disagree on screen. */
    @Test
    fun `the two reductions of one population agree`() {
        val pts = points(ClarkeZone.A to 70, ClarkeZone.B to 10, ClarkeZone.C to 13, ClarkeZone.D to 5, ClarkeZone.E to 2)
        val fromTotals = clarkeShares(block(a = 70.0, ab = 80.0, d = 5.0, e = 2.0))
        zoneShares(pts).zip(fromTotals).forEach { (a, b) -> assertEquals(a, b, 1e-4f) }
    }

    /** No points ⇒ no shares, so the legend names the zones and claims nothing about them. */
    @Test
    fun `an empty scatter has no shares`() {
        assertEquals(emptyList<Float>(), zoneShares(emptyList()))
    }
}
