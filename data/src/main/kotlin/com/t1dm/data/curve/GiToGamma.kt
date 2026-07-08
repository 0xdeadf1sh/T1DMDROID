package com.t1dm.data.curve

/**
 * Maps a food's glycemic index onto the carb **appearance (Ra)** gamma parameters
 * (PLAN.private.md Phase 4 — `GiToGamma`; [model-io-curves]). It is a thin, documented façade over
 * [CurveEngine.Presets.carbGammaForGi] so the meal builder has one named, testable entry point and
 * never hard-codes the simulator's central values.
 *
 * The intuition the mapping encodes: **high-GI ⇒ fast, high early peak** (juice, glucose — small
 * shape `k`, short scale `theta`), **low-GI ⇒ spread, later, flatter** (bread, pasta, legumes —
 * larger `k`/`theta`). The gamma peak is `(k-1)·theta`; [GammaParams.durationMin] gives ~5
 * mean-lives of tail so the curve integrates to the portion's carbs.
 */
object GiToGamma {

    /** Resolved carb-appearance gamma for a portion; feed to `CurveEngine.gamma(carbs, k, theta, durationMin)`. */
    data class GammaParams(val k: Double, val theta: Double, val durationMin: Double)

    /** GI used when a food's GI is unknown (`null`) — a medium-GI mixed-meal default. */
    const val DEFAULT_GI: Double = 50.0

    /** Params for an explicit glycemic index (0..100). */
    fun paramsForGi(gi: Double): GammaParams {
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(gi)
        return GammaParams(k, theta, dur)
    }

    /** Params for a possibly-unknown GI, substituting [DEFAULT_GI] for `null`. */
    fun paramsForGiOrDefault(gi: Double?): GammaParams = paramsForGi(gi ?: DEFAULT_GI)
}
