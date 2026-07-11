package com.t1dm.core.model

/**
 * The forward projection behind the circadian panel's insulin-exhaustion countdown. Each field is
 * an offset (hours) measured FROM THE PRIOR LANDMARK, not from now — DKA onset is
 * [iobZeroToDkaHours] after IOB decays to zero, coma is [dkaToComaHours] after DKA, death is
 * [comaToDeathHours] after coma — so the three chain into absolute instants only once anchored to
 * a concrete IOB-zero moment.
 *
 * DISPLAY-ONLY. This is a morbid, deliberately-untuned estimate the user opted into; **nothing in
 * the §3.6 fail-closed path reads it** — it never gates inference, never actuates, never raises a
 * clinical alarm. The defaults are the shipped [DEFAULT]; the user retunes them via the `death.*`
 * settings keys.
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
