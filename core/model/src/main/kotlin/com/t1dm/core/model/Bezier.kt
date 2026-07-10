package com.t1dm.core.model

import kotlin.math.ceil

/** One on-curve control point of a [BezierCurve]: a rate [y] (≥ 0, linear amount axis) at time
 *  [xMin] (minutes from the log instant). The user drags these; the curve interpolates smoothly. */
data class BezierPoint(val xMin: Double, val y: Double)

/**
 * A smooth cubic curve authored by the Bézier control-point editor (Phase 7D item 19
 * — "replace the piecewise-linear editor with a cubic Bézier control-point editor"). It is a pure
 * SHAPE: the absolute [y] scale is irrelevant because [sampleNormalized] area-normalises to a
 * caller-supplied dose total, exactly as the gamma / Bateman presets and the old [PwlCurve] did — so
 * it drops into the same `customCurve` BLOB pipeline (model-io-curves.md: carbs feed the model as an
 * appearance-rate curve, insulin as a PK-action-rate curve).
 *
 * The interpolant is a C¹ cubic Hermite spline with Catmull-Rom tangents through the (x-sorted)
 * control points — i.e. each span is a cubic Bézier segment — clamped to 0 outside the first/last
 * point and never negative. This yields the rounded, tension-free shape a Bézier editor implies while
 * keeping the drawn curve identical to the sampled one (both go through [valueAt]).
 */
data class BezierCurve(
    val durationMin: Double,
    val points: List<BezierPoint>,
) {
    /** The C¹ cubic-Hermite (Catmull-Rom) value at [xMin]; 0 before the first / after the last point. */
    fun valueAt(xMin: Double): Double {
        val p = points
        if (p.isEmpty()) return 0.0
        if (xMin <= p.first().xMin) return if (xMin < p.first().xMin) 0.0 else p.first().y.coerceAtLeast(0.0)
        if (xMin >= p.last().xMin) return 0.0
        var i = 0
        while (i < p.size - 1 && p[i + 1].xMin < xMin) i++
        val p1 = p[i]
        val p2 = p[i + 1]
        val h = p2.xMin - p1.xMin
        if (h <= 0.0) return maxOf(p1.y, p2.y).coerceAtLeast(0.0)
        val p0 = if (i > 0) p[i - 1] else p1
        val p3 = if (i + 2 < p.size) p[i + 2] else p2
        // Catmull-Rom tangents (secant of the neighbours), scaled to the local span.
        val m1 = tangent(p0, p2) * h
        val m2 = tangent(p1, p3) * h
        val t = (xMin - p1.xMin) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return (h00 * p1.y + h10 * m1 + h01 * p2.y + h11 * m2).coerceAtLeast(0.0)
    }

    /**
     * Sample into `ceil(durationMin / stepMin)` per-step buckets (midpoint rule) then scale so the
     * buckets sum to [total]. A degenerate (zero-area) curve yields all-zeros — the one transform from
     * an editor shape to the model-facing amount-per-5-min curve (identical contract to [PwlCurve]).
     */
    fun sampleNormalized(total: Double, stepMin: Double = 5.0): List<Double> {
        val n = ceil(durationMin / stepMin).toInt().coerceAtLeast(1)
        val raw = DoubleArray(n) { i -> valueAt((i + 0.5) * stepMin).coerceAtLeast(0.0) }
        val area = raw.sum()
        if (area <= 0.0) return List(n) { 0.0 }
        val scale = total / area
        return raw.map { it * scale }
    }

    /**
     * True when the shape encloses no positive area — [sampleNormalized] would yield all-zeros, so it
     * encodes no meal / dose. A degenerate curve must be REFUSED with a plain reason (never saved as a
     * silent zero curve): the model is trained on real appearance / action humps, and a flat input is
     * out-of-distribution as well as clinically meaningless.
     */
    fun isDegenerate(stepMin: Double = 5.0): Boolean {
        val n = ceil(durationMin / stepMin).toInt().coerceAtLeast(1)
        var area = 0.0
        for (i in 0 until n) area += valueAt((i + 0.5) * stepMin).coerceAtLeast(0.0)
        return area <= 0.0
    }

    companion object {
        private fun tangent(a: BezierPoint, b: BezierPoint): Double {
            val dx = b.xMin - a.xMin
            return if (dx <= 0.0) 0.0 else (b.y - a.y) / dx
        }

        /** A sensible default: a smooth hump rising from 0, peaking at [peakFrac], decaying to 0. */
        fun default(durationMin: Double, peakFrac: Double = 0.30): BezierCurve = BezierCurve(
            durationMin = durationMin,
            points = listOf(
                BezierPoint(0.0, 0.0),
                BezierPoint(durationMin * peakFrac, 1.0),
                BezierPoint(durationMin * ((peakFrac + 1.0) / 2.0), 0.55),
                BezierPoint(durationMin, 0.0),
            ),
        )

        /** Compact dependency-free serialisation `dur|x,y;x,y;…` for kv persistence (Settings). */
        fun encode(c: BezierCurve): String =
            "${c.durationMin}|" + c.points.joinToString(";") { "${it.xMin},${it.y}" }

        fun decode(s: String?): BezierCurve? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                val (durPart, ptsPart) = s.split("|", limit = 2)
                val dur = durPart.toDouble()
                val points = ptsPart.split(";").filter { it.isNotBlank() }.map {
                    val (x, y) = it.split(",")
                    BezierPoint(x.toDouble(), y.toDouble())
                }
                if (points.size < 2) null else BezierCurve(dur, points)
            }.getOrNull()
        }
    }
}
