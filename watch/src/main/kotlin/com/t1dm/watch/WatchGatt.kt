package com.t1dm.watch

import java.util.UUID

/** SPEC/watch.md §1–§2; the UUIDs are pinned to `records_golden.json` by WatchGattGoldenTest. */
object WatchGatt {

    /** Match by prefix: the LE random address rotates. Peripherals advertise `T1DM-Watch-<id8>`. */
    const val ADV_NAME_PREFIX = "T1DM-Watch"

    /** One sealed record per write at this MTU; below it, extended records are not sent. */
    const val MTU_TARGET = 247

    // The characteristics differ from the service only in the first group.
    private fun uuid(short: String): UUID = UUID.fromString("$short-c0de-4a7c-9b0d-1d0a7a7c0f01")

    val SERVICE: UUID = uuid("7ed10000")

    /** Phone → peripheral, write-with-response: HELLO, CONFIRM. */
    val KEX: UUID = uuid("7ed10001")

    /** Peripheral → phone, notify: acks and errors. */
    val CONTROL: UUID = uuid("7ed10002")

    /** Phone → peripheral, write-without-response: one sealed record per write, opened in order. */
    val PUSH: UUID = uuid("7ed10003")

    /** Read-only: [u8 proto][u8 epoch][u8 flags][8B device_id], read on every connect. */
    val STATUS: UUID = uuid("7ed10004")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
