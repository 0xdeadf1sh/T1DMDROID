package com.t1dm.ui.game

import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.buildGraphFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the record leaves on the track. The trap these tests exist to catch is the conflation of the
 * three mg/dL bands: coins come from [TargetRange] (the stats TIR band), hazards from
 * [AlertThresholds.lowMgdl] (the user's alarm line, deliberately unbounded), and the graph's axis span
 * — which sets the world height — has nothing to do with either. They coincide at 70/180 by default
 * and must diverge the instant one is edited.
 */
class PickupsTest {

    private val GRID = 300_000L
    private val T0 = 1_700_000_000_000L

    private val target = TargetRange.DEFAULT                       // 70 / 180
    private val alarms = AlertThresholds(55, 70, 180, 250)

    private fun reading(ts: Long, bg: Int, flag: ReadingFlag = ReadingFlag.NORMAL) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 60, quality = 100, provenance = ReadingProvenance.MEASURED, flag = flag,
        tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

    private fun trackOf(rs: List<CgmReading>) =
        buildGameTrack(TrackTrace.of(buildGraphFrame(rs, UnitSpace.MgDl, maxPoints = rs.size + 1)))

    private fun countOf(f: PickupField, kind: PickupKind) = (0 until f.size).count { f.kindAt(it) == kind }

    @Test fun coinsUseTheTargetRangeInclusively() {
        // stats.rs scores in-range as `low <= bg <= high`, so both edges are coins and 181 is not.
        val bg = intArrayOf(69, 70, 120, 180, 181, 300)
        val rs = bg.mapIndexed { i, v -> reading(T0 + i * GRID, v) }
        val f = buildPickups(trackOf(rs), rs, target, alarms)
        assertEquals(3, countOf(f, PickupKind.Coin))
    }

    @Test fun coinsFollowTheTargetRangeAndHazardsFollowTheAlarmLine() {
        // The bands pulled apart: a tight 80-140 target and an alarm low dropped to 55. A reading of 65
        // is then OUT of range (no coin) but ABOVE the danger line (no hazard) — which is exactly what
        // the user asked for by setting them that way, and what deriving one from the other would break.
        val rs = listOf(65, 100, 50).mapIndexed { i, v -> reading(T0 + i * GRID, v) }
        val t = trackOf(rs)
        val f = buildPickups(t, rs, TargetRange(80, 140), AlertThresholds(40, 55, 180, 250))
        assertEquals(1, countOf(f, PickupKind.Coin))       // only the 100
        assertEquals(1, countOf(f, PickupKind.Hazard))     // only the 50
    }

    @Test fun oneHazardPerExcursionAtItsNadir() {
        // A single dip to 48 held over five readings. Twenty obstacles over one hypo would say the
        // record contained twenty hypos.
        val bg = intArrayOf(110, 90, 68, 60, 52, 48, 55, 66, 90, 120)
        val rs = bg.mapIndexed { i, v -> reading(T0 + i * GRID, v) }
        val t = trackOf(rs)
        val f = buildPickups(t, rs, target, alarms)

        assertEquals(1, countOf(f, PickupKind.Hazard))
        val h = (0 until f.size).first { f.kindAt(it) == PickupKind.Hazard }
        assertEquals("at the nadir", T0 + 5 * GRID, f.tsMs[h])
        assertEquals("worth its depth below the low line", 22f, f.amounts[h], 1e-4f)
        // Hazards sit ON the ground; only collectables float.
        assertEquals(t.groundAtMs(f.tsMs[h]), f.ys[h], 1e-3f)
    }

    @Test fun aDropoutSplitsOneLowRunIntoTwoExcursions() {
        // Low, then two silent hours, then low again. Nothing says it stayed low in between, so the
        // record holds two events and the track holds two hazards.
        val a = (0 until 4).map { reading(T0 + it * GRID, 60) }
        val b = (0 until 4).map { reading(a.last().tsMs + 120 * 60_000L + it * GRID, 58) }
        val rs = a + b
        val f = buildPickups(trackOf(rs), rs, target, alarms)
        assertEquals(2, countOf(f, PickupKind.Hazard))
    }

    @Test fun warmupReadingsAreNotScored() {
        // §3.6 suppresses warm-up from alarm evaluation; scoring it would be perverse in either sign.
        val rs = listOf(
            reading(T0, 120, ReadingFlag.WARMUP),
            reading(T0 + GRID, 50, ReadingFlag.WARMUP),
            reading(T0 + 2 * GRID, 120),
        )
        val f = buildPickups(trackOf(rs), rs, target, alarms)
        assertEquals(1, countOf(f, PickupKind.Coin))
        assertEquals(0, countOf(f, PickupKind.Hazard))
    }

    @Test fun theFieldIsAscendingInXAndSearchable() {
        val bg = intArrayOf(120, 60, 130, 45, 150, 90, 200, 110)
        val rs = bg.mapIndexed { i, v -> reading(T0 + i * GRID, v) }
        val t = trackOf(rs)
        val f = buildPickups(t, rs, target, alarms)

        assertTrue("something landed", f.size > 2)
        for (i in 1 until f.size) assertTrue("ascending at $i", f.xs[i] >= f.xs[i - 1])
        // firstFrom is the frame loop's window seek; it must be a true lower bound.
        val mid = f.xs[f.size / 2]
        val k = f.firstFrom(mid)
        assertTrue(k < f.size && f.xs[k] >= mid)
        assertTrue(k == 0 || f.xs[k - 1] < mid)
        assertEquals(f.size, f.firstFrom(f.xs[f.size - 1] + 1f))
    }

    @Test fun anUnplayableTrackYieldsNothing() {
        val rs = listOf(reading(T0, 120))
        val f = buildPickups(GameTrack.EMPTY, rs, target, alarms)
        assertTrue(f.isEmpty)
    }
}
