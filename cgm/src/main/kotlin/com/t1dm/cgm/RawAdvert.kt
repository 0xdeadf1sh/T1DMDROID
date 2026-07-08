package com.t1dm.cgm

/**
 * One captured BLE advertisement, copied off the binder thread (PLAN.private.md §2.3 — the scan
 * callback does "bytes copy + wall-time map + channel offer only"). [adBytes] is the *raw* AD
 * structure array (`ScanRecord.getBytes()`), re-parsed by [AdStructureParser] ourselves because
 * Android merges the two 0x0059 manufacturer structures and `getManufacturerSpecificData()`
 * cannot be trusted (CGM.md §3).
 */
data class RawAdvert(
    val adBytes: ByteArray,
    val name: String?,
    val rxWallMs: Long,
    val rssi: Int?,
) {
    // Value-based equality on the payload so dedup/test comparisons behave; arrays otherwise
    // compare by identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawAdvert) return false
        return adBytes.contentEquals(other.adBytes) &&
            name == other.name && rxWallMs == other.rxWallMs && rssi == other.rssi
    }

    override fun hashCode(): Int {
        var h = adBytes.contentHashCode()
        h = 31 * h + (name?.hashCode() ?: 0)
        h = 31 * h + rxWallMs.hashCode()
        h = 31 * h + (rssi ?: 0)
        return h
    }
}
