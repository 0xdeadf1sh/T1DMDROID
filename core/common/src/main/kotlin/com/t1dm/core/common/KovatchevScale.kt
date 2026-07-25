package com.t1dm.core.common

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The Kovatchev risk transform `f`/`f_inv` (INFERENCE.md §5, §11) as a pure-Kotlin mirror of the
 * Rust `kovatchev_f`/`kovatchev_f_inv` — guards and clamp order included.
 *
 * The crate stays the numeric authority: everything on the model pre/post path, the stats block
 * (LBGI/HBGI/ADRR compute `f` internally) and the graph/stats axes go on calling [NativeCore.kovatchevF].
 * This mirror exists for the DISPLAY chrome that cannot reach the JNI seam — the Glance tile's cached
 * and floor renders run precisely when the live container pull has already thrown, and a build made
 * without an NDK ships no `libt1dm_core.so` at all, so a formatter routed through the seam would
 * silently fall back to an unconverted mg/dL integer under a "risk" label. `KovatchevScaleTest`
 * cross-checks this copy against the crate's own golden fixture so the two cannot drift.
 */
object KovatchevScale {
    const val SCALE = 1.509
    const val POWER = 1.084
    const val OFFSET = 5.381

    /** Physical BG bounds; `f` clamps its input to these and `f_inv` its output. */
    const val BG_MIN = 20.0
    const val BG_MAX = 500.0

    /** `f(20)` ≈ −3.1629 — derived, never a literal, so the pair can never drift from [f]. */
    val RISK_MIN: Double = f(BG_MIN)

    /** `f(500)` ≈ +2.8133. */
    val RISK_MAX: Double = f(BG_MAX)

    /** `f(g) = SCALE·(ln(g)^POWER − OFFSET)`, mg/dL → risk. Total: BG is clamped to `[20, 500]`
     *  first (a positive `ln` base, hence a finite result) and NaN is treated as the low bound
     *  rather than propagated. The output is deliberately NOT clamped — it is total by construction. */
    fun f(mgdl: Double): Double {
        val g = if (mgdl.isNaN()) BG_MIN else mgdl.coerceIn(BG_MIN, BG_MAX)
        return SCALE * (ln(g).pow(POWER) - OFFSET)
    }

    /** `f_inv(r) = exp((r/SCALE + OFFSET)^(1/POWER))`, risk → mg/dL, with the §5 guards: non-finite
     *  risk is replaced (NaN/−∞ → [RISK_MIN], +∞ → [RISK_MAX]), the risk is clamped to
     *  `[RISK_MIN, RISK_MAX]` (keeping the base ≥ 0, so no NaN and no `exp` overflow), and the
     *  mg/dL result is clamped to `[20, 500]`. */
    fun fInv(risk: Double): Double {
        val r = when {
            risk.isNaN() || risk == Double.NEGATIVE_INFINITY -> RISK_MIN
            risk == Double.POSITIVE_INFINITY -> RISK_MAX
            else -> risk
        }.coerceIn(RISK_MIN, RISK_MAX)
        return exp((r / SCALE + OFFSET).pow(1.0 / POWER)).coerceIn(BG_MIN, BG_MAX)
    }
}
