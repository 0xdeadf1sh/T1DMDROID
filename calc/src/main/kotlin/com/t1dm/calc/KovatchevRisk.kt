package com.t1dm.calc

import kotlin.math.ln
import kotlin.math.pow

/**
 * The Kovatchev blood-glucose risk transform (INFERENCE.md §5/§11), reimplemented here as a **pure
 * scalar** so the objective can score a forecast fan without a model round-trip. The Rust
 * `kovatchev_f` remains the numeric authority for the graph's unit space and the stats axis; this
 * mirror only weights mg/dL for dose selection and never feeds a tensor.
 *
 * `f(bg) = SCALE · (ln(bg)^POWER − OFFSET)`, clamped to `[20, 500]`. Risk `r = 10 · f²`, split into
 * a low-BG branch (`f < 0` ⇒ LBGI) and a high-BG branch (`f > 0` ⇒ HBGI) — the standard Kovatchev
 * decomposition. Hypo risk is scored off the lower band, hyper off the median (PLAN § 5h-roll finding).
 */
object KovatchevRisk {
    const val SCALE = 1.509
    const val POWER = 1.084
    const val OFFSET = 5.381
    const val BG_MIN = 20.0
    const val BG_MAX = 500.0

    fun f(bgMgdl: Double): Double {
        val bg = bgMgdl.coerceIn(BG_MIN, BG_MAX)
        return SCALE * (ln(bg).pow(POWER) - OFFSET)
    }

    /** Full risk `10·f²` (always ≥ 0). */
    fun risk(bgMgdl: Double): Double {
        val v = f(bgMgdl)
        return 10.0 * v * v
    }

    /** Low-BG risk component (0 when `bg` is at/above the risk-neutral point). */
    fun lbgi(bgMgdl: Double): Double {
        val v = f(bgMgdl)
        return if (v < 0.0) 10.0 * v * v else 0.0
    }

    /** High-BG risk component (0 when `bg` is at/below the risk-neutral point). */
    fun hbgi(bgMgdl: Double): Double {
        val v = f(bgMgdl)
        return if (v > 0.0) 10.0 * v * v else 0.0
    }
}
