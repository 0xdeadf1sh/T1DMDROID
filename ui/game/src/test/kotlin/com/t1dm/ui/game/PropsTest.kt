package com.t1dm.ui.game

import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.GamePropDensity
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.buildGraphFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PropsTest {

    private val GRID = 300_000L
    private val T0 = 1_700_000_000_000L
    private val alarms = AlertThresholds(55, 70, 180, 250)

    private fun reading(ts: Long, bg: Int?, flag: ReadingFlag = ReadingFlag.NORMAL) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 60, quality = 100, provenance = ReadingProvenance.MEASURED, flag = flag,
        tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

    /** Six hours of trace, 1080 m. */
    private fun day(bg: (Int) -> Int?): List<CgmReading> = (0 until 72).map { reading(T0 + it * GRID, bg(it)) }

    private fun trackOf(rs: List<CgmReading>) =
        buildGameTrack(TrackTrace.of(buildGraphFrame(rs, UnitSpace.MgDl, maxPoints = rs.size + 1)))

    private fun ascending(f: PropField) = (1 until f.size).all { f.xs[it] >= f.xs[it - 1] }

    @Test fun theSameTrackGrowsTheSameWorld() {
        val rs = day { 100 + (it % 7) * 9 }
        val a = buildProps(trackOf(rs), rs, alarms,GamePropDensity.Busy)
        val b = buildProps(trackOf(rs), rs, alarms,GamePropDensity.Busy)
        assertArrayEquals(a.ground.xs, b.ground.xs, 0f)
        assertArrayEquals(a.ground.kinds, b.ground.kinds)
        assertArrayEquals(a.clouds.seeds, b.clouds.seeds, 0f)
    }

    @Test fun everyFieldIsSortedAndBusyOutnumbersSparse() {
        val rs = day { 120 }
        val sparse = buildProps(trackOf(rs), rs, alarms,GamePropDensity.Sparse)
        val busy = buildProps(trackOf(rs), rs, alarms,GamePropDensity.Busy)
        for (f in listOf(sparse.underground, sparse.ground, sparse.clouds, sparse.birds, sparse.sky)) {
            assertTrue(ascending(f))
        }
        assertTrue("${busy.ground.size} vs ${sparse.ground.size}", busy.ground.size > 3 * sparse.ground.size / 2)
        assertTrue(busy.underground.size > sparse.underground.size)
        // 1080 m at 110 m ± 40 %: between 6 and 17 stands.
        assertTrue("${sparse.ground.size}", sparse.ground.size in 6..17)
    }

    @Test fun nothingStandsOverAGap() {
        // Readings 30..41 missing: a one-hour chasm in the middle of the trace.
        val rs = day { if (it in 30..41) null else 120 }.filter { it.bgMgdl != null }
        val track = trackOf(rs)
        val set = buildProps(track, rs, alarms,GamePropDensity.Busy)
        for (f in listOf(set.underground, set.ground, set.sky)) {
            for (i in 0 until f.size) assertTrue("x=${f.xs[i]}", track.groundAt(f.xs[i]).isFinite())
        }
    }

    @Test fun buriedPropsSitUnderTheGroundAndSkyPropsAbove() {
        val rs = day { 140 }
        val track = trackOf(rs)
        val set = buildProps(track, rs, alarms,GamePropDensity.Busy)
        for (i in 0 until set.underground.size) {
            val f = set.underground
            val g = track.groundAt(f.xs[i])
            val kind = f.kindAt(i)
            if (hangsFromGround(kind)) {
                assertEquals(g, f.ys[i], 1e-3f)
            } else {
                assertTrue("${kind} top above ground", f.ys[i] + propH(kind, f.seeds[i]) * 0.5f <= g + 1e-3f)
                assertTrue("${kind} below the world floor", f.ys[i] - propH(kind, f.seeds[i]) * 0.5f >= -1e-3f)
            }
        }
        for (f in listOf(set.clouds, set.birds, set.sky)) {
            for (i in 0 until f.size) assertTrue(f.ys[i] > track.groundAt(f.xs[i]) + 10f)
        }
    }

    @Test fun aKiteKeepsItsStringLength() {
        val rs = day { 90 }
        val track = trackOf(rs)
        val set = buildProps(track, rs, alarms,GamePropDensity.Busy)
        var kites = 0
        for (i in 0 until set.sky.size) {
            if (set.sky.kindAt(i) != PropKind.Kite) continue
            kites++
            assertEquals(set.sky.ys[i] - track.groundAt(set.sky.xs[i]), set.sky.amounts[i], 1e-3f)
        }
        assertTrue(kites > 0)
    }

    @Test fun oneSignPerExcursionAtItsExtreme() {
        val bg = intArrayOf(120, 65, 52, 60, 120, 120, 200, 260, 240, 120, 65, 120)
        val rs = bg.mapIndexed { i, v -> reading(T0 + i * GRID, v) }
        val set = buildProps(trackOf(rs), rs, alarms,GamePropDensity.Sparse)
        assertEquals(3, set.signs.size)
        assertEquals(PropKind.SignLow, set.signs.kindAt(0))
        assertEquals(52f, set.signs.amounts[0], 0f)
        assertEquals(PropKind.SignHigh, set.signs.kindAt(1))
        assertEquals(260f, set.signs.amounts[1], 0f)
        assertEquals(PropKind.SignLow, set.signs.kindAt(2))
        assertEquals("LO", set.signLabels[0])
        assertEquals("HI", set.signLabels[1])
        assertEquals(set.signs.size, set.signLabels.size)
    }

    @Test fun aDropoutCutsAnExcursionInTwo() {
        val rs = listOf(
            reading(T0, 60), reading(T0 + GRID, 55),
            reading(T0 + 20 * GRID, 58), reading(T0 + 21 * GRID, 120),
        )
        val set = buildProps(trackOf(rs), rs, alarms,GamePropDensity.Sparse)
        assertEquals(2, set.signs.size)
    }

    @Test fun obstaclesTraceTheSolidPropsOnly() {
        val rs = day { 130 }
        val track = trackOf(rs)
        val set = buildProps(track, rs, alarms, GamePropDensity.Busy)
        val all = set.obstacles(teeX = -1_000f, behindM = 0f, aheadM = 0f)
        val soft = setOf(PropKind.Bush, PropKind.Tuft, PropKind.Grass, PropKind.Flowers)
        val solid = (0 until set.ground.size).count { set.ground.kindAt(it) !in soft }
        assertTrue("${all.size} boxes for $solid stands", all.size >= solid)
        for (o in all) {
            assertTrue(o.halfW > 0f && o.h > 0f && o.lift >= 0f)
            assertTrue(track.groundAt(o.x).isFinite())
            assertTrue("nothing soft stands at ${o.x}", (0 until set.ground.size).none { set.ground.xs[it] == o.x && set.ground.kindAt(it) in soft })
        }
        val inset = set.obstacles(teeX = -1_000f, behindM = 0f, aheadM = 0f, insetM = 3f)
        assertEquals(all.size, inset.size)
        for (k in all.indices) {
            assertTrue(inset[k].halfW <= all[k].halfW && inset[k].h <= all[k].h)
            if (all[k].lift > 0f) assertEquals(all[k].lift + 3f, inset[k].lift, 1e-3f)
        }
    }

    @Test fun theKeepOutClearsAheadOfTheTeeAndBehindIt() {
        val rs = day { 130 }
        val set = buildProps(trackOf(rs), rs, alarms, GamePropDensity.Busy)
        val tee = set.ground.xs[3]
        val cleared = set.obstacles(teeX = tee, behindM = 15f, aheadM = 80f)
        for (o in cleared) assertTrue("${o.x} vs tee $tee", o.x + o.halfW < tee - 15f || o.x - o.halfW > tee + 80f)
        assertTrue(cleared.size < set.obstacles(teeX = -1_000f, behindM = 0f, aheadM = 0f).size)
    }

    @Test fun noThresholdsMeansNoSigns() {
        val rs = day { 50 }
        val set = buildProps(trackOf(rs), rs, null,GamePropDensity.Sparse)
        assertEquals(0, set.signs.size)
    }
}
