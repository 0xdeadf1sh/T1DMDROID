package com.t1dm.watch.ble

import kotlinx.coroutines.flow.Flow

/** Which peripheral to connect to. */
sealed interface WatchTarget {
    /** A peripheral not yet paired: the name prefix, minus every paired name. */
    data class New(val prefix: String, val exclude: Set<String>) : WatchTarget

    /** A paired peripheral: [address] first, when known, then a scan for [name]. */
    data class Known(val name: String, val address: String?) : WatchTarget
}

/** [deviceName] as advertised; [mtu] the ATT MTU; [address] the one the link rode. */
data class WatchConnection(val deviceName: String, val mtu: Int, val address: String?)

/** One instance per link, all calls off-main. A dropped link surfaces as Disconnected. */
interface WatchCentral {

    /** Hot stream. */
    val events: Flow<WatchCentralEvent>

    /** Connect → MTU → discover → subscribe CONTROL; timeoutMs bounds it. Throws on failure. */
    suspend fun connect(target: WatchTarget, timeoutMs: Long): WatchConnection

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
    /** Raw CONTROL notification; decode with [com.t1dm.watch.proto.WatchCodec.control]. */
    data class Notified(val bytes: ByteArray) : WatchCentralEvent {
        override fun equals(other: Any?) = other is Notified && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    data class Disconnected(val reason: String) : WatchCentralEvent
}
