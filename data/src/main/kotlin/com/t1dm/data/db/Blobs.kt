package com.t1dm.data.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** LE is fixed so a DB copied between hosts decodes identically. */
fun DoubleArray.toBlob(): ByteArray {
    val buf = ByteBuffer.allocate(size * Double.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for (v in this) buf.putDouble(v)
    return buf.array()
}

fun List<Double>.toBlob(): ByteArray = toDoubleArray().toBlob()

internal fun ByteArray.toDoubleArray(): DoubleArray {
    require(size % Double.SIZE_BYTES == 0) { "blob length $size is not an f64 multiple" }
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return DoubleArray(size / Double.SIZE_BYTES) { buf.double }
}

fun ByteArray.toDoubleList(): List<Double> = toDoubleArray().asList()
