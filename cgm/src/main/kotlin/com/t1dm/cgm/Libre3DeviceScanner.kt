package com.t1dm.cgm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.t1dm.core.common.T1dmDispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** Identity is the BLE address until provisioning mints a serial; the advert carries no glucose. */
data class Libre3AdvertisedDevice(
    val device: BluetoothDevice,
    val address: String,
    val rssi: Int,
    /** Wall-clock time the CONTROLLER heard it, not delivery; batched sighting can lag minutes. */
    val heardAtMs: Long,
    /** The scan record as heard, for the sensor's log. */
    val raw: ByteArray? = null,
)

/** One service-uuid filter says it all; nothing else in the advert is read. Needs BLUETOOTH_SCAN. */
class Libre3DeviceScanner(
    private val scanner: BluetoothLeScanner?,
    private val dispatchers: T1dmDispatchers,
    /** Read once per window, not watched — restarting mid-window loses the controller's buffer. */
    private val screenOn: () -> Boolean = { true },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    @SuppressLint("MissingPermission")
    fun discover(): Flow<Libre3AdvertisedDevice> = callbackFlow {
        val ble = scanner ?: run {
            close(IllegalStateException("BluetoothLeScanner unavailable (adapter off or no BLE)"))
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                recognize(result)?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) recognize(r)?.let { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Libre 3 discovery scan failed: $errorCode")
                close(IllegalStateException("Libre 3 discovery scan failed: $errorCode"))
            }
        }

        ble.startScan(scanFilters(), scanSettings(screenOn()), callback)
        awaitClose { runCatching { ble.stopScan(callback) } }
    }
        .buffer(64)
        .flowOn(dispatchers.default)

    @SuppressLint("MissingPermission")
    internal fun recognize(result: ScanResult): Libre3AdvertisedDevice? {
        val device = result.device ?: return null
        val address = device.address ?: return null
        return Libre3AdvertisedDevice(
            device = device,
            address = address,
            rssi = result.rssi,
            heardAtMs = heardAtMs(result),
            raw = advertBytes(result.scanRecord?.bytes),
        )
    }

    /** `timestampNanos` is boot-relative: converts by AGE not offset, clamped at zero if future. */
    private fun heardAtMs(result: ScanResult): Long {
        val ageMs = (elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000L
        return nowMs() - ageMs.coerceAtLeast(0L)
    }

    private companion object {
        const val TAG = "CgmScan"

        /** Locked scan delay. Unbatched scan SUSPENDS silently (no onScanFailed); batched survives. */
        const val BATCH_FLUSH_MS: Long = 5 * 60_000L

        val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(Libre3FamilyDriver.SERVICE_UUID)

        fun scanFilters(): List<ScanFilter> = listOf(
            ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL_UUID).build(),
        )

        /** No delay: scan suspends on lock; a delay offloads buffering to hw, survives lock. */
        fun scanSettings(screenOn: Boolean): ScanSettings = ScanSettings.Builder()
            .setScanMode(
                if (screenOn) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER,
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(true)
            .setReportDelay(if (screenOn) 0L else BATCH_FLUSH_MS)
            .build()
    }
}
