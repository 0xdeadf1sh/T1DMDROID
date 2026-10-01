package com.t1dm.cgm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/** CGM.md §4, no protocol logic; no createBond, no MTU negotiation. Callbacks via tryEmit. */
@SuppressLint("MissingPermission")
class AndroidAidexGattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: CgmSensorLog = CgmSensorLog.NONE,
) : AidexGattTransport {

    private val _events = MutableSharedFlow<AidexGattEvent>(replay = 16, extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    private val chars = HashMap<AidexChar, BluetoothGattCharacteristic>()

    override fun connect() {
        log.gatt(TAG, "connectGatt(autoConnect=false, TRANSPORT_LE) → ${device.address}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            log.gatt(TAG, "connectGatt returned null", CgmLogLevel.E)
            _events.tryEmit(AidexGattEvent.Failure("connectGatt returned null"))
        }
    }

    override fun discoverServices(): Boolean =
        (gatt?.discoverServices() ?: false).also { log.gatt(TAG, "discoverServices() → $it") }

    override fun setNotify(char: AidexChar, enable: Boolean): Boolean {
        val g = gatt ?: return false
        val c = chars[char] ?: return false
        if (!g.setCharacteristicNotification(c, enable)) return false
        val cccd = c.getDescriptor(CCCD) ?: return false
        val value = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        val status = g.writeDescriptor(cccd, value)
        log.gatt(TAG, "CCCD $char notify=$enable → status $status")
        return status == BluetoothStatusCodes.SUCCESS
    }

    override fun write(char: AidexChar, value: ByteArray, withResponse: Boolean): Boolean {
        val g = gatt ?: return false
        val c = chars[char] ?: return false
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val status = g.writeCharacteristic(c, value, type)
        if (status != BluetoothStatusCodes.SUCCESS) log.gatt(TAG, "write $char → status $status", CgmLogLevel.W)
        return status == BluetoothStatusCodes.SUCCESS
    }

    override fun read(char: AidexChar): Boolean {
        val g = gatt ?: return false
        val c = chars[char] ?: return false
        return g.readCharacteristic(c).also { log.gatt(TAG, "read $char → $it") }
    }

    override fun readRemoteRssi(): Boolean = (gatt?.readRemoteRssi() ?: false)

    override fun close() {
        log.gatt(TAG, "close: disconnect + close")
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        chars.clear()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            log.gatt(
                TAG,
                "onConnectionStateChange status=$status newState=$newState (connected=$connected)",
                if (connected && status == BluetoothGatt.GATT_SUCCESS) CgmLogLevel.I else CgmLogLevel.W,
            )
            _events.tryEmit(
                AidexGattEvent.Connection(connected, status == BluetoothGatt.GATT_SUCCESS, status),
            )
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            chars.clear()
            val svc = g.getService(CGM_SERVICE)
            if (svc != null) {
                CHAR_UUIDS.forEach { (ch, uuid) -> svc.getCharacteristic(uuid)?.let { chars[ch] = it } }
            }
            val hasAll = svc != null && chars.keys.containsAll(AidexChar.entries.toList())
            log.gatt(
                TAG,
                "onServicesDiscovered status=$status cgm=${svc != null} chars=${chars.keys} " +
                    "services=${g.services.joinToString { it.uuid.toString().take(8) }}",
            )
            _events.tryEmit(
                AidexGattEvent.ServicesDiscovered(status == BluetoothGatt.GATT_SUCCESS, hasAll),
            )
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val char = charOf(characteristic.uuid)
            if (char == null) {
                log.rx(characteristic.uuid.toString().take(8), value, text = "notify on an unexpected characteristic")
                return
            }
            // F001 carries the masterkey; F002 answers the last write; F003 is the realtime push.
            val rx = log.rx(
                channel = char.name,
                bytes = value,
                topic = when (char) {
                    AidexChar.F001 -> CgmLogTopic.NONE
                    AidexChar.F002 -> log.lastTxTopic
                    AidexChar.F003 -> CgmLogTopic.GLUCOSE
                },
                opens = char == AidexChar.F003,
                secret = char == AidexChar.F001,
            )
            _events.tryEmit(AidexGattEvent.Notify(char, value.copyOf(), rx))
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            charOf(characteristic.uuid)?.let {
                // The one read is F002's session blob: key-derivation input.
                val rx = log.rx("$it read", value, text = "status=$status", secret = it == AidexChar.F002)
                _events.tryEmit(AidexGattEvent.Read(it, value.copyOf(), status == BluetoothGatt.GATT_SUCCESS, rx))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            charOf(characteristic.uuid)?.let {
                log.ack(TAG, "onCharacteristicWrite $it status=$status", status == BluetoothGatt.GATT_SUCCESS)
                _events.tryEmit(AidexGattEvent.Write(it, status == BluetoothGatt.GATT_SUCCESS))
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            charOf(descriptor.characteristic.uuid)?.let {
                log.gatt(TAG, "onDescriptorWrite $it CCCD status=$status")
                _events.tryEmit(AidexGattEvent.NotifyEnabled(it, status == BluetoothGatt.GATT_SUCCESS))
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
            _events.tryEmit(AidexGattEvent.Rssi(rssi, ok))
        }
    }

    private fun charOf(uuid: UUID): AidexChar? = UUID_CHARS[uuid]

    private companion object {
        const val TAG = "AidexConnect"

        /** 16-bit UUIDs expanded against the Bluetooth Base UUID (CGM.md §4.1). */
        fun uuid16(hex: String): UUID = UUID.fromString("0000$hex-0000-1000-8000-00805F9B34FB")

        val CGM_SERVICE: UUID = uuid16("181F")
        val CCCD: UUID = uuid16("2902")

        val CHAR_UUIDS: Map<AidexChar, UUID> = mapOf(
            AidexChar.F001 to uuid16("F001"),
            AidexChar.F002 to uuid16("F002"),
            AidexChar.F003 to uuid16("F003"),
        )
        val UUID_CHARS: Map<UUID, AidexChar> = CHAR_UUIDS.entries.associate { (k, v) -> v to k }
    }
}
