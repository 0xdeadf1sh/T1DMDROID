package com.t1dm.core.model

/**
 * Each field is hours FROM THE PRIOR LANDMARK, not from now.
 * Display-only: nothing in the fail-closed path reads it, and it never actuates or alarms.
 */
data class DkaTimeline(
    val iobZeroToDkaHours: Double,
    val dkaToComaHours: Double,
    val comaToDeathHours: Double,
) {
    companion object {
        val DEFAULT = DkaTimeline(2.0, 29.0, 59.0)
    }
}
