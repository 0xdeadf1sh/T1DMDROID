package com.t1dm.core.model

/**
 * One CGM source's NON-glucose channels, as of its most recent record. Display and diagnosis only.
 *
 * The companion of [CgmReading] on the other axis: that one carries the number the whole app is built
 * around, this one carries everything else the sensor happened to say. Nothing here reaches a reading, a
 * forecast, a statistic, an alarm or the wire, and nothing is persisted — so a reader must treat it as a
 * live read-out that is absent before the first record and after the link drops, never as a record.
 *
 * **Every channel is nullable, and null means "this source cannot report it."** Not zero, not a dash: a
 * source read by passive advertisement carries no temperature and no battery at all, so a panel drawn
 * from this omits those rows for such a source rather than printing a figure nothing measured. This suite
 * prefers withholding a number to showing one it cannot justify.
 *
 * Units are the wire's own and unscaled, so no figure is rounded twice on its way to a display:
 * hundredths for a current, centi-degrees for a temperature, millivolts for an electrode, and the
 * vendor's own code where a code is what the sensor sent.
 */
data class CgmSourceTelemetry(
    /** When the record these came from was SAMPLED — the same clock and the same instant as the
     *  [CgmReading.rxWallMs] of the reading beside it, so the two can be read as one observation. */
    val sampledAtMs: Long,
    /** Skin/sensor temperature in centi-degrees Celsius. */
    val tempCx100: Int? = null,
    /** Transmitter battery as the wire reports it, unscaled — a raw figure, not a percentage. */
    val batteryRaw: Int? = null,
    /** Working-electrode current, hundredths. */
    val iwX100: Int? = null,
    /** Background current, hundredths. */
    val ibX100: Int? = null,
    /** Electrode potentials in mV, in the family's own order. */
    val electrodesMv: List<Int>? = null,
    /**
     * The vendor's error code, verbatim and uninterpreted.
     *
     * `0` is a REPORTED "no error" and not an absence — which is the whole reason this field exists
     * separately from the reading: a record whose code is non-zero is exactly the record whose reading
     * was withheld, so the code has to reach the display by a path the value gate does not sit on.
     */
    val errorCode: Int? = null,
    /**
     * The vendor's trend code — a small enum, not a rate. A rate belongs in
     * [CgmReading.trendTenthsPerMin] and is never derived from this.
     */
    val trendCode: Int? = null,
)
