package com.t1dm.watch.ble

import kotlinx.coroutines.flow.Flow

/**
 * All calls are off-main. A dropped link surfaces as [WatchCentralEvent.Disconnected]; the caller
 * drives reconnect and backoff.
 */
interface WatchCentral {

    /** Hot stream. */
    val events: Flow<WatchCentralEvent>

    /** Scan by [namePrefix] → connect → MTU → discover → subscribe CONTROL. [timeoutMs] bounds the
     *  whole bring-up. */
    suspend fun connectByName(namePrefix: String, timeoutMs: Long)

    /** Null when unavailable. */
    suspend fun readStatus(): ByteArray?

    /** dBm; null when unavailable or not connected. The default keeps non-Android fakes total. */
    suspend fun readRssi(): Int? = null

    /** Write-with-response. Throws on failure. */
    suspend fun writeKex(bytes: ByteArray)

    /** Write-without-response. Throws on failure. */
    suspend fun writePush(bytes: ByteArray)

    /** Safe to call repeatedly. */
    fun disconnect()

    /** True while a discovered, CONTROL-subscribed session is up. */
    val isReady: Boolean
}

sealed interface WatchCentralEvent {
    /** Connected, MTU negotiated, service discovered, CONTROL subscribed — ready for the handshake. */
    data class Ready(val deviceName: String, val mtu: Int) : WatchCentralEvent

    /** Raw CONTROL notification; decode with [com.t1dm.watch.proto.ControlFrame]. */
    data class Notified(val bytes: ByteArray) : WatchCentralEvent {
        override fun equals(other: Any?) = other is Notified && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    data class Disconnected(val reason: String) : WatchCentralEvent

    /** Bring-up failed before Ready. */
    data class Failed(val reason: String) : WatchCentralEvent
}
