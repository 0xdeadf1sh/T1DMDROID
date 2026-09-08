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

    /** Linked, answering, but the sensor marks its own records faulty; distinct from Scanning. */
    Faulted,

    /** Was Live but no MEASURED reading has arrived within the loss-of-signal window. */
    SignalLost,
}

/** Sensor MODEL a source instances, scope of history (§3.1); DISPLAY only, never authority. */
object CgmSensorModelId {
    /** Only real sensor family the app reads today; the class every pre-v11 row belongs to. */
    const val AIDEX_X = "aidexx:x"

    /** Debug-injection source's class; keeps synthetic history off the first real sensor. */
    const val AIDEX_DEBUG = "aidexx:debug"
}

/** Stable, persisted identity+matching metadata (§3.1); matched by name/serial, never BLE. */
data class CgmSourceDescriptor(
    val id: CgmSourceId,
    val vendorId: String,          // owning plugin, e.g. "aidexx"
    val sensorModelId: String,     // sensor family, scope of displayed history.
    val advertName: String?,       // what the sensor announced, verbatim; null = never recorded
    val displayName: String,       // e.g. "AiDEX X 22222C74D9"
    val serialSuffix: String?,     // the name/serial suffix used to match adverts
    val warmupWindowMin: Int,      // seeded per vendor, tunable; drives WARMUP heuristic
    val passiveOnly: Boolean,      // AiDEX X: false (connected GATT session is the sole read path)
    /** Removed from sensor lists by user; DISPLAY flag, readings stay in BG panel history. */
    val hidden: Boolean = false,
    /** Zero-based stable number, UNASSIGNED till storage mints one at insert; never positional. */
    val ordinal: Int = UNASSIGNED_ORDINAL,
) {
    /** [displayName] with [serialSuffix] stripped — "AiDEX X" rather than "AiDEX X 22222C74D9". */
    val shortName: String
        get() {
            val serial = serialSuffix ?: return displayName
            val stripped = displayName.removeSuffix(serial).trimEnd()
            return stripped.ifEmpty { displayName }
        }

    /** For surfaces naming the sensor INCIDENTALLY; a counter avoids leaking the serial. */
    fun ordinalLabel(): String = if (ordinal >= 0) "CGM #$ordinal" else "CGM"

    /** One function per incidental surface; CGM panel shows displayName+ordinalLabel instead. */
    fun incidentalName(showNames: Boolean): String = if (showNames) shortName else ordinalLabel()

    companion object {
        /** ordinal before storage mints one. Negative, so >= 0 is the whole is-it-numbered. */
        const val UNASSIGNED_ORDINAL: Int = -1

        /** Knob's travel range; seed is a vendor constant; 0=no warm-up, minFromStart stays >=0. */
        val WARMUP_WINDOW_RANGE: IntRange = 0..360
    }
}
