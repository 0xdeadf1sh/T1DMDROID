package com.t1dm.cgm

import kotlinx.coroutines.flow.Flow

/** CGM.md §4.1. */
enum class AidexChar { F001, F002, F003 }

/** Emitted on the binder thread, consumed on one coroutine serially. Arrays compare by identity. */
sealed interface AidexGattEvent {
    data class Connection(val connected: Boolean, val statusOk: Boolean, val statusCode: Int) : AidexGattEvent

    /** [hasCgmService]: 0x181F and all of F001/F002/F003 were found. */
    data class ServicesDiscovered(val ok: Boolean, val hasCgmService: Boolean) : AidexGattEvent

    /** F001 masterkey, F002 response, F003 realtime. [rx]: the monoNs `rx` gave its log line. */
    data class Notify(val char: AidexChar, val value: ByteArray, val rx: Long? = null) : AidexGattEvent

    data class Read(val char: AidexChar, val value: ByteArray, val ok: Boolean, val rx: Long? = null) : AidexGattEvent

    data class Write(val char: AidexChar, val ok: Boolean) : AidexGattEvent

    data class NotifyEnabled(val char: AidexChar, val ok: Boolean) : AidexGattEvent

    data class Rssi(val dbm: Int, val ok: Boolean) : AidexGattEvent

    /** Unrecoverable. */
    data class Failure(val reason: String) : AidexGattEvent
}

/** CGM.md §4: no createBond/MTU nego; false = can't-initiate, real outcome async on [events]. */
interface AidexGattTransport {
    /** Impls must replay, or a late subscriber after [connect] misses `Connection`. */
    val events: Flow<AidexGattEvent>

    fun connect()

    fun discoverServices(): Boolean
    fun setNotify(char: AidexChar, enable: Boolean): Boolean
    fun write(char: AidexChar, value: ByteArray, withResponse: Boolean): Boolean
    fun read(char: AidexChar): Boolean

    /** Read-only; never touches the read path. The value arrives as [AidexGattEvent.Rssi]. */
    fun readRemoteRssi(): Boolean

    fun close()
}
