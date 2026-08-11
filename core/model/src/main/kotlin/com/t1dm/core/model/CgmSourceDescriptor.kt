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
 * The sensor MODEL a source is an instance of — the scope over which displayed history is
 * continuous (§3.1).
 *
 * A `sourceId` names one physical sensor and dies with it; a model id names the family, and every
 * sensor of one family shares a single trace on the BG panel. Without this, replacing an expired
 * sensor emptied the panel: the graph is scoped to the active source, so a new serial meant a new
 * (empty) history, and the pannable domain — floored at the first reading of that history — put
 * every earlier logged meal and dose out of reach.
 *
 * **A display scope, never an authority scope.** Exactly one source is still active and still the
 * sole authority for the live value, the alarm engine, and the `sample` projection the model reads.
 * Widening what is *drawn* must never widen what is *believed*.
 */
object CgmSensorModelId {
    /** The only real sensor family the app reads today, and the class every pre-v11 row belongs to. */
    const val AIDEX_X = "aidexx:x"

    /**
     * The debug-injection source's own class, rather than an [AIDEX_X] instance.
     *
     * It matters in one case only, and a narrow one: `CgmScanService.ensureActiveSource` mints this
     * source purely to give injected readings somewhere to land when NO source exists yet, so on a
     * phone that has met a real sensor an injection is attributed to that real sensor and this class
     * never comes up. Where it does — a fresh install driven entirely by injection — it keeps the
     * synthetic history from grafting onto the first real sensor discovered afterwards.
     */
    const val AIDEX_DEBUG = "aidexx:debug"
}

/**
 * Stable, persisted identity + matching metadata for a CGM source (§3.1).
 * Matching is by name / serial suffix, NEVER by BLE address (resolvable-random rotates).
 */
data class CgmSourceDescriptor(
    val id: CgmSourceId,
    val vendorId: String,          // owning plugin, e.g. "aidexx"
    val sensorModelId: String,     // sensor family; the scope displayed history spans ([CgmSensorModelId])
    val advertName: String?,       // what the sensor announced, verbatim; null = never recorded
    val displayName: String,       // e.g. "AiDEX X 22222C74D9"
    val serialSuffix: String?,     // the name/serial suffix used to match adverts
    val warmupWindowMin: Int,      // seeded per vendor, then user-tunable; drives the WARMUP heuristic
    val passiveOnly: Boolean,      // AiDEX X: true (advertisement-only, no GATT session)
    /**
     * Removed from the sensor lists by the user — a retired sensor kept off a list that only ever
     * grows. It is a DISPLAY flag and nothing more: the source stays on record, so its readings stay
     * in the sensor-model history the BG panel draws (the id set comes from `cgm_source`, and
     * dropping the row would take that stretch of the trace with it).
     *
     * Carried on the descriptor because the descriptor is what the sensor lists are built from, and a
     * list cannot filter on a field it cannot see. Storage is not at risk either way: the one writer
     * that could undo a removal — the re-upsert on every sighting — reads the flag from the stored row
     * and never from the descriptor handed to it.
     */
    val hidden: Boolean = false,
) {
    /**
     * [displayName] with the serial the vendor plugin folded into it removed — "AiDEX X" rather than
     * "AiDEX X 22222C74D9".
     *
     * For the surfaces that name the source by its role rather than by unit: the BG panel says which
     * source a reading came from, exactly one is ever active, and there the serial is noise. The CGM
     * panel and CGM settings exist to tell one sensor from another and keep [displayName] and
     * [serialSuffix].
     *
     * Falls back to the full name when stripping would leave nothing, or when the vendor did not build
     * the name out of the serial at all.
     */
    val shortName: String
        get() {
            val serial = serialSuffix ?: return displayName
            val stripped = displayName.removeSuffix(serial).trimEnd()
            return stripped.ifEmpty { displayName }
        }

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
