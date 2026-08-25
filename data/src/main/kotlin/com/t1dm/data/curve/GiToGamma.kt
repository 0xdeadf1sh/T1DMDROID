package com.t1dm.data.curve

object GiToGamma {

    /** [theta] and [durationMin] in minutes. */
    data class GammaParams(val k: Double, val theta: Double, val durationMin: Double)

    /** Medium-GI mixed meal. */
    const val DEFAULT_GI: Double = 50.0

    /** [gi] 0..100. */
    fun paramsForGi(gi: Double): GammaParams {
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(gi)
        return GammaParams(k, theta, dur)
    }

    fun paramsForGiOrDefault(gi: Double?): GammaParams = paramsForGi(gi ?: DEFAULT_GI)
}
