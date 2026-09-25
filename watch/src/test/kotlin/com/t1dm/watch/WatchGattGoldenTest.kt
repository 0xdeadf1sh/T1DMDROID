package com.t1dm.watch

import org.junit.Assert.assertEquals
import org.junit.Test

/** [WatchGatt] against the UUIDs the Rust crate and T1DMKDE are pinned to. */
class WatchGattGoldenTest {

    private val golden: String =
        requireNotNull(javaClass.getResource("/records_golden.json")) { "records_golden.json not on the test classpath" }
            .readText()

    @Test fun `UUIDs match the shared vectors`() {
        val body = Regex("\"gatt\"\\s*:\\s*\\[([^\\]]*)]").find(golden)!!.groupValues[1]
        val want = Regex("\"([0-9a-f-]+)\"").findAll(body).map { it.groupValues[1] }.toList()
        val have = listOf(WatchGatt.SERVICE, WatchGatt.KEX, WatchGatt.CONTROL, WatchGatt.PUSH, WatchGatt.STATUS)
        assertEquals(want, have.map { it.toString() })
    }
}
