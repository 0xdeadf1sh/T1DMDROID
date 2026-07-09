package com.t1dm.watch.ble

import kotlinx.coroutines.flow.Flow

/**
 * The phone-side BLE **central** transport to the watch peripheral, abstracted so [com.t1dm.watch.WatchLink]
 * (and its tests) never touch `android.bluetooth` directly and the module stays host-testable. The
 * Android implementation is [AndroidWatchCentral]; a fake drives the unit tests / emulator harness.
 *
 * Contract: scan-by-name → connect → negotiate MTU → discover the custom service + characteristics →
 * subscribe CONTROL notifications. All calls are suspend/off-main and idempotent where noted; a
 * dropped link surfaces as [WatchCentralEvent.Disconnected] and the caller drives reconnect/backoff.
 */
interface WatchCentral {

    /** A hot stream of transport events (connection lifecycle + inbound CONTROL notifications). */
    val events: Flow<WatchCentralEvent>

    /**
     * Scan for a peripheral whose advertised local name starts with [namePrefix], connect, negotiate
     * the MTU, discover the service, and subscribe CONTROL. Emits [WatchCentralEvent.Ready] on
     * success (or [WatchCentralEvent.Failed]). [timeoutMs] bounds the whole bring-up.
     */
    suspend fun connectByName(namePrefix: String, timeoutMs: Long)

    /** Read the STATUS identity block (epoch/proto/id), or null if unavailable. */
    suspend fun readStatus(): ByteArray?

    /** Read the connected peripheral's RSSI in dBm (Phase 7C — watch signal strength), or null if
     *  unavailable / not connected. Default null keeps non-Android fakes total. */
    suspend fun readRssi(): Int? = null

    /** Write [bytes] to the KEX characteristic (write-with-response). Throws on failure. */
    suspend fun writeKex(bytes: ByteArray)

    /** Write a sealed [bytes] frame to the PUSH characteristic (write-without-response). Throws on failure. */
    suspend fun writePush(bytes: ByteArray)

    /** Tear down the GATT connection and release the client; safe to call repeatedly. */
    fun disconnect()

    /** True while a discovered, CONTROL-subscribed session is up. */
    val isReady: Boolean
}

/** Transport events the central emits upward. */
sealed interface WatchCentralEvent {
    /** Connected, MTU negotiated, service discovered, CONTROL subscribed — ready for the handshake. */
    data class Ready(val deviceName: String, val mtu: Int) : WatchCentralEvent

    /** A CONTROL characteristic notification arrived (raw bytes; decode with [com.t1dm.watch.proto.ControlFrame]). */
    data class Notified(val bytes: ByteArray) : WatchCentralEvent {
        override fun equals(other: Any?) = other is Notified && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    /** The link dropped (reason is a plain-language string for the panel + logs). */
    data class Disconnected(val reason: String) : WatchCentralEvent

    /** Bring-up failed before Ready (no peripheral found, discovery failed, GATT error). */
    data class Failed(val reason: String) : WatchCentralEvent
}
