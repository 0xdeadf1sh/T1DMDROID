package com.t1dm.core.common

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Kovatchev f/f_inv (§5/§11), Rust mirror for DISPLAY chrome; pinned by KovatchevScaleTest. */
object KovatchevScale {
    const val SCALE = 1.509
    const val POWER = 1.084
    const val OFFSET = 5.381

    /** Physical BG bounds; `f` clamps its input to these and `f_inv` its output. */
    const val BG_MIN = 20.0
    const val BG_MAX = 500.0

    /** `f(20)` ≈ −3.1629. */
    val RISK_MIN: Double = f(BG_MIN)

    /** `f(500)` ≈ +2.8133. */
    val RISK_MAX: Double = f(BG_MAX)

    /** mg/dL → risk; BG clamped [20,500] first (NaN = low bound); output NOT clamped. */
    fun f(mgdl: Double): Double {
        val g = if (mgdl.isNaN()) BG_MIN else mgdl.coerceIn(BG_MIN, BG_MAX)
        return SCALE * (ln(g).pow(POWER) - OFFSET)
    }

    /** risk → mg/dL (§5 guards): non-finite replaced, risk and result both clamped to bounds. */
    fun fInv(risk: Double): Double {
        val r = when {
            risk.isNaN() || risk == Double.NEGATIVE_INFINITY -> RISK_MIN
            risk == Double.POSITIVE_INFINITY -> RISK_MAX
            else -> risk
        }.coerceIn(RISK_MIN, RISK_MAX)
        return exp((r / SCALE + OFFSET).pow(1.0 / POWER)).coerceIn(BG_MIN, BG_MAX)
    }
}
