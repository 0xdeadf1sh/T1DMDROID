package com.t1dm.data.curve

/**
 * Maps a bout's DURATION onto the glucose-disposal gamma of `../T1DMCOMMON/SPEC/invariants.md` §5 —
 * the exercise counterpart of [GiToGamma], and a resolver only. The curve itself is built by the one
 * [CurveEngine.gamma] the carbohydrate channel already goes through; nothing here does arithmetic on
 * a curve.
 *
 * The channel is grams of carbohydrate EQUIVALENT per five-minute bucket — the carbohydrate the
 * disposal offsets — and never a duration, an intensity or an energy (§3). Two consequences are
 * worth stating outright, because both look like improvements:
 *
 *  - **Magnitude scales with duration ALONE.** Not with pace, heart rate, distance, or the ACSM
 *    kcal figure `ExerciseEnergy` computes beside it. Every model pretrained on
 *    `T1DMSIM` learnt the channel that way, so an intensity-scaled magnitude puts it
 *    off-distribution while still looking entirely plausible.
 *  - **The only tail here is the curve's own.** `T1DMSIM` also raises insulin sensitivity for six
 *    hours after a bout; §5 fixes that as a SEPARATE mechanism, and a consumer that folds it into
 *    this curve counts the same exercise twice.
 *
 * [K] and [THETA] are fixed, so the shape never changes — unlike the carbohydrate gamma, which the
 * glycaemic index bends. Only the total and the truncation window move with the bout's length.
 */
object ExerciseDisposal {

    /** §5's exercise gamma shape. Peak at `(k−1)·θ` = 30 min. */
    const val K: Double = 3.0
    const val THETA: Double = 15.0

    /** How far past the session's own length the curve runs, in minutes (§5's `duration_min + 90`). */
    const val TAIL_MIN: Double = 90.0

    /** Grams of carbohydrate equivalent disposed per minute of exercise. Per-patient: `T1DMSIM` uses
     *  a population constant of 0.5 and this phone defaults to the same, taking the patient's own
     *  value from `exercise.carb_equiv_per_min` where they have set one. */
    const val DEFAULT_CARB_EQUIV_PER_MIN: Double = 0.5

    /** Sanity rails on the per-patient value. This app's own bounds, not §5's — §5 fixes the default
     *  and says nothing about a range, so the slider picks one wide enough to be nobody's ceiling. */
    const val MIN_CARB_EQUIV_PER_MIN: Double = 0.1
    const val MAX_CARB_EQUIV_PER_MIN: Double = 1.5

    /** A resolved bout; feed to `CurveEngine.gamma(grams, k, theta, durationMin)`. */
    data class GammaParams(
        val grams: Double,
        val k: Double,
        val theta: Double,
        val durationMin: Double,
    )

    /**
     * The disposal gamma for a bout of [durationMin] minutes at [carbEquivPerMin] g/min.
     *
     * Total on hostile input rather than throwing: this is reached from a live recorder on every
     * grid boundary, and a bout that has recorded nothing yet has disposed of nothing. A rate
     * outside the rails is clamped to them, and a non-finite one falls back to the default — a
     * kv row can be hand-edited, and a NaN here would reach the model input as a NaN.
     */
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
