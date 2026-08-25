package com.t1dm.core.model

/** A rate [y] (>= 0, linear amount axis) at time [xMin] (min). */
data class PwlKnot(val xMin: Double, val y: Double)

/**
 * A pure shape: the knots' absolute [y] scale is irrelevant, because [sampleNormalized] area-
 * normalizes to a caller-supplied total. Knots are kept sorted by [PwlKnot.xMin]; the curve is
 * defined on `[0, durationMin]` and clamped to 0 outside the first and last knot. No x-snapping.
 */
data class PwlCurve(
    val durationMin: Double,
    val knots: List<PwlKnot>,
) {
    /** Value of the linear interpolant at [xMin]; 0 outside the knots. */
    fun valueAt(xMin: Double): Double {
        val ks = knots
        if (ks.isEmpty()) return 0.0
        if (xMin <= ks.first().xMin) return if (xMin < ks.first().xMin) 0.0 else ks.first().y
        if (xMin >= ks.last().xMin) return 0.0
        var i = 0
        while (i < ks.size - 1 && ks[i + 1].xMin < xMin) i++
        val a = ks[i]
        val b = ks[i + 1]
        val span = b.xMin - a.xMin
        if (span <= 0.0) return maxOf(a.y, b.y)
        val t = (xMin - a.xMin) / span
        return (a.y + (b.y - a.y) * t).coerceAtLeast(0.0)
    }

    /** `ceil(durationMin / stepMin)` buckets, each the midpoint-rule mean rate over its step, then
     *  scaled to sum to [total]. A zero-area curve yields all zeros. */
    fun sampleNormalized(total: Double, stepMin: Double = 5.0): List<Double> {
        val n = kotlin.math.ceil(durationMin / stepMin).toInt().coerceAtLeast(1)
        val raw = DoubleArray(n) { i ->
            val mid = (i + 0.5) * stepMin
            valueAt(mid).coerceAtLeast(0.0)
        }
        val area = raw.sum()
        if (area <= 0.0) return List(n) { 0.0 }
        val scale = total / area
        return raw.map { it * scale }
    }

    companion object {
        fun triangle(durationMin: Double, peakFrac: Double = 0.35): PwlCurve = PwlCurve(
            durationMin = durationMin,
            knots = listOf(
                PwlKnot(0.0, 0.0),
                PwlKnot(durationMin * peakFrac, 1.0),
                PwlKnot(durationMin, 0.0),
            ),
        )
    }
}
