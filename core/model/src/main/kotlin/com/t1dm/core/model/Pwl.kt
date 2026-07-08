package com.t1dm.core.model

/** One draggable knot of a [PwlCurve]: a rate [y] (>= 0, linear amount axis) at time [xMin] (min). */
data class PwlKnot(val xMin: Double, val y: Double)

/**
 * A piecewise-linear curve authored by the draggable-knot editor (PLAN.private.md Phase 4
 * deliverable 4 — "piecewise-linear draggable-knot editor, no snap, linear amount axis"). It is a
 * pure shape: the knots' absolute [y] scale is irrelevant because [sampleNormalized] area-
 * normalizes to a caller-supplied total. This is what backs a custom carb-appearance or insulin-
 * action override; the normalized per-step buckets are stored as the entity `customCurve` BLOB.
 *
 * Knots are kept sorted by [PwlKnot.xMin]; the curve is defined on `[0, durationMin]` and clamped
 * to 0 outside the first/last knot. There is deliberately NO x-snapping — the editor moves knots
 * freely and the sampler integrates the resulting trapezoids.
 */
data class PwlCurve(
    val durationMin: Double,
    val knots: List<PwlKnot>,
) {
    /** Value of the linear interpolant at [xMin] (0 before the first / after the last knot). */
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

    /**
     * Sample the curve into `ceil(durationMin / stepMin)` per-step buckets — each bucket the mean
     * rate over its `[t, t+stepMin)` interval (midpoint rule) — then scale so the buckets sum to
     * [total]. A degenerate (zero-area) curve yields all-zeros. This is the one transform from an
     * editor shape to the model-facing amount-per-5-min curve.
     */
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
        /** A sensible default shape (a single triangular hump) for a fresh custom curve of [durationMin]. */
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
