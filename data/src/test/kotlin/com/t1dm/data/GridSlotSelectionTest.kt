package com.t1dm.data

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.CgmReadingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [supersedesGridSlot] — which of one sensor's samples claims a five-minute slot — plus the two
 * pieces of arithmetic the sub-grid store rests on: the window of filed instants that snap into a
 * slot, and the retention cutoff.
 */
class GridSlotSelectionTest {

    @Test
    fun `an empty slot takes whatever arrives`() {
        assertTrue(supersedesGridSlot(null, measured(SLOT, SLOT)))
        assertTrue(supersedesGridSlot(null, interpolated(SLOT)))
    }

    /**
     * The guarantee this change is bounded by: for a sensor that samples on the grid, the stored
     * series is exactly what plain `INSERT OR REPLACE` produced. Such a sensor never offers two
     * measurements for one slot, so the contest is never reached — asserted here by folding a
     * realistic stream both ways and demanding the same map. The stream carries everything a
     * five-minute sensor can produce: jittered receptions, a dropout and its gap-fills, a
     * re-delivered advert, and a warm-up row a later gap-fill lands on top of.
     */
    @Test
    fun `a five-minute sensor gets the same series as replace-in-place`() {
        val stream = ArrayList<CgmReadingEntity>()
        val jitter = longArrayOf(-2_000, 41_000, -66_000, 12_000, 140_000, -149_000)
        for (i in 0..5) {
            val slot = SLOT + i * GRID
            stream += measured(slot, slot + jitter[i])
        }
        // A dropout: slots 6 and 7 are never measured, and the stamper fills them when slot 8 lands.
        stream += interpolated(SLOT + 6 * GRID)
        stream += interpolated(SLOT + 7 * GRID)
        stream += measured(SLOT + 8 * GRID, SLOT + 8 * GRID + 3_000)
        // The same advert delivered twice — an identical row, so neither fold may move.
        stream += stream[3]
        // A suppressed warm-up row that a later gap-fill overwrites. This is the one place the two
        // provenances contend for a slot, and the contest deliberately declines to re-decide it.
        stream += measured(SLOT + 9 * GRID, SLOT + 9 * GRID + 1_000, flag = ReadingFlag.WARMUP)
        stream += interpolated(SLOT + 9 * GRID)

        assertEquals(lastWriterWins(stream), withContest(stream))
    }

    /**
     * A THREE-minute sensor filed on its own sample clock — the case the contest exists for, and the one
     * the five-minute guarantee above deliberately does not reach.
     *
     * Five samples fall into three slots, so two slots are contested. Which sample each keeps is decided by
     * nearness to the slot instant and by nothing else, so the answer is the same however the five arrive:
     * a burst drained out of order, or a re-delivery after a reconnect, lands on the same three rows.
     */
    @Test
    fun `a three-minute sensor keeps the sample nearest each slot, whatever the arrival order`() {
        val stream = (0 until 5).map { i ->
            val sampledAt = SLOT + i * 180_000L
            measured(T1dmRepository.snapToGrid(sampledAt), sampledAt).copy(bgMgdl = 100 + i)
        }
        val expected = mapOf(
            SLOT to 100,             // 0 min: alone in its slot
            SLOT + GRID to 102,      // 6 min, one minute from the slot — against the 3-min sample's two
            SLOT + 2 * GRID to 103,  // 9 min, one minute from the slot — against the 12-min sample's two
        )
        val orders = listOf(
            stream,
            stream.reversed(),
            listOf(stream[3], stream[0], stream[4], stream[2], stream[1]),
            listOf(stream[2], stream[4], stream[1], stream[3], stream[0]),
        )
        for (order in orders) {
            assertEquals(
                "this arrival order chose differently",
                expected,
                withContest(order).mapValues { it.value.bgMgdl },
            )
        }
    }

    @Test
    fun `the sample received nearest the slot instant keeps it`() {
        val near = measured(SLOT, SLOT + 20_000)
        val far = measured(SLOT, SLOT + 130_000)
        assertFalse("the further sample took the slot", supersedesGridSlot(near, far))
        assertTrue("the nearer sample did not take the slot", supersedesGridSlot(far, near))
    }

    /** Distance is absolute: a sample early by 20 s beats one late by 130 s. */
    @Test
    fun `nearness is measured either side of the instant`() {
        val early = measured(SLOT, SLOT - 20_000)
        val late = measured(SLOT, SLOT + 130_000)
        assertFalse(supersedesGridSlot(early, late))
        assertTrue(supersedesGridSlot(late, early))
    }

    /**
     * The winner is a function of the SET of samples, not of the order they were written — so a
     * retried or out-of-order persist lands on the same row a clean run does.
     */
    @Test
    fun `the winner does not depend on arrival order`() {
        val a = measured(SLOT, SLOT - 120_000)
        val b = measured(SLOT, SLOT + 30_000)
        val c = measured(SLOT, SLOT + 90_000)
        assertEquals(b, withContest(listOf(a, b, c))[SLOT])
        for (order in listOf(listOf(c, b, a), listOf(b, a, c), listOf(c, a, b), listOf(a, c, b))) {
            assertEquals("this arrival order chose differently", b, withContest(order)[SLOT])
        }
    }

    /**
     * Equidistant is reachable — a slot's five minutes are symmetric about it — so the tie-break has
     * to be stated: the EARLIER reception wins, which is to say the incumbent keeps the slot.
     */
    @Test
    fun `equidistant samples resolve to the earlier reception`() {
        val early = measured(SLOT, SLOT - 60_000)
        val late = measured(SLOT, SLOT + 60_000)
        assertFalse("the later reception took the slot on a tie", supersedesGridSlot(early, late))
        assertTrue("the earlier reception did not take the slot on a tie", supersedesGridSlot(late, early))
    }

    @Test
    fun `a warm-up sample never displaces a measurement, however near`() {
        val normalFar = measured(SLOT, SLOT + 140_000)
        val warmupNear = measured(SLOT, SLOT + 1_000, flag = ReadingFlag.WARMUP)
        assertFalse(supersedesGridSlot(normalFar, warmupNear))
        assertTrue(
            "a usable measurement did not displace a suppressed one",
            supersedesGridSlot(warmupNear, normalFar),
        )
    }

    /**
     * A gap-fill and a measurement are not two candidates for one instant, so the contest sits this
     * one out and the write behaves exactly as it did before: replace in place.
     */
    @Test
    fun `an interpolated row is left to replace in place`() {
        assertTrue(supersedesGridSlot(measured(SLOT, SLOT + 1_000), interpolated(SLOT)))
        assertTrue(supersedesGridSlot(interpolated(SLOT), measured(SLOT, SLOT + 140_000)))
    }

    /**
     * One source cannot receive two different samples in the same millisecond, so an identical
     * receive instant is the same write arriving again — a retry, or a caller rewriting a slot on
     * purpose (the debug seeder does). That replaces in place, exactly as it did before.
     */
    @Test
    fun `the same receive instant replaces in place`() {
        val row = measured(SLOT, SLOT + 40_000)
        assertTrue(supersedesGridSlot(row, row.copy(bgMgdl = 999)))
        assertTrue(supersedesGridSlot(row, row))
    }

    /**
     * The slot window is the inverse of the snap, and this is what holds it there: every instant in
     * the window snaps into the slot and the instants either side of it do not. Spelling those bounds
     * again in a caller is what this makes unnecessary.
     */
    @Test
    fun `the slot window is exactly the instants that snap into the slot`() {
        val window = T1dmRepository.rawSampleWindowFor(SLOT)
        assertEquals(SLOT - GRID / 2, window.first)
        assertEquals(SLOT + GRID / 2 - 1, window.last)
        for (ms in longArrayOf(window.first, window.first + 1, SLOT, window.last - 1, window.last)) {
            assertEquals("$ms is inside the window but snaps elsewhere", SLOT, T1dmRepository.snapToGrid(ms))
        }
        assertEquals(SLOT - GRID, T1dmRepository.snapToGrid(window.first - 1))
        assertEquals(SLOT + GRID, T1dmRepository.snapToGrid(window.last + 1))
        // Adjacent windows tile: no instant belongs to two slots, none to neither.
        assertEquals(window.last + 1, T1dmRepository.rawSampleWindowFor(SLOT + GRID).first)
    }

    @Test
    fun `the retention cutoff is one bound behind now`() {
        val now = 1_700_000_100_000L
        assertEquals(
            now - T1dmRepository.RAW_SAMPLE_RETENTION_MS,
            T1dmRepository.rawSampleCutoff(now),
        )
        assertTrue(
            "the retention bound must outlive the grid slots it sits beside",
            T1dmRepository.RAW_SAMPLE_RETENTION_MS > GRID,
        )
    }

    private companion object {
        const val SOURCE = "src-a"
        const val GRID = T1dmRepository.GRID_MS

        /** On the grid: 1_700_000_100_000 % 300_000 == 0. */
        const val SLOT = 1_700_000_100_000L

        fun measured(
            tsMs: Long,
            rxWallMs: Long,
            flag: ReadingFlag = ReadingFlag.NORMAL,
        ) = CgmReadingEntity(
            sourceId = SOURCE,
            tsMs = tsMs,
            bgMgdl = 120,
            trendTenthsPerMin = 3,
            minFromStart = 400,
            quality = 1,
            provenance = ReadingProvenance.MEASURED,
            flag = flag,
            tzOffsetMin = 0,
            rxWallMs = rxWallMs,
            rssi = -70,
        )

        fun interpolated(tsMs: Long) = CgmReadingEntity(
            sourceId = SOURCE,
            tsMs = tsMs,
            bgMgdl = 118,
            trendTenthsPerMin = null,
            minFromStart = null,
            quality = null,
            provenance = ReadingProvenance.INTERPOLATED,
            flag = ReadingFlag.NORMAL,
            tzOffsetMin = 0,
            rxWallMs = tsMs,
            rssi = null,
        )

        /** What `upsertReading` now stores, slot by slot. */
        fun withContest(rows: List<CgmReadingEntity>): Map<Long, CgmReadingEntity> {
            val out = LinkedHashMap<Long, CgmReadingEntity>()
            for (r in rows) if (supersedesGridSlot(out[r.tsMs], r)) out[r.tsMs] = r
            return out
        }

        /** What `INSERT OR REPLACE` stored before it. */
        fun lastWriterWins(rows: List<CgmReadingEntity>): Map<Long, CgmReadingEntity> =
            rows.associateBy { it.tsMs }
    }
}
