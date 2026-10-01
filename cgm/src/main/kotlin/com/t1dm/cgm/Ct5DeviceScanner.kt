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

/** Identity is name-derived [bsn], never the BLE address. */
data class Ct5AdvertisedDevice(
    val device: BluetoothDevice,
    val name: String,
    /** The 10-digit BSN, this family's identity. */
    val bsn: String,
    val rssi: Int,
    /** Wall-clock time CONTROLLER heard it, not delivery; batched sighting can lag minutes. */
    val heardAtMs: Long,
    /** `null` = no mfg block; unknown = CLAIMED. Flips only after 0x38+0x06, never 0x30. */
    val bound: Boolean?,
    /** Telemetry block populated means running, not idle; `null` as for [bound]. */
    val running: Boolean?,
    /** The scan record as heard, for the sensor's log. */
    val raw: ByteArray? = null,
)

/** No glucose here; filters name/category post-scan (ScanFilter can't). Needs BLUETOOTH_SCAN. */
class Ct5DeviceScanner(
    private val scanner: BluetoothLeScanner?,
    private val dispatchers: T1dmDispatchers,
    private val session: Ct5Session,
    /** Read once per window, not watched — restarting mid-window loses the controller's buffer. */
    private val screenOn: () -> Boolean = { true },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    @SuppressLint("MissingPermission")
    fun discover(): Flow<Ct5AdvertisedDevice> = callbackFlow {
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
                Log.w(TAG, "CT5 discovery scan failed: $errorCode")
                close(IllegalStateException("CT5 discovery scan failed: $errorCode"))
            }
        }

        ble.startScan(scanFilters(), scanSettings(screenOn()), callback)
        awaitClose { runCatching { ble.stopScan(callback) } }
    }
        .buffer(64)
        .flowOn(dispatchers.default)

    /** Null if no device/name match; no CGM block = no bind evidence. Checksum read, unchecked. */
    @SuppressLint("MissingPermission")
    internal fun recognize(result: ScanResult): Ct5AdvertisedDevice? {
        val device = result.device ?: return null
        val record = result.scanRecord
        val name = record?.deviceName ?: device.name ?: return null
        val bsn = Ct5Constants.bsnFrom(name) ?: return null
        val mfg = record?.manufacturerSpecificData
        val payload = (0 until (mfg?.size() ?: 0))
            .asSequence()
            .mapNotNull { i -> mfg?.let { Ct5Constants.adPayloadOf(it.keyAt(i), it.valueAt(i)) } }
            .firstOrNull(Ct5Constants::hasCgmCategory)
        val advert = payload?.let { session.parseAdvert(it) }
        return Ct5AdvertisedDevice(
            device = device,
            name = name,
            bsn = bsn,
            rssi = result.rssi,
            heardAtMs = heardAtMs(result),
            bound = advert?.bound,
            running = advert?.running,
            raw = advertBytes(record?.bytes),
        )
    }

    /** `timestampNanos` is boot-relative: converts by AGE not offset, clamped at zero if future. */
    private fun heardAtMs(result: ScanResult): Long {
        val ageMs = (elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000L
        return nowMs() - ageMs.coerceAtLeast(0L)
    }

    private companion object {
        const val TAG = "CgmScan"

        val DFU_PARCEL_UUID: ParcelUuid = ParcelUuid(Ct5Constants.DFU_SERVICE_UUID)

        fun scanFilters(): List<ScanFilter> = listOf(
            ScanFilter.Builder().setServiceUuid(DFU_PARCEL_UUID).build(),
        )

        /** No delay: scan suspends on lock; a delay offloads buffering to hw, survives lock. */
        fun scanSettings(screenOn: Boolean): ScanSettings = ScanSettings.Builder()
            // Meaningless against a buffering controller; was this app's top battery consumer.
            .setScanMode(
                if (screenOn) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER,
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(true)
            .setReportDelay(if (screenOn) 0L else Ct5Constants.BATCH_FLUSH_MS)
            .build()
    }
}
