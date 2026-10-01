package com.t1dm.core.model

/** Lifecycle state of a single CGM source (§3.1). */
enum class CgmSourceStatus {
    /** No session running. */
    Idle,

    /** Session starting; no valid record yet. */
    Scanning,

    /** Receiving readings still inside the warm-up window (values suppressed). */
    Warmup,

    /** Receiving fresh, valid, out-of-warmup readings. */
    Live,

    /** Link OK; sensor marks own records faulty, so none becomes a reading. Unlike [Scanning]. */
    Faulted,

    /** Was Live but no MEASURED reading has arrived within the loss-of-signal window. */
    SignalLost,
}

/** Never `status.name` — that prints the enum identifier. */
fun statusWord(status: CgmSourceStatus): String = when (status) {
    CgmSourceStatus.Idle -> "idle"
    CgmSourceStatus.Scanning -> "connecting"
    CgmSourceStatus.Warmup -> "warming up"
    CgmSourceStatus.Live -> "live"
    CgmSourceStatus.Faulted -> "sensor fault"
    CgmSourceStatus.SignalLost -> "signal lost"
}

/** Model id: display scope for one family; sourceId is the live authority, not this. */
object CgmSensorModelId {
    /** AiDEX X, the family of every pre-v11 row; other families keep their ids by their drivers. */
    const val AIDEX_X = "aidexx:x"

    /** Debug-injection class; reached only on a phone with no real sensor met yet. */
    const val AIDEX_DEBUG = "aidexx:debug"
}

/** CGM source identity (§3.1); matched by name/serial suffix, not BLE address (rotates). */
data class CgmSourceDescriptor(
    val id: CgmSourceId,
    val vendorId: String,          // owning plugin, e.g. "aidexx"
    val sensorModelId: String,     // sensor family; display scope ([CgmSensorModelId])
    val advertName: String?,       // what the sensor announced, verbatim; null = never recorded
    val displayName: String,       // e.g. "AiDEX X 00000T1DM0"
    val serialSuffix: String?,     // the name/serial suffix used to match adverts
    val warmupWindowMin: Int,      // seeded per vendor, then user-tunable; drives WARMUP
    val passiveOnly: Boolean,      // AiDEX X: false (connected GATT session is the sole read path)
    /** Display-only: hidden source stays on record, its readings stay in sensor-model history. */
    val hidden: Boolean = false,
    /** Zero-based, stable; [UNASSIGNED_ORDINAL] until minted on first write. Never positional. */
    val ordinal: Int = UNASSIGNED_ORDINAL,
) {
    /** [displayName] with [serialSuffix] stripped — "AiDEX X" rather than "AiDEX X 00000T1DM0". */
    val shortName: String
        get() {
            val serial = serialSuffix ?: return displayName
            val stripped = displayName.removeSuffix(serial).trimEnd()
            return stripped.ifEmpty { displayName }
        }

    /** Names sensor for incidental surfaces with a counter, not the serial, to avoid leaking it. */
    fun ordinalLabel(): String = if (ordinal >= 0) "CGM #$ordinal" else "CGM"

    /** Unused by the CGM panel, which shows [displayName] and [ordinalLabel] directly. */
    fun incidentalName(showNames: Boolean): String = if (showNames) shortName else ordinalLabel()

    /** Serial for incidental surfaces; masked it is the ordinal digit repeated, not the serial. */
    fun incidentalSerial(showNames: Boolean): String? = when {
        showNames -> serialSuffix
        ordinal >= 0 -> ordinal.toString().repeat(MASKED_SERIAL_LEN).take(MASKED_SERIAL_LEN)
        else -> null
    }

    companion object {
        /** Before storage mints an ordinal. Negative, so `>= 0` means numbered. */
        const val UNASSIGNED_ORDINAL: Int = -1

        /** Characters in a masked serial; fixed so a two-digit ordinal cannot widen the row. */
        const val MASKED_SERIAL_LEN: Int = 6

        /** Warm-up knob range, minutes; 0 disables it (minFromStart is never negative). */
        val WARMUP_WINDOW_RANGE: IntRange = 0..360
    }
}
