package com.t1dm.calc

import com.t1dm.core.common.KovatchevScale

/**
 * The Kovatchev risk INDEX, built on the scalar transform so the objective can score a forecast fan
 * without a model round-trip. The Rust `kovatchev_f` remains the numeric authority; `f` here is the
 * single pure-Kotlin mirror [KovatchevScale] (INFERENCE.md §5/§11), which is golden-gated against the
 * crate — this file adds only the index on top of it and never feeds a tensor.
 *
 * Risk `r = 10 · f²`, split into a low-BG branch (`f < 0` ⇒ LBGI) and a high-BG branch (`f > 0` ⇒
 * HBGI) — the standard Kovatchev decomposition. Hypo risk is scored off the lower band, hyper off the
 * median (SPEC § 5h-roll finding).
 */
object KovatchevRisk {

    /** `f(bg)` clamped to `[20, 500]`; NaN scores as the low bound, so a garbage BG reads as maximal
     *  hypo risk rather than as a risk-free `NaN < 0.0 == false`. */
    fun f(bgMgdl: Double): Double = KovatchevScale.f(bgMgdl)

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
