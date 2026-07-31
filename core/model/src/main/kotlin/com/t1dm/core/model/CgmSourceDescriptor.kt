package com.t1dm.core.model

/** Lifecycle state of a single CGM source (§3.1). */
enum class CgmSourceStatus {
    /** Not scanning; no recent adverts. */
    Idle,

    /** Scanning but no CRC-valid glucose advert decoded yet. */
    Scanning,

    /** Receiving readings still inside the warm-up window (values suppressed). */
    Warmup,

    /** Receiving fresh, valid, out-of-warmup readings. */
    Live,

    /** Was Live but no MEASURED reading has arrived within the loss-of-signal window. */
    SignalLost,
}

/**
 * Stable, persisted identity + matching metadata for a CGM source (§3.1).
 * Matching is by name / serial suffix, NEVER by BLE address (resolvable-random rotates).
 */
data class CgmSourceDescriptor(
    val id: CgmSourceId,
    val vendorId: String,          // owning plugin, e.g. "aidexx"
    val displayName: String,       // e.g. "AiDEX X 22222C74D9"
    val serialSuffix: String?,     // the name/serial suffix used to match adverts
    val warmupWindowMin: Int,      // seeded per vendor, then user-tunable; drives the WARMUP heuristic
    val passiveOnly: Boolean,      // AiDEX X: true (advertisement-only, no GATT session)
) {
    companion object {
        /**
         * The bounds the user may tune [warmupWindowMin] to (minutes), per source. The *seed* for a
         * newly discovered sensor is a vendor constant and lives with that vendor's plugin; this is
         * only how far the knob travels once the sensor exists.
         *
         * `0` means **no warm-up**: no `minFromStart` is below zero, so every reading is eligible from
         * the first minute — the heuristic switches itself off rather than needing a separate flag.
         */
        val WARMUP_WINDOW_RANGE: IntRange = 0..360
    }
}
