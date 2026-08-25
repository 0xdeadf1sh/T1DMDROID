package com.t1dm.data.curve

/**
 * Duration → the disposal gamma of `../T1DMCOMMON/SPEC/invariants.md` §5. Channel is grams of
 * carbohydrate EQUIVALENT per 5-min bucket; magnitude scales with duration alone, never intensity.
 * §5's post-bout insulin-sensitivity rise is a separate mechanism, not this curve's tail.
 */
object ExerciseDisposal {

    /** §5's shape. Peak at `(k−1)·θ` = 30 min. */
    const val K: Double = 3.0
    const val THETA: Double = 15.0

    /** Minutes past the bout's length; §5's `duration_min + 90`. */
    const val TAIL_MIN: Double = 90.0

    /** g/min. `T1DMSIM`'s population constant; overridden by `exercise.carb_equiv_per_min`. */
    const val DEFAULT_CARB_EQUIV_PER_MIN: Double = 0.5

    /** This app's rails, not §5's — §5 fixes the default and gives no range. */
    const val MIN_CARB_EQUIV_PER_MIN: Double = 0.1
    const val MAX_CARB_EQUIV_PER_MIN: Double = 1.5

    data class GammaParams(
        val grams: Double,
        val k: Double,
        val theta: Double,
        val durationMin: Double,
    )

    /** Clamps rather than throws: a hand-edited kv row's NaN would otherwise reach the model input. */
    fun paramsFor(
        durationMin: Double,
        carbEquivPerMin: Double = DEFAULT_CARB_EQUIV_PER_MIN,
    ): GammaParams {
        val minutes = if (durationMin.isFinite()) durationMin.coerceAtLeast(0.0) else 0.0
        val rate = if (carbEquivPerMin.isFinite()) {
            carbEquivPerMin.coerceIn(MIN_CARB_EQUIV_PER_MIN, MAX_CARB_EQUIV_PER_MIN)
        } else {
            DEFAULT_CARB_EQUIV_PER_MIN
        }
        return GammaParams(
            grams = minutes * rate,
            k = K,
            theta = THETA,
            durationMin = minutes + TAIL_MIN,
        )
    }
}
