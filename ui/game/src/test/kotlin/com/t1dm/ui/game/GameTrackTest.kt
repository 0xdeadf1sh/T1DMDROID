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

/**
 * Host JVM tests for the terrain build. Two properties carry the design and are worth stating before
 * the assertions:
 *
 *  1. **The physics scale is unit-independent.** mg/dL and mmol/L are affine images of one another and
 *     [worldValueSpan] rounds nothing, so their heightfields must agree to float error — not merely
 *     look alike. Kovatchev cannot: `f` is monotone but NONLINEAR, so no honest test asserts an
 *     identical shape there. What survives the transform is the ORDER of every height and the world
 *     envelope, and that is what is asserted — the car meets the same span of world whichever space
 *     the panel was in, which is the actual claim (raw values would have differed by ~100x).
 *  2. **A dropout is a hole.** `breakAfter` cuts the ground exactly where the polyline is cut.
 */
class GameTrackTest {

    private val GRID = 300_000L
    private val T0 = 1_700_000_000_000L

    private fun reading(ts: Long, bg: Int, flag: ReadingFlag = ReadingFlag.NORMAL) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 60, quality = 100, provenance = ReadingProvenance.MEASURED, flag = flag,
        tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

    /** `t1dm-core::kovatchev_f`, constants and clamp transcribed, so the test needs no JNI. Only the
     *  scale of what it does matters here; the transform itself is golden-tested in the crate. */
    private val kovatchevF: (Double) -> Double = { g -> 1.509 * (ln(g.coerceIn(20.0, 500.0)).pow(1.084) - 5.381) }

    /** A day of plausible trace: a rise, a plateau, a fall through the low threshold and a recovery. */
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

    // ── 1. normalisation ────────────────────────────────────────────────────────────────────

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

        // Raw Kovatchev values span ~4.7 against mg/dL's ~230 — a ~50x difference that normalisation
        // must erase, or the same car is a mountain goat in one space and immovable in the other.
        val rawSpanRatio = (a.map.valueHi - a.map.valueLo) / (k.map.valueHi - k.map.valueLo)
        assertTrue("the raw spans really are wildly different ($rawSpanRatio)", rawSpanRatio > 20f)

        for (h in a.heights + k.heights) {
            assertTrue("every height is inside the world", h in 0f..WORLD_HEIGHT_M)
        }
        // Monotone f ⇒ the RANK of every sample is preserved, which is the strongest shape claim a
        // nonlinear space admits. The heights themselves differ, and should: risk space stretches the
        // hypo end, so the fall into the low is a steeper hill. That is what the unit is for.
        for (i in 1 until a.heights.size) {
            val da = a.heights[i] - a.heights[i - 1]
            val dk = k.heights[i] - k.heights[i - 1]
            if (abs(da) > 1e-3f) {
                assertTrue("sample $i moves the same way in both spaces", da > 0f == dk > 0f)
            }
        }
    }

    @Test fun riskSpaceSteepensTheHypoAndFlattensTheHyper() {
        // The one thing the normalisation must NOT erase: which parts of the day are hard. Kovatchev
        // magnifies the low end and compresses the high end, so the same 5-minute leg is a steeper
        // descent under a hypo and a gentler one coming off a peak. Both legs are taken from day():
        // 61→55 mg/dL on the way into the low, 200→182 on the way down from the plateau.
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
        // 100 mg/dL with a couple of counts of sensor noise. Normalising to the DATA extent alone
        // would turn that into full-height cliffs; the configured axis span is what keeps it flat.
        val flat = (0 until 60).map { reading(T0 + it * GRID, 100 + (it % 3) - 1) }
        val t = trackIn(UnitSpace.MgDl, flat)
        val lo = t.heights.min()
        val hi = t.heights.max()
        assertTrue("a flat day is flat ground (${hi - lo} m of relief)", hi - lo < 1f)
    }

    @Test fun aReadingBeyondTheAxisStillHasGroundUnderIt() {
        // 400 mg/dL is far above the default 250 ceiling. The span grows to fit rather than clipping,
        // so the spike is a hill and not a plateau — and no height is ever negative, which the solver
        // would read as a gap.
        val spike = (0 until 20).map { reading(T0 + it * GRID, if (it == 10) 400 else 110) }
        val t = trackIn(UnitSpace.MgDl, spike)
        assertTrue("the spike clears the ceiling", t.heights.max() > WORLD_HEIGHT_M * 0.9f)
        assertTrue("no solid sample is negative", t.heights.filter { it.isFinite() }.all { it >= 0f })
    }

    @Test fun theNadirIsGroundEvenWhenTheAxisFloorSitsOnIt() {
        // A grid sample that coincides with a reading is still COMPUTED as `a + (b - a)·1`, which in
        // float can land one ulp under `b`. Where `b` is the run's minimum and the configured axis
        // floor sits on it (worldValueSpan then puts valueLo AT the data), the mapped height goes
        // negative — and `t1dm-core::game::solid` reads a negative sample as NO GROUND, so the deepest
        // point of the trace becomes an invisible chasm the canvas (which tests only isNaN) draws as
        // solid. Non-integer value spaces only: mg/dL readings map exactly.
        val bg = intArrayOf(140, 120, 108, 35, 60, 90, 110, 126)
        val rs = bg.mapIndexed { i, v -> reading(T0 + i * GRID, v) }
        val t = buildGameTrack(traceIn(UnitSpace.MmolL, rs), rangeMinMgdl = 70, rangeMaxMgdl = 200)

        assertEquals("the axis floor really is the datum", 0f, t.heights.min(), 0f)
        assertTrue("no solid sample is negative", t.heights.none { it.isFinite() && it < 0f })
        assertTrue("the nadir is ground", t.groundAtMs(T0 + 3 * GRID) >= 0f)
    }

    // ── 2. chasms ───────────────────────────────────────────────────────────────────────────

    @Test fun aDropoutBecomesAChasm() {
        val before = (0 until 12).map { reading(T0 + it * GRID, 120) }
        val resume = before.last().tsMs + 90 * 60_000L      // a 90-min dropout, well past maxGapMin
        val after = (0 until 12).map { reading(resume + it * GRID, 130) }
        val t = trackIn(UnitSpace.MgDl, before + after)

        val gaps = t.heights.count { it.isNaN() }
        assertTrue("the dropout is a hole in the ground ($gaps samples)", gaps > 60)

        // The lips are solid and the middle is not: fall in and the run is over.
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
        // One reading marooned between two dropouts: `forEachTraceRun` emits it as a run of one, which
        // at one sample wide would be a needle rather than a platform.
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
        // Gap-filled readings are drawn faintly but they ARE drawn: the panel bridged them, so the
        // ground bridges them too. Only a real dropout cuts.
        val rs = (0 until 20).map {
            reading(T0 + it * GRID, 120).copy(
                provenance = if (it in 8..11) ReadingProvenance.INTERPOLATED else ReadingProvenance.MEASURED,
            )
        }
        val t = trackIn(UnitSpace.MgDl, rs)
        assertTrue(t.heights.none { it.isNaN() })
    }

    // ── 3. the x axis is time, exactly ──────────────────────────────────────────────────────

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
        // Left→right IS forward in time: the finish line is the most recent reading.
        assertEquals(t.map.worldXOf(rs.last().tsMs), t.length, t.dx)
        assertTrue(t.groundAt(0f).isFinite())
        assertTrue(t.groundAt(t.length).isFinite())
    }

    @Test fun theHeightfieldReproducesTheTraceAtEveryReading() {
        // dx divides the 5-min grid, so resampling is exact at the readings and linear between —
        // the terrain IS the polyline rather than an approximation of it.
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
        // 200 days at one metre per minute is 288 km — past the solver's 200 000-sample cap, which is
        // reached at about 139 days. Level of detail belongs to the grid, never to the trace.
        val rs = (0 until 200 * 288).map { reading(T0 + it * GRID, 100 + (it % 60)) }
        val t = buildGameTrack(traceIn(UnitSpace.MgDl, rs))
        assertTrue("within the solver's cap", t.heights.size <= MAX_TERRAIN_SAMPLES)
        assertTrue("the grid coarsened instead", t.dx > TERRAIN_DX_M)
        assertTrue("and the track still spans the record", t.length > 200_000f)
    }

    @Test fun theSpecSatisfiesEverythingGameWorldValidates() {
        // `GameWorld::new` is the one fallible entry point on the seam and it rejects rather than
        // clamps. Everything it checks is checked here, on a track that has a chasm in it, so a
        // heightfield can never reach the constructor in a state it will refuse.
        val a = (0 until 10).map { reading(T0 + it * GRID, 120) }
        val b = (0 until 10).map { reading(a.last().tsMs + 60 * 60_000L + it * GRID, 90) }
        val spec = trackIn(UnitSpace.MgDl, a + b).terrain

        assertTrue("MIN_TERRAIN_SAMPLES", spec.heights.size >= 2)
        assertTrue("MAX_TERRAIN_SAMPLES", spec.heights.size <= MAX_TERRAIN_SAMPLES)
        assertTrue("dx finite and positive", spec.dx.isFinite() && spec.dx > 0f)
        assertTrue("worldHeight finite and positive", spec.worldHeight.isFinite() && spec.worldHeight > 0f)
        // `solid(h) = h.is_finite() && h >= 0.0`: a sample is ground or it is a chasm, never a
        // negative height that would read as a hole the panel never showed.
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
