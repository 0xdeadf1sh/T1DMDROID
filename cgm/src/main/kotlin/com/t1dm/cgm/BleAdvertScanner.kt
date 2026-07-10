package com.t1dm.cgm

import android.annotation.SuppressLint
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

/**
 * Passive AiDEX X advertisement scanner (Phase 1). Filters at the BLE layer on
 * manufacturer 0x0059 **and** the 0x181F CGM service, in `SCAN_MODE_BALANCED`, legacy adverts,
 * `CALLBACK_TYPE_ALL_MATCHES`, `reportDelay 0`. Name-prefix (`LinX-`, …) can't be expressed as a
 * `ScanFilter` (that needs an exact name), so it is post-filtered here.
 *
 * The callback does the minimum on the binder thread — copy the raw AD bytes, stamp the receive
 * wall-time, offer to the channel (§2.3) — and all decode work happens downstream on
 * `Dispatchers.Default` via [flowOn]. No `BLUETOOTH_CONNECT`, no location: this is
 * advertisement-only (`neverForLocation`, cgm-ingestion memory).
 */
class BleAdvertScanner(
    private val scanner: BluetoothLeScanner?,
    private val dispatchers: T1dmDispatchers,
) {
    @SuppressLint("MissingPermission")
    fun rawAdverts(): Flow<RawAdvert> = callbackFlow {
        val ble = scanner ?: run {
            close(IllegalStateException("BluetoothLeScanner unavailable (adapter off or no BLE)"))
            return@callbackFlow
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(CgmConstants.MANUFACTURER_ID, byteArrayOf())
                .setServiceUuid(SERVICE_PARCEL_UUID)
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(true)
            .setReportDelay(0L)
            .build()

        val callback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val bytes = record.bytes ?: return
                trySend(
                    RawAdvert(
                        adBytes = bytes.copyOf(),
                        name = record.deviceName ?: result.device?.name,
                        rxWallMs = System.currentTimeMillis(),
                        rssi = result.rssi,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }

        ble.startScan(filters, settings, callback)
        awaitClose { runCatching { ble.stopScan(callback) } }
    }
        .buffer(64)
        .flowOn(dispatchers.default)

    private companion object {
        const val TAG = "CgmScan"

        /** 0x181F expanded against the Bluetooth Base UUID. */
        val SERVICE_PARCEL_UUID: ParcelUuid =
            ParcelUuid(UUID.fromString("0000181F-0000-1000-8000-00805F9B34FB"))
    }
}
