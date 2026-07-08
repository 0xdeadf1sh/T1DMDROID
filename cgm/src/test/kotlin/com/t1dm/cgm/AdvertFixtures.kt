package com.t1dm.cgm

/** Assembles raw BLE AD structure streams (the bytes an [AdStructureParser] must re-parse). */
object AdvertFixtures {

    /** The CGM.md §3.1 golden glucose payload (21600 min, 92 mg/dL valid, prev 84/78). */
    val GOLDEN_PAYLOAD: ByteArray = hex(
        "60 54 01 00 1F 5C 80 3E 54 80 3A 4E 80 36 00 00 7E AE DD 01",
    )

    const val GOLDEN_NAME = "LinX-22222C74D9"

    fun hex(s: String): ByteArray =
        s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun struct(type: Int, data: ByteArray): ByteArray {
        val out = ByteArray(2 + data.size)
        out[0] = (1 + data.size).toByte()   // len covers type + data
        out[1] = type.toByte()
        data.copyInto(out, 2)
        return out
    }

    private fun manufacturer(companyId: Int, payload: ByteArray): ByteArray {
        val data = ByteArray(2 + payload.size)
        data[0] = (companyId and 0xFF).toByte()
        data[1] = ((companyId ushr 8) and 0xFF).toByte()
        payload.copyInto(data, 2)
        return struct(0xFF, data)
    }

    /**
     * A full connectable advert mirroring CGM.md §3: flags, 0x181F service, the ≥20-byte 0x0059
     * glucose block, the local name, and the interleaved short 0x0059 status block that Android
     * merges into the glucose one (so the parser must pick by length).
     */
    fun fullAdvert(payload: ByteArray = GOLDEN_PAYLOAD, name: String = GOLDEN_NAME): ByteArray {
        val flags = struct(0x01, byteArrayOf(0x06))
        val service = struct(0x02, hex("1F 18"))
        val glucose = manufacturer(CgmConstants.MANUFACTURER_ID, payload)
        val localName = struct(0x09, name.toByteArray(Charsets.UTF_8))
        val status = manufacturer(CgmConstants.MANUFACTURER_ID, hex("AA BB CC DD EE"))
        return flags + service + glucose + localName + status
    }

    /** Same structures, but the short 0x0059 block precedes the glucose one. */
    fun fullAdvertShortFirst(payload: ByteArray = GOLDEN_PAYLOAD, name: String = GOLDEN_NAME): ByteArray {
        val flags = struct(0x01, byteArrayOf(0x06))
        val service = struct(0x02, hex("1F 18"))
        val status = manufacturer(CgmConstants.MANUFACTURER_ID, hex("AA BB CC DD EE"))
        val glucose = manufacturer(CgmConstants.MANUFACTURER_ID, payload)
        val localName = struct(0x09, name.toByteArray(Charsets.UTF_8))
        return flags + service + status + glucose + localName
    }

    fun raw(adBytes: ByteArray, rxWallMs: Long, name: String = GOLDEN_NAME, rssi: Int? = -60): RawAdvert =
        RawAdvert(adBytes = adBytes, name = name, rxWallMs = rxWallMs, rssi = rssi)
}
