package com.t1dm.cgm

/** adBytes: raw AD array, re-parsed since Android merges dup 0x0059 structs (CGM.md §3). */
data class RawAdvert(
    val adBytes: ByteArray,
    val name: String?,
    val rxWallMs: Long,
    val rssi: Int?,
) {
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
