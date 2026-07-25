package com.t1dm.core.model

/**
 * On-device forecast-accuracy domain types (Phase 7C) mirroring the Rust `t1dm-core::accuracy`
 * uniffi records one-to-one, so the Models drill-down renders realized accuracy without a
 * dependency on the ExecuTorch / native binding. The Kotlin data layer pairs each matured
 * `prediction` row with the realized `cgm_reading` at `made_at + h`; the Rust core does only the
 * arithmetic (RMSE/MAE/MARD + central-90 coverage). This is an accuracy statement about a
 * FORECAST — advisory only, never a dosing claim.
 */

/** One matured forecast↔realization pair at a single horizon (τ.05/τ.95 fan edges optional). */
data class AccuracyPair(
    val horizonMin: Int,
    val predicted: Double,
    val realized: Double,
    val bandLo: Double,
    val bandHi: Double,
    val hasBand: Boolean,
)

/**
 * Reduced accuracy at one horizon. [coverage90] is null when no matured pair at this horizon
 * carried a band; [sufficient] is `n >= minSamples` — the UI shows the metrics only when true,
 * else a plain "insufficient history" line (every empty state states WHY).
 */
data class HorizonAccuracy(
    val horizonMin: Int,
    val n: Int,
    val rmse: Double,
    val mae: Double,
    val mard: Double,
    val coverage90: Double?,
    val sufficient: Boolean,
)

/** The full per-horizon accuracy report, horizons ascending. */
data class AccuracyReport(
    val horizons: List<HorizonAccuracy>,
    val nPairs: Int,
    val minSamples: Int,
) {
    companion object {
        val EMPTY = AccuracyReport(emptyList(), 0, 0)
    }
}
