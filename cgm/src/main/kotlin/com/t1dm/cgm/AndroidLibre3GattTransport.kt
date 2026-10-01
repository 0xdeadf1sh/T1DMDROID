package com.t1dm.cgm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.SystemClock
import com.t1dm.core.ble.GattCallbackCompat
import com.t1dm.core.ble.writeCharacteristicCompat
import com.t1dm.core.ble.writeDescriptorCompat
import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import java.util.UUID

/** §5.1: the security service the handshake runs on (base `0898xxxx-EF89-11E9-81B4-2A2AE2DBCCE4`). */
object Libre3Gatt {
    val SECURITY_SERVICE_UUID: UUID = UUID.fromString("0898203A-EF89-11E9-81B4-2A2AE2DBCCE4")

    /** §5.1: the primary data service (also the scan filter); the data chars live on it. */
    val DATA_SERVICE_UUID: UUID = UUID.fromString("089810CC-EF89-11E9-81B4-2A2AE2DBCCE4")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** §5.1 handshakeNotifying order: cert, then challenge, then command clock. */
    val HANDSHAKE_ORDER: List<Libre3PairingChar> = listOf(
        Libre3PairingChar.SecCertData,
        Libre3PairingChar.SecChallengeData,
        Libre3PairingChar.SecCommandResponse,
    )

    /**
     * §5.1: the post-handshake subscribe set and order (protocol.md PoC). [Libre3DataChar]
     * entries are declared in that order, so the enum IS the order.
     */
    val DATA_ORDER: List<Libre3DataChar> = Libre3DataChar.entries
}

/**
 * PLAN_T1DMDROID.md §5.10 Android notes, kept literally: the default ATT MTU (never
 * `requestMtu` — §5.2's 18/19-byte chunking is fixed by the protocol), each CCCD write must
 * complete (`onDescriptorWrite`) before the next enable, and writes serialize with
 * `onCharacteristicWrite` confirmation. No bonding; `connectGatt` is the whole link.
 */
@SuppressLint("MissingPermission")
class AndroidLibre3GattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    /** §12.5: dependency-injected; `null` = Log only (under the debug flag). */
    private val pduSink: Libre3PduSink? = null,
    private val pduDebug: Boolean = false,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val log: CgmSensorLog = CgmSensorLog.NONE,
) : Libre3GattTransport {

    private val recorder = Libre3PduRecorder(pduSink, pduDebug, nowMs, log)

    @Volatile private var gatt: BluetoothGatt? = null

    /** By lowercase UUID; filled by discovery, used by writes and notifies. */
    private val chars = HashMap<String, BluetoothGattCharacteristic>()

    private val listeners = HashMap<String, (ByteArray, Long?) -> Unit>()

    /** RSSI read results; set by the source before the link exists. */
    private var rssiListener: ((Int) -> Unit)? = null

    /** CCCD enables pending; consumed one at a time, each after the previous acked. */
    private val subscribeQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private var onReady: (() -> Unit)? = null
    private var onGattError: ((String) -> Unit)? = null

    /** The §5.1 data phase (enableDataChars): its own ready/fail pair, pending once asked. */
    private var dataOnReady: (() -> Unit)? = null
    private var dataOnFail: ((String) -> Unit)? = null

    /** The one write in flight; the session's discipline keeps it single. */
    private var writeDone: ((Boolean) -> Unit)? = null

    override fun connect(onReady: () -> Unit, onGattError: (reason: String) -> Unit) {
        this.onReady = onReady
        this.onGattError = onGattError
        log.gatt(TAG, "connectGatt(autoConnect=false, TRANSPORT_LE) → ${device.address}")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (g == null) {
            log.w(TAG, "connectGatt returned null")
            onGattError("connectGatt returned null")
        } else {
            gatt = g
        }
    }

    @Synchronized
    override fun writeChunk(charUuid: String, chunk: ByteArray, onDone: (Boolean) -> Unit) {
        val g = gatt
        val c = chars[charUuid.lowercase()]
        if (g == null || c == null) {
            log.w(TAG, "write to $charUuid refused — no link or characteristic")
            onDone(false)
            return
        }
        recorder.write(charUuid, chunk)
        // With response: the ack is the §5.10 pacing beat. Default MTU kept (§5.10).
        val status = g.writeCharacteristicCompat(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (status != BluetoothStatusCodes.SUCCESS) {
            log.w(TAG, "write to $charUuid rejected by the stack ($status)")
            onDone(false)
            return
        }
        writeDone = onDone
    }

    override fun setNotifyListener(charUuid: String, listener: ((ByteArray, Long?) -> Unit)?) {
        synchronized(listeners) {
            if (listener == null) listeners.remove(charUuid.lowercase()) else listeners[charUuid.lowercase()] = listener
        }
    }

    override fun readRemoteRssi(): Boolean = gatt?.readRemoteRssi() ?: false

    override fun setRssiListener(listener: ((Int) -> Unit)?) {
        rssiListener = listener
    }

    /**
     * §5.1/§5.10: the seven data characteristics off the primary data service on the SAME gatt
     * (no re-discovery — `getService` after the handshake discovery), CCCD-enabled in
     * [Libre3DataChar] order, each acked before the next. The found chars join [chars] so a
     * later `writeChunk` to patchControl (phase-5 backfill request) resolves.
     */
    @Synchronized
    override fun enableDataChars(onReady: () -> Unit, onFail: (String) -> Unit) {
        val g = gatt
        if (g == null) {
            log.w(TAG, "enableDataChars refused — no link")
            onFail("no link for the data characteristics")
            return
        }
        val svc = g.getService(Libre3Gatt.DATA_SERVICE_UUID)
        if (svc == null) {
            onFail("the primary data service (§5.1) is absent")
            return
        }
        synchronized(chars) {
            for (name in Libre3Gatt.DATA_ORDER) {
                val c = svc.getCharacteristic(UUID.fromString(name.uuid))
                if (c == null) {
                    log.w(TAG, "characteristic ${name.uuid} is absent")
                    onFail("characteristic ${name.uuid} is absent")
                    return
                }
                chars[name.uuid] = c
            }
        }
        // §5.1 data order; each enable waits for its onDescriptorWrite before the next.
        synchronized(subscribeQueue) {
            subscribeQueue.clear()
            for (name in Libre3Gatt.DATA_ORDER) chars[name.uuid]?.let { subscribeQueue.addLast(it) }
        }
        dataOnReady = onReady
        dataOnFail = onFail
        pumpSubscribe()
    }

    override fun close() {
        val g = gatt
        if (g != null) log.gatt(TAG, "close: disconnect + close")
        gatt = null
        synchronized(chars) { chars.clear() }
        synchronized(listeners) { listeners.clear() }
        synchronized(subscribeQueue) { subscribeQueue.clear() }
        val pendingWrite = synchronized(this) { val d = writeDone; writeDone = null; d }
        synchronized(this) {
            dataOnReady = null
            dataOnFail = null
        }
        g?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        // A write parked on a dead link must not wait out the session's clock.
        pendingWrite?.invoke(false)
        onReady = null
        onGattError = null
        rssiListener = null
    }

    private fun fail(reason: String) {
        log.w(TAG, "gatt error: $reason")
        // The data phase owns failures once asked for: its onFail serves the enable sequence
        // and, once armed, every later link death (the pairing session is gone by then).
        val dataFail = synchronized(this) { dataOnFail }
        if (dataFail != null) {
            synchronized(this) { dataOnReady = null }
            dataFail(reason)
            return
        }
        val cb = onGattError ?: return
        onGattError = null
        onReady = null
        cb(reason)
    }

    /** Next CCCD enable of the round in flight, or the round's onReady once fully armed. */
    private fun pumpSubscribe() {
        val g = gatt ?: return
        val next = synchronized(subscribeQueue) { subscribeQueue.removeFirstOrNull() }
        if (next == null) {
            val dataReady = synchronized(this) { dataOnReady }
            if (dataReady != null) {
                log.gatt(TAG, "data characteristics armed (§5.1 order)")
                synchronized(this) { dataOnReady = null }
                dataReady()
                return
            }
            log.gatt(TAG, "handshake characteristics armed (§5.1 order)")
            onReady?.invoke()
            return
        }
        // NOTIFY semantics; the descriptor write completing is what arms it (§5.10).
        if (!g.setCharacteristicNotification(next, true)) {
            fail("setCharacteristicNotification rejected for ${next.uuid}")
            return
        }
        val cccd = next.getDescriptor(Libre3Gatt.CCCD_UUID)
        if (cccd == null || g.writeDescriptorCompat(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) !=
            BluetoothStatusCodes.SUCCESS
        ) {
            fail("CCCD write for ${next.uuid} could not be initiated")
        }
    }

    private val callback = object : GattCallbackCompat() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log.gatt(TAG, "connected (status=$status); discovering services")
                    if (!g.discoverServices()) fail("discoverServices() rejected")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    log.gatt(TAG, "disconnected (status=$status)")
                    fail("disconnected (status=$status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("service discovery failed (status=$status)")
                return
            }
            val svc = g.getService(Libre3Gatt.SECURITY_SERVICE_UUID)
            if (svc == null) {
                fail("the security service (§5.1) is absent")
                return
            }
            synchronized(chars) { chars.clear() }
            for (name in Libre3Gatt.HANDSHAKE_ORDER) {
                val c = svc.getCharacteristic(UUID.fromString(name.uuid))
                if (c == null) {
                    fail("characteristic ${name.uuid} is absent")
                    return
                }
                synchronized(chars) { chars[name.uuid] = c }
            }
            // §5.1 handshakeNotifying order; each enable waits for its onDescriptorWrite.
            synchronized(subscribeQueue) {
                for (name in Libre3Gatt.HANDSHAKE_ORDER) chars[name.uuid]?.let { subscribeQueue.addLast(it) }
            }
            pumpSubscribe()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            log.ack(TAG, "onCharacteristicWrite ${charName(characteristic.uuid.toString())} status=$status", ok)
            val done = synchronized(this@AndroidLibre3GattTransport) {
                val d = writeDone
                writeDone = null
                d
            }
            done?.invoke(ok)
        }

        override fun onNotify(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val uuid = characteristic.uuid.toString()
            val rx = recorder.notify(uuid, value)
            val listener = synchronized(listeners) { listeners[uuid.lowercase()] }
            listener?.invoke(value.copyOf(), rx)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != Libre3Gatt.CCCD_UUID) return
            log.gatt(TAG, "CCCD ${charName(descriptor.characteristic.uuid.toString())} armed status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("CCCD write failed (status=$status)")
                return
            }
            pumpSubscribe()
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            log.gatt(
                tag = if (ok) null else TAG,
                text = if (ok) "rssi $rssi dBm" else "readRemoteRssi failed (status=$status)",
                level = if (ok) CgmLogLevel.I else CgmLogLevel.W,
                topic = CgmLogTopic.RSSI,
                value = if (ok) rssi else null,
                opens = true,
            )
            if (ok) rssiListener?.invoke(rssi)
        }
    }

    private fun charName(uuid: String): String = LIBRE3_LOG_CHARS[uuid.lowercase()]?.first ?: uuid.take(8)

    private companion object {
        const val TAG = "Libre3Connect"
    }
}
