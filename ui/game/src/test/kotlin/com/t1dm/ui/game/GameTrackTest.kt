package com.t1dm.ui.game

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.buildGraphFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

class GameTrackTest {

    private val GRID = 300_000L
    private val T0 = 1_700_000_000_000L

    private fun reading(ts: Long, bg: Int, flag: ReadingFlag = ReadingFlag.NORMAL) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 60, quality = 100, provenance = ReadingProvenance.MEASURED, flag = flag,
        tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

    /** `t1dm-core::kovatchev_f` transcribed, so the test needs no JNI. */
    private val kovatchevF: (Double) -> Double = { g -> 1.509 * (ln(g.coerceIn(20.0, 500.0)).pow(1.084) - 5.381) }

    private fun day(): List<CgmReading> {
        val bg = intArrayOf(
            110, 118, 130, 148, 170, 192, 210, 218, 214, 200, 182, 165, 150, 138, 128, 120,
            114, 108, 100, 92, 84, 76, 68, 61, 55, 52, 58, 70, 86, 104, 122, 138, 150, 158,
            160, 156, 148, 140, 132, 126,
        )
        return bg.mapIndexed { i, v -> reading(T0 + i * GRID, v) }
    }

    private fun traceIn(unit: UnitSpace, readings: List<CgmReading>): TrackTrace =
        TrackTrace.of(
            buildGraphFrame(
                readings, unit, maxPoints = readings.size + 1,
                kovatchevF = if (unit == UnitSpace.Kovatchev) kovatchevF else null,
            ),
        )

    private fun trackIn(unit: UnitSpace, readings: List<CgmReading> = day()) =
        buildGameTrack(
            traceIn(unit, readings),
            kovatchevF = if (unit == UnitSpace.Kovatchev) kovatchevF else null,
        )

    @Test fun mgdlAndMmolProduceTheSameTerrain() {
        val a = trackIn(UnitSpace.MgDl)
        val b = trackIn(UnitSpace.MmolL)
        assertEquals("same sample count", a.heights.size, b.heights.size)
        assertEquals("same world height", a.map.worldHeight, b.map.worldHeight, 0f)
        assertEquals("same track length", a.length, b.length, 0f)
        for (i in a.heights.indices) {
            assertEquals("height[$i]", a.heights[i], b.heights[i], 1e-3f)
        }
    }

    @Test fun kovatchevKeepsTheWorldHeightAndTheOrderOfEveryHill() {
        val a = trackIn(UnitSpace.MgDl)
        val k = trackIn(UnitSpace.Kovatchev)
        assertEquals(a.heights.size, k.heights.size)
        assertEquals(a.map.worldHeight, k.map.worldHeight, 0f)

        val rawSpanRatio = (a.map.valueHi - a.map.valueLo) / (k.map.valueHi - k.map.valueLo)
        assertTrue("the raw spans really are wildly different ($rawSpanRatio)", rawSpanRatio > 20f)

        for (h in a.heights + k.heights) {
            assertTrue("every height is inside the world", h in 0f..WORLD_HEIGHT_M)
        }
        // Monotone f preserves the rank of every sample; the heights themselves differ, and should.
        for (i in 1 until a.heights.size) {
            val da = a.heights[i] - a.heights[i - 1]
            val dk = k.heights[i] - k.heights[i - 1]
            if (abs(da) > 1e-3f) {
                assertTrue("sample $i moves the same way in both spaces", da > 0f == dk > 0f)
            }
        }
    }

    @Test fun riskSpaceSteepensTheHypoAndFlattensTheHyper() {
        // day()'s legs: 61→55 mg/dL into the low, 200→182 down from the plateau.
        val a = trackIn(UnitSpace.MgDl)
        val k = trackIn(UnitSpace.Kovatchev)
        fun drop(t: GameTrack, i: Int) = t.groundAtMs(T0 + i * GRID) - t.groundAtMs(T0 + (i + 1) * GRID)

        assertTrue(
            "the hypo leg is steeper in risk space (${drop(k, 23)} vs ${drop(a, 23)})",
            drop(k, 23) > drop(a, 23),
        )
        assertTrue(
            "the hyper leg is gentler (${drop(k, 9)} vs ${drop(a, 9)})",
            drop(k, 9) < drop(a, 9),
        )
    }

    @Test fun aFlatTraceDoesNotBecomeAMountainRange() {
        // Normalising to the data extent alone would turn a couple of counts of noise into cliffs.
        val flat = (0 until 60).map { reading(T0 + it * GRID, 100 + (it % 3) - 1) }
        val t = trackIn(UnitSpace.MgDl, flat)
        val lo = t.heights.min()
        val hi = t.heights.max()
        assertTrue("a flat day is flat ground (${hi - lo} m of relief)", hi - lo < 1f)
    }

    @Test fun aReadingBeyondTheAxisStillHasGroundUnderIt() {
        // The span grows to fit rather than clipping; a negative height would read as a gap.
        val spike = (0 until 20).map { reading(T0 + it * GRID, if (it == 10) 400 else 110) }
        val t = trackIn(UnitSpace.MgDl, spike)
        assertTrue("the spike clears the ceiling", t.heights.max() > WORLD_HEIGHT_M * 0.9f)
        assertTrue("no solid sample is negative", t.heights.filter { it.isFinite() }.all { it >= 0f })
    }

    @Test fun theNadirIsGroundEvenWhenTheAxisFloorSitsOnIt() {
        // `a+(b-a)·1` can land 1 ulp under `b`; at the run min this reads no ground (mg/dL exact).
        val bg = intArrayOf(140, 120, 108, 35, 60, 90, 110, 126)
        val rs = bg.mapIndexed { i, v -> reading(T0 + i * GRID, v) }
        val t = buildGameTrack(traceIn(UnitSpace.MmolL, rs), rangeMinMgdl = 70, rangeMaxMgdl = 200)

        assertEquals("the axis floor really is the datum", 0f, t.heights.min(), 0f)
        assertTrue("no solid sample is negative", t.heights.none { it.isFinite() && it < 0f })
        assertTrue("the nadir is ground", t.groundAtMs(T0 + 3 * GRID) >= 0f)
    }

    @Test fun aDropoutBecomesAChasm() {
        val before = (0 until 12).map { reading(T0 + it * GRID, 120) }
        val resume = before.last().tsMs + 90 * 60_000L      // past maxGapMin
        val after = (0 until 12).map { reading(resume + it * GRID, 130) }
        val t = trackIn(UnitSpace.MgDl, before + after)

        val gaps = t.heights.count { it.isNaN() }
        assertTrue("the dropout is a hole in the ground ($gaps samples)", gaps > 60)

        val lastBefore = t.map.worldXOf(before.last().tsMs)
        val firstAfter = t.map.worldXOf(after.first().tsMs)
        assertTrue("the near lip is ground", t.groundAt(lastBefore).isFinite())
        assertTrue("the far lip is ground", t.groundAt(firstAfter).isFinite())
        assertFalse("mid-chasm is not", t.groundAt((lastBefore + firstAfter) / 2f).isFinite())
    }

    @Test fun contiguousDataHasNoChasms() {
        val t = trackIn(UnitSpace.MgDl)
        assertTrue("an unbroken day is unbroken ground", t.heights.none { it.isNaN() })
    }

    @Test fun anIsolatedReadingIsWideEnoughToLandOn() {
        // `forEachTraceRun` emits a marooned reading as a run of one: one sample wide is a needle.
        val a = (0 until 8).map { reading(T0 + it * GRID, 120) }
        val lone = reading(a.last().tsMs + 90 * 60_000L, 150)
        val b = (0 until 8).map { reading(lone.tsMs + 90 * 60_000L + it * GRID, 130) }
        val t = trackIn(UnitSpace.MgDl, a + listOf(lone) + b)

        val i = Math.round(t.map.worldXOf(lone.tsMs) / t.dx)
        var solid = 0
        var j = i
        while (j >= 0 && t.heights[j].isFinite()) { solid++; j-- }
        j = i + 1
        while (j < t.heights.size && t.heights[j].isFinite()) { solid++; j++ }
        assertTrue("the island is landable ($solid samples)", solid * t.dx >= 4f)
        assertTrue("but it is still an island", t.heights.count { it.isNaN() } > 100)
    }

    @Test fun interpolatedPointsAreSolidGround() {
        // The panel bridges interpolated readings, so ground does too; only a real dropout cuts.
        val rs = (0 until 20).map {
            reading(T0 + it * GRID, 120).copy(
                provenance = if (it in 8..11) ReadingProvenance.INTERPOLATED else ReadingProvenance.MEASURED,
            )
        }
        val t = trackIn(UnitSpace.MgDl, rs)
        assertTrue(t.heights.none { it.isNaN() })
    }

    @Test fun worldXIsAffineInTimeAndInvertsExactly() {
        val t = trackIn(UnitSpace.MgDl)
        assertEquals(0f, t.map.worldXOf(T0), 0f)
        assertEquals(60f * METRES_PER_MINUTE, t.map.worldXOf(T0 + 3_600_000L), 1e-3f)
        for (ts in longArrayOf(T0, T0 + GRID, T0 + 7 * GRID, T0 + 39 * GRID)) {
            assertEquals(ts, t.map.tsMsAt(t.map.worldXOf(ts)))
        }
    }

    @Test fun theTrackRunsFromTheStartInstantToTheLastReading() {
        val rs = day()
        val t = trackIn(UnitSpace.MgDl, rs)
        assertEquals(rs.first().tsMs, t.startMs)
        assertEquals(rs.last().tsMs, t.endMs)
        assertEquals(t.map.worldXOf(rs.last().tsMs), t.length, t.dx)
        assertTrue(t.groundAt(0f).isFinite())
        assertTrue(t.groundAt(t.length).isFinite())
    }

    @Test fun theHeightfieldReproducesTheTraceAtEveryReading() {
        val rs = day()
        val t = trackIn(UnitSpace.MgDl, rs)
        for (r in rs) {
            assertEquals(
                "ground under the reading at ${r.tsMs}",
                t.map.worldYOf(r.bgMgdl!!.toFloat()), t.groundAtMs(r.tsMs), 1e-2f,
            )
        }
    }

    @Test fun anOverlongSpanCoarsensTheGridRatherThanBeingRefused() {
        // 200 days is past the solver's 200 000-sample cap, reached at about 139 days.
        val rs = (0 until 200 * 288).map { reading(T0 + it * GRID, 100 + (it % 60)) }
        val t = buildGameTrack(traceIn(UnitSpace.MgDl, rs))
        assertTrue("within the solver's cap", t.heights.size <= MAX_TERRAIN_SAMPLES)
        assertTrue("the grid coarsened instead", t.dx > TERRAIN_DX_M)
        assertTrue("and the track still spans the record", t.length > 200_000f)
    }

    @Test fun theSpecSatisfiesEverythingGameWorldValidates() {
        // `GameWorld::new` rejects rather than clamps, so everything it checks is checked here.
        val a = (0 until 10).map { reading(T0 + it * GRID, 120) }
        val b = (0 until 10).map { reading(a.last().tsMs + 60 * 60_000L + it * GRID, 90) }
        val spec = trackIn(UnitSpace.MgDl, a + b).terrain

        assertTrue("MIN_TERRAIN_SAMPLES", spec.heights.size >= 2)
        assertTrue("MAX_TERRAIN_SAMPLES", spec.heights.size <= MAX_TERRAIN_SAMPLES)
        assertTrue("dx finite and positive", spec.dx.isFinite() && spec.dx > 0f)
        assertTrue("worldHeight finite and positive", spec.worldHeight.isFinite() && spec.worldHeight > 0f)
        // `solid(h) = h.is_finite() && h >= 0.0`.
        assertTrue("no sample is negative-but-finite", spec.heights.none { it.isFinite() && it < 0f })
        assertTrue("the chasm is encoded as non-finite", spec.heights.any { !it.isFinite() })
        assertTrue("and it is a view of the same field", spec.heights.size == trackIn(UnitSpace.MgDl, a + b).heights.size)
    }

    @Test fun anEmptyRecordYieldsAnUnplayableTrackRatherThanThrowing() {
        val t = buildGameTrack(TrackTrace.EMPTY)
        assertFalse(t.isPlayable)
        assertEquals(0f, t.length, 0f)
        assertTrue(t.groundAt(0f).isNaN())
    }
}
