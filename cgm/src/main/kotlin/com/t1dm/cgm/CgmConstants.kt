package com.t1dm.cgm

import com.t1dm.core.model.CgmSensorModelId

/** Passive-AiDEX consts (§3.1, CGM.md §1/§3); pure JVM, unit-testable w/o Robolectric. */
object CgmConstants {
    /** CGM.md §3. */
    const val MANUFACTURER_ID: Int = 0x0059

    /** CGM.md §3. */
    const val SERVICE_UUID16: Int = 0x181F

    /** Name prefix→model (CGM.md §1); match name/serial, never rotating BLE addr; wear varies. */
    val MODEL_BY_NAME_PREFIX: Map<String, String> = linkedMapOf(
        "LinX-" to CgmSensorModelId.AIDEX_X,     // EU
        "AiDEX X-" to CgmSensorModelId.AIDEX_X,  // Asia
        "Lumi-" to CgmSensorModelId.AIDEX_X,     // LumiFlex
        "Smart-" to CgmSensorModelId.AIDEX_X,    // Brazil
    )

    /** In match order, derived: Juggluco's second hand-maintained copy is missing an entry. */
    val NAME_PREFIXES: List<String> = MODEL_BY_NAME_PREFIX.keys.toList()

    /** [brand] is the matched prefix without its separator: "LinX-22222C74D9" reads "LinX". */
    data class AdvertMatch(
        val prefix: String,
        val sensorModelId: String,
        val brand: String,
        val serial: String,
    )

    /** The one matcher: startsWith in prefix order; overlap picks order; empty serial ⇒ null. */
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

    /** CGM.md §3.1; the interleaved ~5-byte status advert is rejected by this floor. */
    const val GLUCOSE_PAYLOAD_MIN_LEN: Int = 20

    /** Seed only (§3.1): no warm-up bit, minFromStart<WARMUP_WINDOW_MIN⇒WARMUP; AiDEX X≈60min. */
    const val WARMUP_WINDOW_MIN: Int = 60

    /** Grid quantum, ms; every persisted `tsMs % GRID_MS == 0`. */
    const val GRID_MS: Long = 300_000L

    /** mg/dL (CGM.md §4). */
    val VALID_BG_RANGE: IntRange = 18..800
}
