package com.t1dm.cgm

/** Android's `ScanRecord.getManufacturerSpecificData` returns the MERGED concatenation of the two
 *  0x0059 structures the sensor advertises (CGM.md §3), so the `[len][type][data…]` TLV stream is
 *  walked here instead. */
object AdStructureParser {

    /** The bytes AFTER the 2-byte company id, from the first matching structure whose payload is at
     *  least [minLen] — the glucose block, never the short status block. Null when there is none. */
    fun manufacturerPayload(
        adBytes: ByteArray,
        companyId: Int = CgmConstants.MANUFACTURER_ID,
        minLen: Int = CgmConstants.GLUCOSE_PAYLOAD_MIN_LEN,
    ): ByteArray? {
        var i = 0
        val n = adBytes.size
        while (i < n) {
            val len = adBytes[i].toInt() and 0xFF
            if (len == 0) break                 // zero-length terminates the AD stream
            if (i + len >= n) break             // structure claims to run past the buffer
            val type = adBytes[i + 1].toInt() and 0xFF
            // 0xFF = Manufacturer Specific Data; needs at least type(1)+company(2).
            if (type == 0xFF && len >= 3) {
                val cid = (adBytes[i + 2].toInt() and 0xFF) or ((adBytes[i + 3].toInt() and 0xFF) shl 8)
                val payloadLen = len - 3
                if (cid == companyId && payloadLen >= minLen) {
                    val from = i + 4
                    return adBytes.copyOfRange(from, from + payloadLen)
                }
            }
            i += len + 1                        // [len byte] + len data bytes
        }
        return null
    }

    /** The Complete (0x09) or Shortened (0x08) Local Name (CGM.md §3). */
    fun localName(adBytes: ByteArray): String? {
        var i = 0
        val n = adBytes.size
        while (i < n) {
            val len = adBytes[i].toInt() and 0xFF
            if (len == 0) break
            if (i + len >= n) break
            val type = adBytes[i + 1].toInt() and 0xFF
            if ((type == 0x09 || type == 0x08) && len >= 1) {
                val from = i + 2
                return String(adBytes.copyOfRange(from, from + (len - 1)), Charsets.UTF_8)
            }
            i += len + 1
        }
        return null
    }
}
