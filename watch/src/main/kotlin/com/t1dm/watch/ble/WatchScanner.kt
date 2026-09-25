package com.t1dm.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** One scan shared by every link: Android refuses a sixth scan start inside 30 s, silently. */
@SuppressLint("MissingPermission")
class WatchScanner(private val context: Context) {

    /** [name] as advertised, which a stack-cached GAP name can differ from. */
    class Found(val device: BluetoothDevice, val name: String)

    private class Waiter(val match: (String) -> Boolean, val found: CompletableDeferred<Found>)

    private val waiters = ArrayList<Waiter>()
    private var running: ScanCallback? = null
    private var lastStartMs = Long.MIN_VALUE / 2

    /** The first advertiser whose name [match]es; throws on timeout or a failed scan. */
    suspend fun find(match: (String) -> Boolean, timeoutMs: Long): Found {
        val w = Waiter(match, CompletableDeferred())
        synchronized(waiters) { waiters += w }
        try {
            return withTimeout(timeoutMs) {
                val wait = synchronized(waiters) { lastStartMs + MIN_START_GAP_MS - SystemClock.elapsedRealtime() }
                if (wait > 0) delay(wait)
                ensureRunning()
                w.found.await()
            }
        } finally {
            synchronized(waiters) {
                waiters -= w
                if (waiters.isEmpty()) stop()
            }
        }
    }

    private fun ensureRunning() = synchronized(waiters) {
        if (running != null || waiters.isEmpty()) return
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: throw IllegalStateException("no BLE scanner (adapter off / no BLE)")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: device.name ?: return
                synchronized(waiters) {
                    waiters.firstOrNull { !it.found.isCompleted && it.match(name) }?.found?.complete(Found(device, name))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                synchronized(waiters) {
                    val e = IllegalStateException("scan failed: $errorCode")
                    waiters.forEach { it.found.completeExceptionally(e) }
                    running = null
                }
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(/* filters = */ null, settings, cb)
        running = cb
        lastStartMs = SystemClock.elapsedRealtime()
    }

    private fun stop() {
        val cb = running ?: return
        running = null
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        }.onFailure { Timber.tag(TAG).w(it, "stopScan failed") }
    }

    private companion object {
        const val TAG = "WatchLink"
        const val MIN_START_GAP_MS = 6_000L
    }
}
