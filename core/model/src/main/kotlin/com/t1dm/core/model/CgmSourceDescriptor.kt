package com.t1dm.core.model

/** Lifecycle state of a single CGM source (PLAN.private.md §3.1). */
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
 * Stable, persisted identity + matching metadata for a CGM source (PLAN.private.md §3.1).
 * Matching is by name / serial suffix, NEVER by BLE address (resolvable-random rotates).
 */
data class CgmSourceDescriptor(
    val id: CgmSourceId,
    val vendorId: String,          // owning plugin, e.g. "aidexx"
    val displayName: String,       // e.g. "AiDEX X 22222C74D9"
    val serialSuffix: String?,     // the name/serial suffix used to match adverts
    val warmupWindowMin: Int,      // WARMUP_WINDOW_MIN (AiDEX X ≈ 60); drives the WARMUP heuristic
    val passiveOnly: Boolean,      // AiDEX X: true (advertisement-only, no GATT session)
)
