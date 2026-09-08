package com.t1dm.core.model

/** Hours FROM THE PRIOR LANDMARK, not from now; display-only, never actuates or alarms. */
data class DkaTimeline(
    val iobZeroToDkaHours: Double,
    val dkaToComaHours: Double,
    val comaToDeathHours: Double,
) {
    companion object {
        val DEFAULT = DkaTimeline(2.0, 29.0, 59.0)
    }
}
