package com.t1dm.inference

import kotlin.math.sin

/**
 * A plausible synthetic 24 h, 5-min BG series for cold-start verification (session decision:
 * "COLD START … VERIFY with SYNTHETIC BG context (a plausible 24 h 5-min series; the sensor is NOT
 * needed)"). The real history is only minutes old at first run, so a debug `GridTick` feeds this
 * into [InferenceController.runCycle] to exercise the whole build_context → backend → assemble →
 * guard → overlay path with no CGM present. It is deterministic (seeded) so a run is reproducible.
 */
object SyntheticContext {
    /** 288 steps = 48 patches = 24 h. Gentle diurnal wave in a physiological mg/dL band. */
    fun plausible24h(steps: Int = 288, anchorTsMs: Long = System.currentTimeMillis()): BgSeries {
        val mgdl = DoubleArray(steps)
        var bg = 120.0
        for (i in 0 until steps) {
            // Slow diurnal drift + a couple of meal-like excursions, bounded to a sane range.
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
