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
 * The sensor MODEL a source is an instance of — the scope displayed history spans (§3.1). A
 * `sourceId` names one physical sensor; a model id names the family, and one family shares one
 * trace. A DISPLAY scope, never an authority scope: one source is the authority for the live value.
 */
object CgmSensorModelId {
    /** The only real sensor family the app reads today, and the class every pre-v11 row belongs to. */
    const val AIDEX_X = "aidexx:x"

    /** The debug-injection source's own class. Reached only on a phone that has met no real sensor,
     *  where it keeps synthetic history from grafting onto the first real one discovered. */
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
    val passiveOnly: Boolean,      // AiDEX X: false (connected GATT session is the sole read path)
    /**
     * Removed from the sensor lists by the user. A DISPLAY flag only: the source stays on record, so
     * its readings stay in the sensor-model history the BG panel draws.
     */
    val hidden: Boolean = false,
    /**
     * A small stable number for this sensor, zero-based; [UNASSIGNED_ORDINAL] until storage mints
     * one, which it does once, in the write transaction that first records the row. Never
     * positional: delisting an earlier sensor would renumber every later one.
     */
    val ordinal: Int = UNASSIGNED_ORDINAL,
) {
    /** [displayName] with [serialSuffix] stripped — "AiDEX X" rather than "AiDEX X 22222C74D9". */
    val shortName: String
        get() {
            val serial = serialSuffix ?: return displayName
            val stripped = displayName.removeSuffix(serial).trimEnd()
            return stripped.ifEmpty { displayName }
        }

    /**
     * For surfaces that name the sensor INCIDENTALLY. A vendor may build the advertised name out of
     * the serial printed on the sensor, so a photograph of such a surface carries the serial too;
     * this says it with a counter instead. Unnumbered sensors are named by kind alone.
     */
    fun ordinalLabel(): String = if (ordinal >= 0) "CGM #$ordinal" else "CGM"

    /** One function for every incidental surface, so none is left behind when the rule changes. The
     *  CGM panel does not call it: it shows [displayName] and [ordinalLabel] together. */
    fun incidentalName(showNames: Boolean): String = if (showNames) shortName else ordinalLabel()

    companion object {
        /** [ordinal] before storage has minted one. Negative, so `>= 0` is the whole "is it numbered". */
        const val UNASSIGNED_ORDINAL: Int = -1

        /**
         * How far the knob travels; the seed for a newly discovered sensor is a vendor constant in
         * that plugin. `0` means no warm-up: no `minFromStart` is below zero, so the heuristic
         * switches itself off rather than needing a flag.
         */
        val WARMUP_WINDOW_RANGE: IntRange = 0..360
    }
}
