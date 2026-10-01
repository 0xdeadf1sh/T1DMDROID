package com.t1dm.cgm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import com.t1dm.core.common.T1dmDispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.UUID

/** [serial] is derived from the advertised name, never from the BLE address (§3.1). */
data class AidexAdvertisedDevice(
    val device: BluetoothDevice,
    val name: String,
    val serial: String,
    val rssi: Int,
    /** The scan record as heard, for the sensor's log. */
    val raw: ByteArray? = null,
)

/** Discovery only, never a data path: obtains a BluetoothDevice handle for connectGatt. */
class AidexDeviceScanner(
    private val scanner: BluetoothLeScanner?,
    private val dispatchers: T1dmDispatchers,
) {
    @SuppressLint("MissingPermission")
    fun discover(): Flow<AidexAdvertisedDevice> = callbackFlow {
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
                Log.w(TAG, "BLE discovery scan failed: $errorCode")
                close(IllegalStateException("BLE discovery scan failed: $errorCode"))
            }
        }

        ble.startScan(scanFilters(), scanSettings(), callback)
        awaitClose { runCatching { ble.stopScan(callback) } }
    }
        .buffer(64)
        .flowOn(dispatchers.default)

    private companion object {
        const val TAG = "CgmScan"

        /** 0x181F expanded against the Bluetooth Base UUID. */
        val SERVICE_PARCEL_UUID: ParcelUuid =
            ParcelUuid(UUID.fromString("0000181F-0000-1000-8000-00805F9B34FB"))

        fun scanFilters(): List<ScanFilter> = listOf(
            ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL_UUID).build(),
        )

        fun scanSettings(): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(true)
            .setReportDelay(0L)
            .build()

        /** Null when the record carries no name, no device, or no matching prefix. */
        @SuppressLint("MissingPermission")
        fun recognize(result: ScanResult): AidexAdvertisedDevice? {
            val device = result.device ?: return null
            val name = result.scanRecord?.deviceName ?: device.name ?: return null
            val match = CgmConstants.matchAdvertName(name) ?: return null
            return AidexAdvertisedDevice(
                device = device,
                name = name,
                serial = match.serial,
                rssi = result.rssi,
                raw = advertBytes(result.scanRecord?.bytes),
            )
        }
    }
}
