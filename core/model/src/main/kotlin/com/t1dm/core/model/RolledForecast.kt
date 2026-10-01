package com.t1dm.core.model

/** Own forecast window re-fed N times to 12h. DISPLAY-ONLY: never in predictions, calc, alerts. */
data class RolledForecast(
    val anchorTsMs: Long,
    val stepMs: Long,
    /** Step-major median BG (mg/dL) over the whole roll. */
    val medianBg: DoubleArray,
    /** The τ=.05 lower band edge (mg/dL) per step. */
    val lowerBg: DoubleArray,
    /** The τ=.95 upper band edge (mg/dL) per step. */
    val upperBg: DoubleArray,
    /** Whole fan, steps x nQuantiles ascending tau, bandsMgdl layout; EMPTY if no fan. */
    val bandsMgdl: DoubleArray = DoubleArray(0),
    /** The descriptor's VALIDATED window (0 = no model); past it extrapolated, never alerted on. */
    val validatedSteps: Int,
    /** The user-requested roll horizon in hours (30 min…12 h). */
    val requestedHours: Double,
    /** Display eligibility only — this NEVER authorises an alert or a dose. */
    val eligible: Boolean,
    /** [medianBg] then holds only the valid prefix. */
    val degenerate: Boolean,
    /** Null when fully eligible. */
    val reason: String?,
    /** The valid portion is `completedRolls · validatedSteps`. */
    val completedRolls: Int,
    /** 0 when no model sized the window. */
    val requestedRolls: Int,
) {
    val size: Int get() = medianBg.size
    val isEmpty: Boolean get() = medianBg.isEmpty()

    val horizonEndMs: Long get() = anchorTsMs + size.toLong() * stepMs

    val extrapolatedSteps: Int get() = (size - validatedSteps).coerceAtLeast(0)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RolledForecast) return false
        return anchorTsMs == other.anchorTsMs && stepMs == other.stepMs &&
            medianBg.contentEquals(other.medianBg) && lowerBg.contentEquals(other.lowerBg) &&
            upperBg.contentEquals(other.upperBg) && bandsMgdl.contentEquals(other.bandsMgdl) &&
            validatedSteps == other.validatedSteps &&
            requestedHours == other.requestedHours && eligible == other.eligible &&
            degenerate == other.degenerate && reason == other.reason &&
            completedRolls == other.completedRolls && requestedRolls == other.requestedRolls
    }

    override fun hashCode(): Int {
        var r = anchorTsMs.hashCode()
        r = 31 * r + medianBg.contentHashCode()
        r = 31 * r + validatedSteps
        r = 31 * r + requestedHours.hashCode()
        r = 31 * r + eligible.hashCode()
        r = 31 * r + degenerate.hashCode()
        return r
    }

    companion object {
        val NONE = RolledForecast(
            anchorTsMs = 0L, stepMs = 300_000L,
            medianBg = DoubleArray(0), lowerBg = DoubleArray(0), upperBg = DoubleArray(0),
            validatedSteps = 0, requestedHours = 0.0, eligible = false, degenerate = false,
            reason = null, completedRolls = 0, requestedRolls = 0,
        )

        /** A roll that could not be produced at all. */
        fun missing(requestedHours: Double, requestedRolls: Int, reason: String): RolledForecast =
            NONE.copy(requestedHours = requestedHours, requestedRolls = requestedRolls, reason = reason)
    }
}
