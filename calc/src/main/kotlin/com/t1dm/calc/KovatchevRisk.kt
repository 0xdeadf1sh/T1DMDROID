package com.t1dm.calc

import com.t1dm.core.common.KovatchevScale

/** Index; Rust `kovatchev_f` is authority, [KovatchevScale] its mirror (INFERENCE.md §5§11). */
object KovatchevRisk {

    /** Clamped `[20,500]`; NaN clamps low, so garbage BG reads max hypo, not `NaN<0.0==false`. */
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
