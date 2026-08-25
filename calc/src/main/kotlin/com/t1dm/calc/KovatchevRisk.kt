package com.t1dm.calc

import com.t1dm.core.common.KovatchevScale

/** The index only; the Rust `kovatchev_f` is the numeric authority and [KovatchevScale] its single
 *  pure-Kotlin mirror (INFERENCE.md §5/§11). */
object KovatchevRisk {

    /** Clamped to `[20, 500]`; NaN scores as the low bound, so a garbage BG reads as maximal hypo
     *  risk rather than as a risk-free `NaN < 0.0 == false`. */
    fun f(bgMgdl: Double): Double = KovatchevScale.f(bgMgdl)

    fun risk(bgMgdl: Double): Double {
        val v = f(bgMgdl)
        return 10.0 * v * v
    }

    fun lbgi(bgMgdl: Double): Double {
        val v = f(bgMgdl)
        return if (v < 0.0) 10.0 * v * v else 0.0
    }

    fun hbgi(bgMgdl: Double): Double {
        val v = f(bgMgdl)
        return if (v > 0.0) 10.0 * v * v else 0.0
    }
}
