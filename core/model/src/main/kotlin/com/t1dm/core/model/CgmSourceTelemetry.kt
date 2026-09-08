package com.t1dm.core.model

/** NON-glucose channels, latest record; display/diagnosis, never persisted; null≠0; unscaled. */
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
    /** Vendor error code verbatim; `0` = REPORTED no-error; non-zero withholds the reading. */
    val errorCode: Int? = null,
    /** A small enum, not a rate; rate is [CgmReading.trendTenthsPerMin], never from this. */
    val trendCode: Int? = null,
)
