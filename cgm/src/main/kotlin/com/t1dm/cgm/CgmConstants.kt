package com.t1dm.cgm

import com.t1dm.core.model.CgmSensorModelId

/** §3.1, CGM.md §1/§3. Pure-JVM: no android.* here, or every reader needs Robolectric. */
object CgmConstants {
    /** CGM.md §3. */
    const val MANUFACTURER_ID: Int = 0x0059

    /** CGM.md §3. */
    const val SERVICE_UUID16: Int = 0x181F

    /** CGM.md §1: match by name/serial, never BLE address. Four names, one platform, not a bug. */
    val MODEL_BY_NAME_PREFIX: Map<String, String> = linkedMapOf(
        "LinX-" to CgmSensorModelId.AIDEX_X,     // EU
        "AiDEX X-" to CgmSensorModelId.AIDEX_X,  // Asia
        "Lumi-" to CgmSensorModelId.AIDEX_X,     // LumiFlex
        "Smart-" to CgmSensorModelId.AIDEX_X,    // Brazil
    )

    /** In match order, derived — never a second list. */
    val NAME_PREFIXES: List<String> = MODEL_BY_NAME_PREFIX.keys.toList()

    /** [brand] is the matched prefix without its separator: "LinX-00000T1DM0" reads "LinX". */
    data class AdvertMatch(
        val prefix: String,
        val sensorModelId: String,
        val brand: String,
        val serial: String,
    )

    /** Prefixes may overlap; map order decides. Null if no match or serial is empty. */
    fun matchAdvertName(advertName: String): AdvertMatch? {
        val entry = MODEL_BY_NAME_PREFIX.entries.firstOrNull { advertName.startsWith(it.key) } ?: return null
        val serial = advertName.removePrefix(entry.key)
        if (serial.isEmpty()) return null
        return AdvertMatch(
            prefix = entry.key,
            sensorModelId = entry.value,
            brand = entry.key.trimEnd('-', ' '),
            serial = serial,
        )
    }

    /** Glucose payload is exactly 20 B (CGM.md §3.1); the ~5 B status advert is not. */
    const val GLUCOSE_PAYLOAD_MIN_LEN: Int = 20

    /** §3.1: adverts carry no warm-up bit; minFromStart below 60 reads WARMUP (AiDEX X ~60 min). */
    const val WARMUP_WINDOW_MIN: Int = 60

    /** The 5-minute grid quantum in ms; every persisted `tsMs % GRID_MS == 0`. */
    const val GRID_MS: Long = 300_000L

    /** mg/dL, CGM.md §9. */
    val VALID_BG_RANGE: IntRange = 18..800
}
