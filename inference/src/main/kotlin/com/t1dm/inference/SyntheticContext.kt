package com.t1dm.inference

import kotlin.math.sin

/** Deterministic synthetic BG series, to exercise a cycle with no CGM present. */
object SyntheticContext {
    /** [steps] 5-minute samples, in a physiological mg/dL band. */
    fun plausible24h(steps: Int = 288, anchorTsMs: Long = System.currentTimeMillis()): BgSeries {
        val mgdl = DoubleArray(steps)
        var bg = 120.0
        for (i in 0 until steps) {
            val diurnal = 18.0 * sin(i / 46.0)
            val meal = if (i % 96 in 6..18) 35.0 * sin((i % 96 - 6) / 12.0 * Math.PI) else 0.0
            bg = (bg * 0.85 + (118.0 + diurnal + meal) * 0.15).coerceIn(60.0, 260.0)
            mgdl[i] = bg
        }
        val step = 300_000L
        val gridAnchor = Math.floorDiv(anchorTsMs, step) * step
        val gridStart = gridAnchor - (steps - 1).toLong() * step
        return BgSeries(mgdl, anchorTsMs = gridAnchor, gridStartMs = gridStart)
    }
}
