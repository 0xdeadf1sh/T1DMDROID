package com.t1dm.cgm

import com.t1dm.core.model.CgmSensorModelId

/**
 * Frozen passive-AiDEX pipeline constants (§3.1, CGM.md §1/§3). Pure-JVM only — no `android.*` types live here, so every pipeline stage that reads
 * these constants stays unit-testable without Robolectric. Android-typed handles (the service
 * ParcelUuid) live inside [BleAdvertScanner].
 */
object CgmConstants {
    /** BLE company id of the 0x0059 manufacturer-specific glucose payload (CGM.md §3). */
    const val MANUFACTURER_ID: Int = 0x0059

    /** 16-bit CGM Service UUID advertised alongside the payload (CGM.md §3). */
    const val SERVICE_UUID16: Int = 0x181F

    /**
     * Every advertised-name prefix this vendor's sensors use, each mapped to the sensor model it
     * identifies (CGM.md §1). Match is by name / serial suffix, NEVER by BLE address
     * (resolvable-random rotates).
     *
     * **This table is where a vendor declares its models, and it is per-vendor by design.** A second
     * company's plugin brings its own; nothing central lists them, so two vendors can never collide
     * on a class and adding one edits no shared file.
     *
     * **All four resolve to one model, and that is the finding, not a placeholder.** They are brand
     * skins of a single MicroTech platform: the vendor's own FY2024 annual report gives LinX as the
     * international brand name for AiDEX X, and Juggluco — whose list this one descends from —
     * states that everything true of AiDEX X sensors is true of LinX but the name. Three independent
     * implementations (this one, Juggluco, and the vendor's app) treat the prefix as a recognition
     * token that is discarded, never a dispatch key: one scan filter, one decoder, one session. The
     * only per-brand difference found anywhere is wear duration, which is a user setting here.
     *
     * Splitting them would be the worse error — it would break a trace that ought to be continuous,
     * which is the very defect the model class exists to fix. `advertName` is recorded per source so
     * a future split can reclassify what is already on record.
     */
    val MODEL_BY_NAME_PREFIX: Map<String, String> = linkedMapOf(
        "LinX-" to CgmSensorModelId.AIDEX_X,     // EU
        "AiDEX X-" to CgmSensorModelId.AIDEX_X,  // Asia
        "Lumi-" to CgmSensorModelId.AIDEX_X,     // LumiFlex
        "Smart-" to CgmSensorModelId.AIDEX_X,    // Brazil
    )

    /**
     * The prefixes alone, in match order — derived, never a second list.
     *
     * Juggluco keeps a second hand-maintained copy of its equivalent list in native code with one
     * entry missing, and those devices are mishandled as a result. One list, read two ways.
     */
    val NAME_PREFIXES: List<String> = MODEL_BY_NAME_PREFIX.keys.toList()

    /**
     * Everything one advertised name yields, resolved by a single pass over [MODEL_BY_NAME_PREFIX].
     *
     * [brand] is the matched prefix without its separator, so "LinX-22222C74D9" reads "LinX" — which
     * is what lets a source name itself honestly. Every source used to be labelled "AiDEX X" whichever
     * brand it advertised, because the label was a hardcoded string rather than anything the sensor
     * said.
     */
    data class AdvertMatch(
        val prefix: String,
        val sensorModelId: String,
        val brand: String,
        val serial: String,
    )

    /**
     * Resolve an advertised name once — prefix, model, brand and serial together.
     *
     * **One matcher, because overlap order is a decision and it must be made in one place.** The
     * prefixes are matched by `startsWith` and nothing stops a future pair from overlapping; three
     * sites each running their own `firstOrNull` were three chances to disagree about which prefix
     * won, on the same string. [MODEL_BY_NAME_PREFIX] is ordered, this walks it in order, and that
     * order is now the only answer.
     *
     * Null when no prefix matches or the serial would be empty — a name with nothing after its brand
     * identifies no sensor, and the callers all treated that as unrecognised anyway.
     */
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

    /** The glucose manufacturer payload is exactly 20 bytes (CGM.md §3.1); the interleaved
     *  status advert is ~5 bytes and is rejected by this floor. */
    const val GLUCOSE_PAYLOAD_MIN_LEN: Int = 20

    /**
     * Passive WARMUP heuristic window (§3.1). The passive advert carries no
     * warmup bit, so `minFromStart < WARMUP_WINDOW_MIN ⇒ WARMUP`. AiDEX X warm-up ≈ 60 min; the
     * official app owns real warm-up, this is a belt-and-suspenders default.
     */
    const val WARMUP_WINDOW_MIN: Int = 60

    /** The 5-minute grid quantum in ms; every persisted `tsMs % GRID_MS == 0`. */
    const val GRID_MS: Long = 300_000L

    /** Glucose values treated as physiologically valid (CGM.md §4). */
    val VALID_BG_RANGE: IntRange = 18..800
}
