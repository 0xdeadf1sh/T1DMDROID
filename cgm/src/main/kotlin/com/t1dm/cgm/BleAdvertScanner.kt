package com.t1dm.cgm

import android.annotation.SuppressLint
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
import java.util.UUID

/** Passive AiDEX X, name post-filtered; [reportDelayMs]>0=batch mode, ~5min alarm latency. */
class BleAdvertScanner(
    private val scanner: BluetoothLeScanner?,
    private val dispatchers: T1dmDispatchers,
    private val reportDelayMs: Long = 0L,
) {
    @SuppressLint("MissingPermission")
    fun rawAdverts(): Flow<RawAdvert> = callbackFlow {
        val ble = scanner ?: run {
            close(IllegalStateException("BluetoothLeScanner unavailable (adapter off or no BLE)"))
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                rawAdvertFrom(result)?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) rawAdvertFrom(r)?.let { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }

        ble.startScan(scanFilters(), scanSettings(reportDelayMs), callback)
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
            ScanFilter.Builder()
                .setManufacturerData(CgmConstants.MANUFACTURER_ID, byteArrayOf())
                .setServiceUuid(SERVICE_PARCEL_UUID)
                .build(),
        )

        fun scanSettings(reportDelayMs: Long): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(true)
            .setReportDelay(reportDelayMs)
            .build()

        /** Stamped from boot-clock timestampNanos, not flush time; null if no raw AD bytes. */
        @SuppressLint("MissingPermission")
        fun rawAdvertFrom(result: ScanResult): RawAdvert? {
            val record = result.scanRecord ?: return null
            val bytes = record.bytes ?: return null
            val bootToWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            return RawAdvert(
                adBytes = bytes.copyOf(),
                name = record.deviceName ?: result.device?.name,
                rxWallMs = bootToWallMs + result.timestampNanos / 1_000_000L,
                rssi = result.rssi,
            )
        }
    }
}
