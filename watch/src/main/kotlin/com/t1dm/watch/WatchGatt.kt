package com.t1dm.watch

import java.util.UUID

/**
 * The custom GATT surface the phone (BLE **central**) drives on the ESP32-C3 (GATT **peripheral**),
 * plus the discovery + link constants. This is the authoritative UUID map; `docs/WATCH_BLE.md`
 * restates it for the firmware side and the two MUST stay in lock-step.
 *
 * One primary service exposes four characteristics. The handshake and all control traffic ride
 * `KEX` (phone → watch writes) and `CONTROL` (watch → phone notifications); the 5-min glance rides
 * `PUSH` (phone → watch writes of sealed frames); `STATUS` is a read-only identity/epoch block the
 * central reads once on discovery to detect a reflash / epoch desync before trusting the session.
 *
 * Everything of substance is AES-128-GCM sealed at the app layer ([com.t1dm.watch.crypto.WatchSession]);
 * the LESC bond underneath is defence-in-depth only (watch-link.md).
 */
object WatchGatt {

    /** Advertised local-name prefix the central scans for (match by prefix, not by the LE random
     *  address, which rotates). Firmware advertises `T1DM-Watch-<id>`. */
    const val ADV_NAME_PREFIX = "T1DM-Watch"

    /** Target ATT MTU. Negotiated up from the 23-byte default so a full sealed PUSH (header + a
     *  ~40-byte glance + the 16-byte GCM tag) rides a single write; falls back gracefully. */
    const val MTU_TARGET = 247

    /** Protocol version carried in every frame header; a mismatch forces a re-pair. */
    const val PROTO_VERSION = 0x01

    // The custom 128-bit base; the service and its characteristics differ only in the first group.
    private fun uuid(short: String): UUID = UUID.fromString("$short-c0de-4a7c-9b0d-1d0a7a7c0f01")

    /** Primary custom service. */
    val SERVICE: UUID = uuid("7ed10000")

    /** Key-exchange / control-in (phone → watch, write-with-response): HELLO, CONFIRM, REKEY,
     *  RESET/UNPAIR handshake frames ([com.t1dm.watch.proto.KexFrame]). */
    val KEX: UUID = uuid("7ed10001")

    /** Control-out (watch → phone, notify): HELLO_ACK, CONFIRM_ACK, PUSH_ACK, and error frames
     *  (ERR_EPOCH / ERR_AUTH → force re-pair) ([com.t1dm.watch.proto.ControlFrame]). */
    val CONTROL: UUID = uuid("7ed10002")

    /** Push sink (phone → watch, write-without-response): sealed [com.t1dm.watch.proto.WatchPush]
     *  glance frames, one every 5 min. */
    val PUSH: UUID = uuid("7ed10003")

    /** Read-only identity block: `[u8 proto][u8 epoch][u8 flags][8B watch-id][…]` — read on
     *  discovery to detect a reflashed/epoch-desynced peripheral before any push. */
    val STATUS: UUID = uuid("7ed10004")

    /** Standard Client Characteristic Configuration Descriptor, to subscribe CONTROL notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
