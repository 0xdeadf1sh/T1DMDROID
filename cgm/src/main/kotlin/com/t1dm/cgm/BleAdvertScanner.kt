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

/**
 * Passive AiDEX X advertisement scanner (Phase 1). Filters at the BLE layer on
 * manufacturer 0x0059 **and** the 0x181F CGM service, `SCAN_MODE_LOW_LATENCY`, legacy adverts,
 * `CALLBACK_TYPE_ALL_MATCHES`. Name-prefix (`LinX-`, …) can't be expressed as a `ScanFilter` (that
 * needs an exact name), so it is post-filtered downstream.
 *
 * **Report-delay batching for screen-off survival ([reportDelayMs] > 0).** Stock AOSP keeps a
 * *filtered* foreground-service scan alive across screen-off, but Xiaomi/HyperOS goes beyond AOSP and
 * fully SUSPENDS it the moment the phone locks (`dumpsys bluetooth_manager` → "Filter Suspended";
 * verified on-device — a `reportDelay 0` scan, callback or PendingIntent, delivers nothing while
 * locked). The one configuration that survives is **offloaded batch scanning**: the controller buffers
 * adverts in hardware, independent of the host's screen state, and flushes them via
 * [ScanCallback.onBatchScanResults] — on a Xiaomi-imposed ~5-minute timer when locked, or every
 * [reportDelayMs] when awake. A ~5-min flush loses no grid slot for a sensor that advertises ~1/min
 * onto a 5-min grid (CGM.md §3); it does add up to ~5 min of latency to the screen-off alarm path.
 * The caller passes `reportDelayMs = 0` (real-time callback) only when the controller reports no
 * offloaded-batching support.
 *
 * Because batched results arrive together long after capture, each is stamped from
 * [ScanResult.getTimestampNanos] (its true boot-clock receive time, mapped to wall time), NOT the
 * flush moment — so grid-snapping downstream lands every advert in its real 5-min slot.
 *
 * The callback does the minimum on the binder thread — copy the raw AD bytes, map the timestamp,
 * offer to the channel (§2.3) — and all decode work happens downstream on `Dispatchers.Default` via
 * [flowOn]. No `BLUETOOTH_CONNECT`, no location: advertisement-only (`neverForLocation`).
 */
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

            // Batched (screen-off) delivery: a whole buffered burst arrives at once.
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

        /** The BLE-layer filter (manufacturer 0x0059 ∧ the 0x181F CGM service). */
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

        /** Copy one [ScanResult] into a [RawAdvert] off whatever thread delivered it. The receive
         *  wall-time is derived from the record's boot-clock [ScanResult.getTimestampNanos] so a
         *  batch flushed minutes later still stamps each advert at its true capture instant. Null when
         *  the record carries no raw AD bytes. */
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
