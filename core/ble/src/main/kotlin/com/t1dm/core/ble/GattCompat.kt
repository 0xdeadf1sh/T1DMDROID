package com.t1dm.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build

/** A [BluetoothStatusCodes] value on every API level. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun BluetoothGatt.writeCharacteristicCompat(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    writeType: Int,
): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeCharacteristic(characteristic, value, writeType)
    } else {
        characteristic.writeType = writeType
        characteristic.value = value
        writeCharacteristic(characteristic).toStatus()
    }

/** A [BluetoothStatusCodes] value on every API level. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun BluetoothGatt.writeDescriptorCompat(descriptor: BluetoothGattDescriptor, value: ByteArray): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeDescriptor(descriptor, value)
    } else {
        descriptor.value = value
        writeDescriptor(descriptor).toStatus()
    }

private fun Boolean.toStatus(): Int = if (this) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN

/** Null below API 33 for a random address: no handle carries its type, so the caller scans. */
fun BluetoothAdapter.remoteLeDeviceCompat(address: String, addressType: Int): BluetoothDevice? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getRemoteLeDevice(address, addressType)
    addressType == BluetoothDevice.ADDRESS_TYPE_PUBLIC -> getRemoteDevice(address)
    else -> null
}

/** Before API 33 the stack calls only the value-less callbacks; both reach onNotify/onRead. */
abstract class GattCallbackCompat : BluetoothGattCallback() {
    open fun onNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = Unit

    open fun onRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
        Unit

    final override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) = onNotify(gatt, characteristic, value)

    final override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) = onRead(gatt, characteristic, value, status)

    @Deprecated("Called before API 33 only", ReplaceWith("onNotify"))
    @Suppress("DEPRECATION")
    final override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) =
        onNotify(gatt, characteristic, characteristic.value?.copyOf() ?: EMPTY)

    @Deprecated("Called before API 33 only", ReplaceWith("onRead"))
    @Suppress("DEPRECATION")
    final override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) = onRead(gatt, characteristic, characteristic.value?.copyOf() ?: EMPTY, status)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
