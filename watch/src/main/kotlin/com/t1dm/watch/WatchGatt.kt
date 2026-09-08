package com.t1dm.watch

import java.util.UUID

/** Authoritative UUID map; WATCH_BLE.md mirrors for firmware, lock-step; phone=central. */
object WatchGatt {

    /** Match by prefix: the LE random address rotates. Firmware advertises `T1DM-Watch-<id>`. */
    const val ADV_NAME_PREFIX = "T1DM-Watch"

    /** From 23B default so sealed PUSH (header+~40B glance+16B tag) fits one write; falls back. */
    const val MTU_TARGET = 247

    /** In every frame header; a mismatch forces a re-pair. */
    const val PROTO_VERSION = 0x01

    // The characteristics differ from the service only in the first group.
    private fun uuid(short: String): UUID = UUID.fromString("$short-c0de-4a7c-9b0d-1d0a7a7c0f01")

    val SERVICE: UUID = uuid("7ed10000")

    /** Phone → watch, write-with-response: HELLO, CONFIRM, REKEY, RESET/UNPAIR. */
    val KEX: UUID = uuid("7ed10001")

    /** Watch → phone, notify: acks and errors; ERR_EPOCH / ERR_AUTH force a re-pair. */
    val CONTROL: UUID = uuid("7ed10002")

    /** Phone → watch, write-without-response: sealed glance frames, one every 5 min. */
    val PUSH: UUID = uuid("7ed10003")

    /** Read-only identity: `[u8 proto][u8 epoch][u8 flags][8B watch-id]…`, read on discovery. */
    val STATUS: UUID = uuid("7ed10004")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
