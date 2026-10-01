package com.t1dm.cgm

import android.util.Log
import com.t1dm.core.model.CgmLogTopic

/** §12.5: one recorded GATT PDU — monotonic ts, direction, char, raw bytes. Fixture material. */
class Libre3Pdu(
    val tsMs: Long,
    val direction: Direction,
    val charUuid: String,
    val bytes: ByteArray,
) {
    enum class Direction { WRITE, NOTIFY }

    override fun equals(other: Any?) = this === other || (other is Libre3Pdu &&
        tsMs == other.tsMs &&
        direction == other.direction &&
        charUuid == other.charUuid &&
        bytes.contentEquals(other.bytes))

    override fun hashCode(): Int {
        var h = tsMs.hashCode()
        h = 31 * h + direction.hashCode()
        h = 31 * h + charUuid.hashCode()
        h = 31 * h + bytes.contentHashCode()
        return h
    }

    override fun toString() = "$direction ${charUuid.take(UUID_PREFIX)}… ${bytes.size} B @ $tsMs"

    private companion object {
        const val UUID_PREFIX = 8
    }
}

/** §12.5: where recorded PDUs go; a test fixture is just a list of [Libre3Pdu]. */
fun interface Libre3PduSink {
    fun record(pdu: Libre3Pdu)
}

/**
 * PLAN_T1DMDROID.md §4/§5.1: the transport the pairing session drives. Fragments come
 * pre-framed from the machine (§5.2), so this layer carries bytes and paces them — nothing
 * here parses protocol content. Callbacks may fire on any thread.
 */
interface Libre3GattTransport {

    /**
     * `connectGatt(autoConnect=false)` to the sensor address, discover, then subscribe the 3
     * handshake chars (§5.1 handshakeNotifying order). [onReady] fires once all three CCCD
     * writes completed; [onGattError] fires on disconnect/CCCD failure and ends the session.
     */
    fun connect(onReady: () -> Unit, onGattError: (reason: String) -> Unit)

    /**
     * §5.10: writes on the security chars are serialized with `onCharacteristicWrite`
     * confirmation. [onDone] receives the confirmation — or `false` when the write could not
     * even be initiated; it is invoked exactly once, then the next chunk may go.
     */
    fun writeChunk(charUuid: String, chunk: ByteArray, onDone: (Boolean) -> Unit)

    /**
     * §5.1/§5.10: the post-handshake data plane, on the SAME gatt link the handshake ran on
     * (a fresh reconnect would re-run the whole VM handshake; §16 fallback, not a design
     * goal). Enables the seven data characteristics (§5.1 data set) as CCCD notifies in the
     * protocol.md order — [Libre3DataChar.entries] order — each `onDescriptorWrite` completing
     * before the next enable. [onReady] fires once all seven are armed; [onFail] fires when an
     * enable fails and stays the link's failure surface afterwards (the handshake session is
     * done by then): the stream turns it into SignalLost.
     */
    fun enableDataChars(onReady: () -> Unit, onFail: (String) -> Unit)

    /** Raw notify chunks, §5.2 seq-prefixed except secCommandResponse; [rx] as on AiDEX. */
    fun setNotifyListener(charUuid: String, listener: ((chunk: ByteArray, rx: Long?) -> Unit)?)

    /**
     * `BluetoothGatt.readRemoteRssi()`; `false` when there is no link to ask. The answer lands
     * on the [setRssiListener] listener (or nowhere, when the read failed) — the Ct5 transport
     * carries the same split.
     */
    fun readRemoteRssi(): Boolean

    /** RSSI reads land here; `null` detaches. May fire on any thread. */
    fun setRssiListener(listener: ((Int) -> Unit)?)

    fun close()
}

/** Name and log topic per characteristic uuid, lowercase. */
internal val LIBRE3_LOG_CHARS: Map<String, Pair<String, CgmLogTopic>> = buildMap {
    Libre3PairingChar.entries.forEach { put(it.uuid.lowercase(), it.name to CgmLogTopic.NONE) }
    Libre3DataChar.entries.forEach {
        val topic = when (it) {
            Libre3DataChar.GlucoseData -> CgmLogTopic.GLUCOSE
            Libre3DataChar.PatchStatus -> CgmLogTopic.STATUS
            Libre3DataChar.ClinicalData -> CgmLogTopic.CLINICAL
            Libre3DataChar.HistoricData, Libre3DataChar.PatchControl -> CgmLogTopic.HISTORY
            Libre3DataChar.EventLog, Libre3DataChar.FactoryData -> CgmLogTopic.NONE
        }
        put(it.uuid.lowercase(), it.name to topic)
    }
}

/** PDU capture shared by the transport; Log always, the sink when one is set. */
internal class Libre3PduRecorder(
    private val sink: Libre3PduSink?,
    private val debug: Boolean,
    private val nowMs: () -> Long,
    private val log: CgmSensorLog = CgmSensorLog.NONE,
) {

    fun write(charUuid: String, bytes: ByteArray) {
        capture(Libre3Pdu.Direction.WRITE, charUuid, bytes)
    }

    /** The log line's monoNs; null if unkept. */
    fun notify(charUuid: String, bytes: ByteArray): Long? = capture(Libre3Pdu.Direction.NOTIFY, charUuid, bytes)

    private fun capture(direction: Libre3Pdu.Direction, charUuid: String, bytes: ByteArray): Long? {
        val pdu = Libre3Pdu(tsMs = nowMs(), direction = direction, charUuid = charUuid, bytes = bytes.copyOf())
        if (debug) Log.d(TAG, "$pdu ${hex(pdu.bytes)}")
        sink?.record(pdu)
        val (name, topic) = LIBRE3_LOG_CHARS[charUuid.lowercase()] ?: (charUuid.take(8) to CgmLogTopic.NONE)
        return when (direction) {
            Libre3Pdu.Direction.WRITE -> {
                log.tx(name, pdu.bytes, topic)
                null
            }
            Libre3Pdu.Direction.NOTIFY -> log.rx(name, pdu.bytes, topic)
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "Libre3Pdu"
    }
}
