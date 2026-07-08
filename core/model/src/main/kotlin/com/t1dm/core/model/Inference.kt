package com.t1dm.core.model

/**
 * Model pre/post-processing types crossing the Rust `t1dm-core` seam (Phase 2,
 * INFERENCE.md §§6-8). These mirror the uniffi records one-to-one; `:core:native`
 * projects the generated `uniffi.t1dm_core.*` types onto these so downstream consumers
 * (`:inference`, `:calc`, `:alerts`) never depend on the binding directly.
 */

/** Per-channel normalization statistics (bg in risk space; carb/insulin in log1p space). */
data class ChannelStat(val mean: Double, val std: Double)

/**
 * The pre/post contract parsed from a model `descriptor.json` (PLAN §2.4) — the sole
 * source of the normalization stats plus the checkpoint-absent decode constants.
 */
data class ModelDescriptor(
    val bg: ChannelStat,
    val carb: ChannelStat,
    val insulin: ChannelStat,
    val ropeBase: Int,
    val medianGlobalDim: Int,
    val stepBasisType: String,
    val quantileSpreadMin: Double,
    val negFill: Double,
    val predictionHorizonHours: Int,
    val maxContextPatches: Int,
    val minContextPatches: Int,
    val patchSize: Int,
    val nInputFeatures: Int,
    val conformalEnabled: Boolean,
)

/**
 * The normalized model input built from a raw history, plus the mg/dL anchor.
 * [context] is the `nCtx·6·3` step-major-flattened normalized context; [pred] the
 * `P·6·3` prediction zone (BG feat 0 = literal 0; dose feats = normalize(0) or announced);
 * [lastBg] the persistence anchor. The backend left-pads [context] to the fixed T=52
 * artifact and builds the struct mask.
 */
data class BuiltContext(
    val nCtx: Int,
    val predictionPatches: Int,
    val context: List<Double>,
    val pred: List<Double>,
    val lastBg: Double,
)

/**
 * The decoded forecast, step-major over the P·S horizon (`i = p·S + s`). Risk-space
 * ([medianRisk]/[qTauRisk]) and the `f_inv` mg/dL projections ([medianBg]/[bandsMgdl]).
 */
data class Forecast(
    val medianRisk: List<Double>,
    val qTauRisk: List<Double>,
    val medianBg: List<Double>,
    val bandsMgdl: List<Double>,
)

/** Why a forecast is unfit to drive a rail/alert (§3.6-B). [OK] ⇒ eligible. */
enum class ForecastStatus { OK, NON_FINITE, RAIL_PINNED, COLLAPSED_BAND, MISORDERED_QUANTILES }
