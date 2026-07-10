package com.t1dm.cgm

/**
 * Frozen passive-AiDEX pipeline constants (SPEC.private.md §3.1, CGM.md §1/§3, cgm-ingestion
 * memory). Pure-JVM only — no `android.*` types live here, so every pipeline stage that reads
 * these constants stays unit-testable without Robolectric. Android-typed handles (the service
 * ParcelUuid) live inside [BleAdvertScanner].
 */
object CgmConstants {
    /** BLE company id of the 0x0059 manufacturer-specific glucose payload (CGM.md §3). */
    const val MANUFACTURER_ID: Int = 0x0059

    /** 16-bit CGM Service UUID advertised alongside the payload (CGM.md §3). */
    const val SERVICE_UUID16: Int = 0x181F

    /**
     * Advertised-name prefixes for the AiDEX X family (CGM.md §1). Match is by name / serial
     * suffix, NEVER by BLE address (resolvable-random rotates). `LinX-` is the EU brand.
     */
    val NAME_PREFIXES: List<String> = listOf("LinX-", "AiDEX X-", "Lumi-", "Smart-")

    /** The glucose manufacturer payload is exactly 20 bytes (CGM.md §3.1); the interleaved
     *  status advert is ~5 bytes and is rejected by this floor. */
    const val GLUCOSE_PAYLOAD_MIN_LEN: Int = 20

    /**
     * Passive WARMUP heuristic window (SPEC.private.md §3.1). The passive advert carries no
     * warmup bit, so `minFromStart < WARMUP_WINDOW_MIN ⇒ WARMUP`. AiDEX X warm-up ≈ 60 min; the
     * official app owns real warm-up, this is a belt-and-suspenders default.
     */
    const val WARMUP_WINDOW_MIN: Int = 60

    /** The 5-minute grid quantum in ms; every persisted `tsMs % GRID_MS == 0`. */
    const val GRID_MS: Long = 300_000L

    /** Glucose values treated as physiologically valid (CGM.md §4). */
    val VALID_BG_RANGE: IntRange = 18..800
}
