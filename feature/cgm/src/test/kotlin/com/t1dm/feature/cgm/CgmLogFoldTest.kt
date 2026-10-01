package com.t1dm.feature.cgm

import com.t1dm.core.model.CgmLogEntry
import com.t1dm.core.model.CgmLogKind
import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CgmLogFoldTest {
    private val log = ArrayList<CgmLogEntry>()

    private fun add(
        atMs: Long,
        topic: CgmLogTopic,
        kind: CgmLogKind = CgmLogKind.LOG,
        level: CgmLogLevel = CgmLogLevel.I,
        opens: Boolean = false,
        value: Int? = null,
        text: String = "",
    ) {
        log += CgmLogEntry(atMs, atMs * 1_000_000, kind, level, topic, null, text, null, value, opens)
    }

    /** One CT5-style exchange: push in, its decode, the ack out, all within a second. */
    private fun push(atMs: Long, bg: Int, level: CgmLogLevel = CgmLogLevel.I) {
        add(atMs, CgmLogTopic.GLUCOSE, CgmLogKind.RX, opens = true)
        add(atMs + 5, CgmLogTopic.GLUCOSE, CgmLogKind.DEC, level, value = bg, text = "bg $bg")
        add(atMs + 100, CgmLogTopic.GLUCOSE, CgmLogKind.TX)
    }

    private fun rssi(atMs: Long, dbm: Int) =
        add(atMs, CgmLogTopic.RSSI, CgmLogKind.GATT, opens = true, value = dbm)

    @Test
    fun `routine exchanges fold per topic and the newest glucose stays open`() {
        for (i in 0 until 4) {
            push(i * 180_000L, 100 + i)
            for (k in 1..3) rssi(i * 180_000L + k * 15_000L, -70 - k)
        }
        val rows = foldCgmLog(log)
        val glucose = rows[0] as CgmLogRow.Fold
        assertEquals(CgmLogTopic.GLUCOSE, glucose.topic)
        assertEquals(3, glucose.units.size)
        assertEquals(9, glucose.lines)
        assertEquals(100, glucose.valueMin)
        assertEquals(102, glucose.valueMax)
        val rssi = rows[1] as CgmLogRow.Fold
        assertEquals(12, rssi.units.size)
        assertEquals(-73, rssi.valueMin)
        assertEquals(-71, rssi.valueMax)
        // The fourth push, shown line by line.
        assertEquals(listOf(18, 19, 20), rows.drop(2).map { (it as CgmLogRow.Line).index })
    }

    @Test
    fun `a line without a topic splits the folds around it`() {
        push(0, 100)
        push(180_000, 101)
        add(200_000, CgmLogTopic.NONE, text = "phase LIVE → FAILED")
        push(360_000, 102)
        push(540_000, 103)
        push(720_000, 104)
        val rows = foldCgmLog(log)
        assertTrue(rows[0] is CgmLogRow.Fold)
        assertEquals(6, (rows[1] as CgmLogRow.Line).index)
        val after = rows[2] as CgmLogRow.Fold
        assertEquals(2, after.units.size)
        assertEquals(listOf(13, 14, 15), rows.drop(3).map { (it as CgmLogRow.Line).index })
    }

    @Test
    fun `a warning shows its whole exchange and splits the fold`() {
        push(0, 100)
        push(180_000, 101)
        push(360_000, 55, level = CgmLogLevel.W)
        push(540_000, 103)
        push(720_000, 104)
        val rows = foldCgmLog(log)
        assertTrue(rows[0] is CgmLogRow.Fold)
        assertEquals(listOf(6, 7, 8), rows.subList(1, 4).map { (it as CgmLogRow.Line).index })
    }

    @Test
    fun `a lone exchange is not folded`() {
        push(0, 100)
        rssi(15_000, -70)
        add(20_000, CgmLogTopic.NONE)
        val rows = foldCgmLog(log)
        assertTrue(rows.all { it is CgmLogRow.Line })
        assertEquals(5, rows.size)
    }

    @Test
    fun `lines past the gap start a new exchange`() {
        add(0, CgmLogTopic.CLINICAL, CgmLogKind.RX)
        add(100, CgmLogTopic.CLINICAL, CgmLogKind.DEC)
        add(60_000, CgmLogTopic.CLINICAL, CgmLogKind.RX)
        add(60_050, CgmLogTopic.CLINICAL, CgmLogKind.DEC)
        val fold = foldCgmLog(log).single() as CgmLogRow.Fold
        assertEquals(2, fold.units.size)
        assertEquals(2, fold.units[1].size)
    }

    @Test
    fun `pages count back from the newest`() {
        assertEquals(1, cgmLogPageCount(0))
        assertEquals(3, cgmLogPageCount(1_100))
        assertEquals(600 until 1_100, cgmLogPage(1_100, 0))
        assertEquals(100 until 600, cgmLogPage(1_100, 1))
        assertEquals(0 until 100, cgmLogPage(1_100, 2))
    }

    @Test
    fun `deltas read monotonic within a boot and wall across one`() {
        val a = CgmLogEntry(1_000, 5_000_000_000, CgmLogKind.LOG, CgmLogLevel.I, CgmLogTopic.NONE, null, "", null, null, false)
        val b = CgmLogEntry(1_101, 5_100_000_000, CgmLogKind.LOG, CgmLogLevel.I, CgmLogTopic.NONE, null, "", null, null, false)
        val rebooted = CgmLogEntry(90_000, 1_000_000, CgmLogKind.LOG, CgmLogLevel.I, CgmLogTopic.NONE, null, "", null, null, false)
        assertEquals(100L, deltaMs(a, b))
        assertEquals(88_899L, deltaMs(b, rebooted))
        assertEquals("+0.100", deltaLabel(100))
        assertEquals("+1m28s", deltaLabel(88_899))
        assertEquals("35 01 FE", hexOf(byteArrayOf(0x35, 1, -2)))
    }
}
