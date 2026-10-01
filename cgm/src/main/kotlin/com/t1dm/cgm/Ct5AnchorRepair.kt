package com.t1dm.cgm

/** Recovers a wear's bind instant from arrival witnesses via MEDIAN (not mean), pure. */
object Ct5AnchorRepair {

    /** Enough that a stopped counter's later frames cannot be half the sample. */
    const val MIN_WITNESSES: Int = 32

    /** One arrival: when the frame reached the phone, and the minutes-since-start it carried. */
    data class Arrival(val rxWallMs: Long, val minFromStart: Int)

    /** [early]/[late]: equal means one anchor dates the whole wear; a gap means it can't. */
    data class Diagnosis(val anchor: Long, val witnesses: Int, val early: Long, val late: Long) {
        val skewMs: Long get() = late - early
    }

    fun diagnose(arrivals: List<Arrival>): Diagnosis? {
        val estimates = estimatesOf(arrivals)
        if (estimates.size < MIN_WITNESSES) return null
        val third = estimates.size / 3
        return Diagnosis(
            anchor = estimates[estimates.size / 2],
            witnesses = estimates.size,
            early = medianOfOldest(arrivals, third),
            late = medianOfNewest(arrivals, third),
        )
    }

    /** By ARRIVAL order, not estimate: asks whether the estimate moves over the wear. */
    private fun medianOfOldest(arrivals: List<Arrival>, n: Int): Long =
        estimatesOf(arrivals.sortedBy { it.rxWallMs }.take(n)).let { it[it.size / 2] }

    private fun medianOfNewest(arrivals: List<Arrival>, n: Int): Long =
        estimatesOf(arrivals.sortedBy { it.rxWallMs }.takeLast(n)).let { it[it.size / 2] }

    /** One estimate per index, earliest arrival: else a stopped index outvotes the wear. */
    private fun estimatesOf(arrivals: List<Arrival>): List<Long> = arrivals
        .filter { it.minFromStart >= 0 }
        .groupBy { it.minFromStart }
        .map { (minFromStart, sameIndex) ->
            sameIndex.minOf { it.rxWallMs } - minFromStart.toLong() * 60_000L
        }
        .sorted()

    /** Witnesses must agree within one sample interval, else no single anchor fits both. */
    const val MAX_AGREEMENT_SPREAD_MS: Long = 180_000L

    /** Null means DELETE NOTHING: too few witnesses, or they disagree, so no anchor is named. */
    fun anchorFrom(arrivals: List<Arrival>): Long? {
        val estimates = estimatesOf(arrivals)
        if (estimates.size < MIN_WITNESSES) return null
        val q1 = estimates[estimates.size / 4]
        val q3 = estimates[estimates.size * 3 / 4]
        if (q3 - q1 > MAX_AGREEMENT_SPREAD_MS) return null
        return estimates[estimates.size / 2]
    }
}
