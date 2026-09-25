package com.t1dm.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.watch.WatchGatt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** CONNECTED session needs BLUETOOTH_CONNECT (CGM scan is passive); GATT callbacks hop off main. */
@SuppressLint("MissingPermission")
class AndroidWatchCentral(
    private val context: Context,
    private val dispatchers: T1dmDispatchers,
    private val scanner: WatchScanner,
) : WatchCentral {

    private val _events = MutableSharedFlow<WatchCentralEvent>(replay = 0, extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    private val opLock = Mutex()
    private var gatt: BluetoothGatt? = null

    // Single-flight completions resolved by the GATT callback.
    private var connectDone: CompletableDeferred<Boolean>? = null
    private var mtuDone: CompletableDeferred<Int>? = null
    private var discoverDone: CompletableDeferred<Boolean>? = null
    private var writeDone: CompletableDeferred<Boolean>? = null
    private var readDone: CompletableDeferred<ByteArray?>? = null
    private var cccdDone: CompletableDeferred<Boolean>? = null
    private var rssiDone: CompletableDeferred<Int?>? = null

    @Volatile
    override var isReady: Boolean = false
        private set

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override suspend fun connect(target: WatchTarget, timeoutMs: Long): WatchConnection =
        withContext(dispatchers.io) {
            withTimeout(timeoutMs) {
                // A direct connect needs no scan, so it still works while HyperOS suspends scans.
                val known = target as? WatchTarget.Known
                val direct = known?.address
                    ?.let { addr -> runCatching { adapter?.getRemoteDevice(addr) }.getOrNull() }
                    ?.let { withTimeoutOrNull(timeoutMs / 2) { bringUp(it, known.name) } }
                direct ?: run {
                    disconnect()
                    val found = scanner.find(target.matcher(), timeoutMs)
                    bringUp(found.device, found.name)
                        ?: throw IllegalStateException("GATT connection failed before bring-up")
                }
            }
        }

    private fun WatchTarget.matcher(): (String) -> Boolean = when (this) {
        is WatchTarget.Known -> { n -> n == name }
        is WatchTarget.New -> { n -> n.startsWith(prefix) && n !in exclude }
    }

    /** Null when the GATT connection itself fails; throws on a peer that is not a watch. */
    private suspend fun bringUp(device: BluetoothDevice, name: String): WatchConnection? {
        // Armed before connectGatt: requestMtu/discoverServices before STATE_CONNECTED are dropped.
        connectDone = CompletableDeferred()
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IllegalStateException("connectGatt returned null (adapter off?)")
        gatt = g
        if (!connectDone!!.await()) {
            disconnect()
            return null
        }

        mtuDone = CompletableDeferred()
        g.requestMtu(WatchGatt.MTU_TARGET)
        val mtu = mtuDone!!.await()

        discoverDone = CompletableDeferred()
        check(g.discoverServices()) { "discoverServices() rejected by the stack" }
        check(discoverDone!!.await()) { "service discovery failed" }

        val service = g.getService(WatchGatt.SERVICE)
            ?: throw IllegalStateException("watch service ${WatchGatt.SERVICE} not present")
        listOf(WatchGatt.KEX, WatchGatt.CONTROL, WatchGatt.PUSH, WatchGatt.STATUS).forEach {
            checkNotNull(service.getCharacteristic(it)) { "missing characteristic $it" }
        }
        subscribeControl(g, service.getCharacteristic(WatchGatt.CONTROL))

        isReady = true
        return WatchConnection(deviceName = name, mtu = mtu, address = device.address)
    }

    override suspend fun readStatus(): ByteArray? = opLock.withLock {
        val ch = gatt?.getService(WatchGatt.SERVICE)?.getCharacteristic(WatchGatt.STATUS) ?: return null
        readDone = CompletableDeferred()
        if (gatt?.readCharacteristic(ch) != true) return null
        withTimeout(OP_TIMEOUT_MS) { readDone!!.await() }
    }

    override suspend fun readRssi(): Int? = opLock.withLock {
        val g = gatt ?: return null
        if (!isReady) return null
        rssiDone = CompletableDeferred()
        if (!g.readRemoteRssi()) return null
        runCatching { withTimeout(OP_TIMEOUT_MS) { rssiDone!!.await() } }.getOrNull()
    }

    override suspend fun writeKex(bytes: ByteArray) = writeChar(WatchGatt.KEX, bytes, withResponse = true)

    override suspend fun writePush(bytes: ByteArray) = writeChar(WatchGatt.PUSH, bytes, withResponse = false)

    private suspend fun writeChar(uuid: java.util.UUID, bytes: ByteArray, withResponse: Boolean) =
        opLock.withLock {
            val g = gatt ?: throw IllegalStateException("no GATT connection")
            val ch = g.getService(WatchGatt.SERVICE)?.getCharacteristic(uuid)
                ?: throw IllegalStateException("characteristic $uuid not found")
            val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            writeDone = CompletableDeferred()
            val status = g.writeCharacteristic(ch, bytes, type)
            check(status == BluetoothGatt.GATT_SUCCESS) { "writeCharacteristic($uuid) rejected: $status" }
            check(withTimeout(OP_TIMEOUT_MS) { writeDone!!.await() }) { "write to $uuid failed" }
        }

    override fun disconnect() {
        isReady = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private suspend fun subscribeControl(g: BluetoothGatt, control: BluetoothGattCharacteristic) {
        check(g.setCharacteristicNotification(control, true)) { "enable CONTROL notify failed" }
        val cccd = control.getDescriptor(WatchGatt.CCCD)
            ?: throw IllegalStateException("CONTROL characteristic has no CCCD")
        cccdDone = CompletableDeferred()
        val status = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        check(status == BluetoothGatt.GATT_SUCCESS) { "CCCD write rejected: $status" }
        check(withTimeout(OP_TIMEOUT_MS) { cccdDone!!.await() }) { "CCCD write failed" }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // A closed attempt's late callback must not fail or drop the live one.
            if (gatt != null && g != gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasReady = isReady
                isReady = false
                connectDone?.complete(false)
                if (wasReady) emit(WatchCentralEvent.Disconnected("GATT disconnected (status=$status)"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuDone?.complete(mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discoverDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            cccdDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            readDone?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ch.uuid == WatchGatt.CONTROL) emit(WatchCentralEvent.Notified(value.copyOf()))
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            rssiDone?.complete(if (status == BluetoothGatt.GATT_SUCCESS) rssi else null)
        }
    }

    private fun emit(event: WatchCentralEvent) {
        if (!_events.tryEmit(event)) Timber.tag(TAG).w("dropped watch central event (buffer full): %s", event)
    }

    companion object {
        private const val TAG = "WatchLink"
        private const val OP_TIMEOUT_MS = 10_000L
    }
}
