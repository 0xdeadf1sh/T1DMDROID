package com.t1dm.cgm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log

/** [serial] is derived from the bonded name, never from the BLE address (§3.1). */
data class AidexBondedDevice(
    val device: BluetoothDevice,
    val name: String,
    val serial: String,
)

/** PAIRED AiDEX devices; connectGatt works on a bonded handle whether or not it advertises. */
class BondedAidexDevices(private val adapter: BluetoothAdapter?) {

    /** In the system's own order. Total: an adapter off or absent yields nothing, never a throw. */
    @SuppressLint("MissingPermission")
    fun all(): List<AidexBondedDevice> {
        val ad = adapter ?: return emptyList()
        if (!ad.isEnabled) return emptyList()
        val bonded = runCatching { ad.bondedDevices }.getOrElse { e ->
            // A SecurityException here means BLUETOOTH_CONNECT was revoked while running.
            Log.w(TAG, "cannot read bonded devices: ${e.message}")
            null
        } ?: return emptyList()
        return bonded.mapNotNull { dev ->
            val name = runCatching { dev.name }.getOrNull() ?: return@mapNotNull null
            val match = CgmConstants.matchAdvertName(name) ?: return@mapNotNull null
            AidexBondedDevice(device = dev, name = name, serial = match.serial)
        }
    }

    /** Null when it is not paired, or the adapter is off. */
    fun forSerial(serial: String): AidexBondedDevice? = all().firstOrNull { it.serial == serial }

    private companion object {
        const val TAG = "CgmScan"
    }
}
