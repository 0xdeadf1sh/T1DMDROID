package com.t1dm.core.model

/**
 * Split-conformal band recalibration, fitted on device: `SPEC/inference.md` §8.4.
 *
 * The median never moves. Every consumer that decides a category reads the raw fan; the calibrated
 * fan is display-only, reaches the BG panel alone, and is never stored or pushed — the wire has no
 * raw/calibrated discriminator.
 */

/**
 * [delta] is `steps · nQuantiles`, step-major in ascending τ — [ModelPrediction.bandsMgdl]'s
 * layout, so it applies without a transpose. All zeros when [sufficient] is false. The coverage
 * and width pairs are τ.05–.95 realized on the held-out [nEval] split.
 */
data class ConformalFit(
    val delta: List<Double>,
    val steps: Int,
    val nQuantiles: Int,
    val nWindows: Int,
    val nCal: Int,
    val nEval: Int,
    val nRejected: Int,
    val minCalWindows: Int,
    val sufficient: Boolean,
    val maxAbsDeltaMgdl: Double,
    val cov90Raw: Double?,
    val cov90Cal: Double?,
    val meanWidth90Raw: Double?,
    val meanWidth90Cal: Double?,
) {
    companion object {
        /** What the binding maps a core error to. */
        val NONE = ConformalFit(
            delta = emptyList(),
            steps = 0,
            nQuantiles = 0,
            nWindows = 0,
            nCal = 0,
            nEval = 0,
            nRejected = 0,
            minCalWindows = 0,
            sufficient = false,
            maxAbsDeltaMgdl = 0.0,
            cov90Raw = null,
            cov90Cal = null,
            meanWidth90Raw = null,
            meanWidth90Cal = null,
        )
    }
}

/** Only a sufficient fit is stored; a refusal leaves the previous correction untouched. */
data class BandCalibration(
    val modelId: String,
    val delta: List<Double>,
    val steps: Int,
    val nQuantiles: Int,
    val nCal: Int,
    val nEval: Int,
    val maxAbsDeltaMgdl: Double,
    val cov90Raw: Double?,
    val cov90Cal: Double?,
    val meanWidth90Raw: Double?,
    val meanWidth90Cal: Double?,
    val windowDays: Int,
    val fittedAtMs: Long,
    /** CGM source every window was scoped to. Null is unknown and never matches, so an unstamped
     *  correction stops being applied. */
    val sourceId: String? = null,
) {
    /** Trusted only as long as the history it was fitted on; past it, the raw fan. */
    val expiresAtMs: Long get() = fittedAtMs + windowDays.toLong() * 86_400_000L

    fun expiredAt(nowMs: Long): Boolean = nowMs >= expiresAtMs

    /** By the identity of the fit — one fit per model is in flight, so `(modelId, fittedAtMs)`
     *  decides it exactly and the boxed [delta] is never walked. Structural, not reference. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val o = other as? BandCalibration ?: return false
        return modelId == o.modelId && fittedAtMs == o.fittedAtMs
    }

    override fun hashCode(): Int = 31 * modelId.hashCode() + fittedAtMs.hashCode()
}

/** Conditions of the caller, not of the patient's history. */
enum class BandFitRefusal {
    /** A fit is already in flight; a second is refused outright, never queued. */
    BUSY,

    HORIZON_UNKNOWN,
}

/**
 * [refusal] non-null: the walk never ran, so [fit] and the counts are absent rather than zero.
 * Both null: the walk scored nothing. `fit.sufficient == false`: the fit's own refusal.
 */
data class BandCalibrationOutcome(
    val fit: ConformalFit?,
    val stored: Boolean,
    val nMatured: Int,
    val nIncomplete: Int,
    val refusal: BandFitRefusal? = null,
)
