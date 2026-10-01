package com.t1dm.cgm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import com.t1dm.core.ble.GattCallbackCompat
import com.t1dm.core.ble.writeCharacteristicCompat
import com.t1dm.core.ble.writeDescriptorCompat
import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** No bonding; connectGatt is the whole link. MTU negotiated here (K's 22B reply needs it). */
@SuppressLint("MissingPermission")
class AndroidCt5GattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    scope: CoroutineScope,
    private val log: CgmSensorLog = CgmSensorLog.NONE,
) : Ct5GattTransport {

    private val _events = MutableSharedFlow<Ct5GattEvent>(replay = 16, extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    private val chars = HashMap<Ct5Char, BluetoothGattCharacteristic>()

    /** The only writer: Android permits one outstanding GATT operation at a time. */
    private val pacer = Ct5WritePacer(
        scope = scope,
        write = ::writeNow,
        onExhausted = { opcode -> _events.tryEmit(Ct5GattEvent.LadderExhausted(opcode)) },
        onWriteRejected = { opcode ->
            _events.tryEmit(Ct5GattEvent.Failure("write of 0x${opcode.toString(16)} rejected"))
        },
        log = log,
    )

    override fun connect() {
        log.gatt(TAG, "connectGatt(autoConnect=false, TRANSPORT_LE) → ${device.address}")
        pacer.start()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            log.gatt(TAG, "connectGatt returned null", CgmLogLevel.E)
            _events.tryEmit(Ct5GattEvent.Failure("connectGatt returned null"))
        }
    }

    override fun requestMtu(mtu: Int): Boolean =
        (gatt?.requestMtu(mtu) ?: false).also { log.gatt(TAG, "requestMtu($mtu) → $it") }

    override fun discoverServices(): Boolean =
        (gatt?.discoverServices() ?: false).also { log.gatt(TAG, "discoverServices() → $it") }

    override fun setNotify(enable: Boolean): Boolean {
        val g = gatt ?: return false
        val c = chars[Ct5Char.NOTIFY] ?: return false
        if (!g.setCharacteristicNotification(c, enable)) return false
        val cccd = c.getDescriptor(Ct5Constants.CCCD_UUID) ?: return false
        // NOTIFY, never indicate: an indication CCCD value would arm nothing here.
        val value = if (enable) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        val status = g.writeDescriptorCompat(cccd, value)
        log.gatt(TAG, "CCCD 0x1001 notify=$enable → status $status")
        return status == BluetoothStatusCodes.SUCCESS
    }

    override fun sendPaced(frame: ByteArray, ladder: LongArray, expectReply: Boolean) =
        pacer.enqueue(frame, ladder, expectReply)

    override fun acknowledge() = pacer.acknowledge()

    override fun readRemoteRssi(): Boolean = gatt?.readRemoteRssi() ?: false

    override fun close() {
        log.gatt(TAG, "close: disconnect + close")
        pacer.close()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        chars.clear()
    }

    /** Called ONLY by the pacer; the characteristic supports only WRITE_TYPE_NO_RESPONSE. */
    private fun writeNow(value: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = chars[Ct5Char.WRITE] ?: return false
        val status = g.writeCharacteristicCompat(c, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        if (status != BluetoothStatusCodes.SUCCESS) log.gatt(TAG, "writeCharacteristic → status $status", CgmLogLevel.W)
        return status == BluetoothStatusCodes.SUCCESS
    }

    private val callback = object : GattCallbackCompat() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            log.gatt(
                TAG,
                "onConnectionStateChange status=$status newState=$newState (connected=$connected)",
                if (connected && status == BluetoothGatt.GATT_SUCCESS) CgmLogLevel.I else CgmLogLevel.W,
            )
            _events.tryEmit(Ct5GattEvent.Connection(connected, status == BluetoothGatt.GATT_SUCCESS, status))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log.gatt(TAG, "onMtuChanged mtu=$mtu status=$status")
            _events.tryEmit(Ct5GattEvent.MtuChanged(mtu, status == BluetoothGatt.GATT_SUCCESS))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            chars.clear()
            val svc = g.getService(Ct5Constants.SERVICE_UUID)
            if (svc != null) {
                svc.getCharacteristic(Ct5Constants.WRITE_UUID)?.let { chars[Ct5Char.WRITE] = it }
                svc.getCharacteristic(Ct5Constants.NOTIFY_UUID)?.let { chars[Ct5Char.NOTIFY] = it }
            }
            val hasAll = svc != null && chars.keys.containsAll(Ct5Char.entries.toList())
            log.gatt(
                TAG,
                "onServicesDiscovered status=$status svc=${svc != null} chars=${chars.keys} " +
                    "services=${g.services.joinToString { it.uuid.toString().take(8) }}",
            )
            _events.tryEmit(
                Ct5GattEvent.ServicesDiscovered(status == BluetoothGatt.GATT_SUCCESS, hasAll),
            )
        }

        override fun onNotify(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == Ct5Constants.NOTIFY_UUID) {
                val opcode = if (value.isEmpty()) -1 else value[0].toInt() and 0xFF
                val topic = ct5LogTopic(opcode)
                val rx = log.rx(
                    channel = "0x1001",
                    bytes = value,
                    topic = topic,
                    text = ct5OpName(opcode),
                    opens = topic == CgmLogTopic.GLUCOSE,
                    secret = ct5Withheld(opcode),
                )
                _events.tryEmit(Ct5GattEvent.Notify(value.copyOf(), rx))
            } else {
                log.rx(characteristic.uuid.toString().take(8), value, text = "notify on an unexpected characteristic")
            }
        }

        /** Write-without-response: bytes queued only; no Ct5GattEvent confirms the peer got it. */
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            log.ack(TAG, "onCharacteristicWrite status=$status (queued locally)", status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log.gatt(TAG, "onDescriptorWrite ${descriptor.uuid.toString().take(8)} status=$status")
            if (descriptor.uuid == Ct5Constants.CCCD_UUID) {
                _events.tryEmit(Ct5GattEvent.NotifyEnabled(status == BluetoothGatt.GATT_SUCCESS))
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            log.gatt(
                tag = null,
                text = if (ok) "rssi $rssi dBm" else "rssi read failed status=$status",
                level = if (ok) CgmLogLevel.I else CgmLogLevel.W,
                topic = CgmLogTopic.RSSI,
                value = if (ok) rssi else null,
                opens = true,
            )
            _events.tryEmit(Ct5GattEvent.Rssi(rssi, ok))
        }
    }

    private companion object {
        const val TAG = "Ct5Connect"
    }
}
