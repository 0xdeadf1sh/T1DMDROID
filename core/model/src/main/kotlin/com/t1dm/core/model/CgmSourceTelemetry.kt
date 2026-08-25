package com.t1dm.core.model

/**
 * One CGM source's NON-glucose channels, as of its most recent record. Display and diagnosis only,
 * never persisted — absent before the first record and after the link drops. Null means the source
 * cannot report that channel, never zero. Units are the wire's own, unscaled.
 */
data class CgmSourceTelemetry(
    /** The same clock and the same instant as the [CgmReading.rxWallMs] beside it. */
    val sampledAtMs: Long,
    /** Skin/sensor temperature in centi-degrees Celsius. */
    val tempCx100: Int? = null,
    /** Transmitter battery as the wire reports it — raw, not a percentage. */
    val batteryRaw: Int? = null,
    /** Working-electrode current, hundredths. */
    val iwX100: Int? = null,
    /** Background current, hundredths. */
    val ibX100: Int? = null,
    /** Electrode potentials in mV, in the family's own order. */
    val electrodesMv: List<Int>? = null,
    /**
     * The vendor's error code, verbatim. `0` is a REPORTED "no error", not an absence: a record
     * whose code is non-zero is exactly the record whose reading was withheld, so it reaches the
     * display by a path the value gate does not sit on.
     */
    val errorCode: Int? = null,
    /** A small enum, not a rate. A rate is [CgmReading.trendTenthsPerMin] and is never derived
     *  from this. */
    val trendCode: Int? = null,
)
