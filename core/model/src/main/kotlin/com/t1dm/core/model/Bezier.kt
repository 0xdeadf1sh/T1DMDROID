package com.t1dm.core.model

import kotlin.math.ceil

/** Rate [y] (>= 0) at [xMin] minutes from the log instant. */
data class BezierPoint(val xMin: Double, val y: Double)

/** Pure shape; [sampleNormalized] area-normalises to total. C¹ Catmull-Rom Hermite, clamped ≥0. */
data class BezierCurve(
    val durationMin: Double,
    val points: List<BezierPoint>,
) {
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
        // Catmull-Rom tangents, scaled to the local span.
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

    /** ceil(durationMin/stepMin) buckets, midpoint rule, sum to [total]; zero if no area. */
    fun sampleNormalized(total: Double, stepMin: Double = 5.0): List<Double> {
        val n = ceil(durationMin / stepMin).toInt().coerceAtLeast(1)
        val raw = DoubleArray(n) { i -> valueAt((i + 0.5) * stepMin).coerceAtLeast(0.0) }
        val area = raw.sum()
        if (area <= 0.0) return List(n) { 0.0 }
        val scale = total / area
        return raw.map { it * scale }
    }

    /** No positive area; refuse plainly, don't save a silent zero (OOD, clinically meaningless). */
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

        fun default(durationMin: Double, peakFrac: Double = 0.30): BezierCurve = BezierCurve(
            durationMin = durationMin,
            points = listOf(
                BezierPoint(0.0, 0.0),
                BezierPoint(durationMin * peakFrac, 1.0),
                BezierPoint(durationMin * ((peakFrac + 1.0) / 2.0), 0.55),
                BezierPoint(durationMin, 0.0),
            ),
        )

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
