package com.t1dm.data

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.CgmReadingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSlotSelectionTest {

    @Test
    fun `an empty slot takes whatever arrives`() {
        assertTrue(supersedesGridSlot(null, measured(SLOT, SLOT)))
        assertTrue(supersedesGridSlot(null, interpolated(SLOT)))
    }

    @Test
    fun `a five-minute sensor gets the same series as replace-in-place`() {
        val stream = ArrayList<CgmReadingEntity>()
        val jitter = longArrayOf(-2_000, 41_000, -66_000, 12_000, 140_000, -149_000)
        for (i in 0..5) {
            val slot = SLOT + i * GRID
            stream += measured(slot, slot + jitter[i])
        }
        stream += interpolated(SLOT + 6 * GRID)
        stream += interpolated(SLOT + 7 * GRID)
        stream += measured(SLOT + 8 * GRID, SLOT + 8 * GRID + 3_000)
        // The same advert delivered twice.
        stream += stream[3]
        // The one slot where warm-up and gap-fill contend; the contest declines to re-decide it.
        stream += measured(SLOT + 9 * GRID, SLOT + 9 * GRID + 1_000, flag = ReadingFlag.WARMUP)
        stream += interpolated(SLOT + 9 * GRID)

        assertEquals(lastWriterWins(stream), withContest(stream))
    }

    @Test
    fun `a three-minute sensor keeps the sample nearest each slot, whatever the arrival order`() {
        val stream = (0 until 5).map { i ->
            val sampledAt = SLOT + i * 180_000L
            measured(T1dmRepository.snapToGrid(sampledAt), sampledAt).copy(bgMgdl = 100 + i)
        }
        val expected = mapOf(
            SLOT to 100,             // 0 min, alone
            SLOT + GRID to 102,      // 6 min, 1 min out; beats the 3-min sample
            SLOT + 2 * GRID to 103,  // 9 min, 1 min out; beats the 12-min sample
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

    @Test
    fun `nearness is measured either side of the instant`() {
        val early = measured(SLOT, SLOT - 20_000)
        val late = measured(SLOT, SLOT + 130_000)
        assertFalse(supersedesGridSlot(early, late))
        assertTrue(supersedesGridSlot(late, early))
    }

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

    @Test
    fun `an interpolated row is left to replace in place`() {
        assertTrue(supersedesGridSlot(measured(SLOT, SLOT + 1_000), interpolated(SLOT)))
        assertTrue(supersedesGridSlot(interpolated(SLOT), measured(SLOT, SLOT + 140_000)))
    }

    @Test
    fun `the same receive instant replaces in place`() {
        val row = measured(SLOT, SLOT + 40_000)
        assertTrue(supersedesGridSlot(row, row.copy(bgMgdl = 999)))
        assertTrue(supersedesGridSlot(row, row))
    }

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

        /** What `upsertReading` stores, slot by slot. */
        fun withContest(rows: List<CgmReadingEntity>): Map<Long, CgmReadingEntity> {
            val out = LinkedHashMap<Long, CgmReadingEntity>()
            for (r in rows) if (supersedesGridSlot(out[r.tsMs], r)) out[r.tsMs] = r
            return out
        }

        /** What `INSERT OR REPLACE` stores. */
        fun lastWriterWins(rows: List<CgmReadingEntity>): Map<Long, CgmReadingEntity> =
            rows.associateBy { it.tsMs }
    }
}
