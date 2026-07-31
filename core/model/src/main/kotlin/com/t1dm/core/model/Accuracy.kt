package com.t1dm.core.model

/**
 * On-device forecast-accuracy domain types (Phase 7C) mirroring the Rust `t1dm-core::accuracy`
 * uniffi records one-to-one, so the Models drill-down renders realized accuracy without a
 * dependency on the ExecuTorch / native binding. The Kotlin data layer owns the PAIRING (walking
 * each stored `prediction` row forward to the realized `cgm_reading` at every step of its window);
 * the golden-gated Rust core owns every number. This is an accuracy statement about a FORECAST —
 * advisory only, never a dosing claim.
 *
 * The suite reproduces `T1DMAI/realdata/metrics.py::compute_suite`, so an on-device figure is
 * directly comparable to that project's validation table — provided the BASIS is named alongside
 * it (`SPEC/invariants.md` §6.2: a band-projected error and a median-line error are different
 * quantities measured on one forecast).
 *
 * **CG-EGA is the exception and is NOT comparable.** That project's `metrics.py` passes the two
 * trajectories to `cg_ega_counts` transposed, so the figures it publishes assign the glycaemic
 * region by the forecast rather than by the truth. Ours follow the documented order; see the
 * divergence note above the CG-EGA block in `t1dm-core::accuracy`. The two %AP/%BE/%EP sets differ
 * severalfold and a gap between them is not evidence about the exported model.
 */

/**
 * One matured forecast window, scored whole — the input record of `forecast_metrics_suite`.
 *
 * [bandsMgdl] is the full quantile fan, `steps × nQuantiles` row-major in ascending τ (exactly
 * [ModelPrediction.bandsMgdl]); [medianBg] and [realizedBg] one value per step; [lastBg] the
 * persistence anchor — the measured BG at the forecast's `made_at`, which is both the baseline
 * the skill score competes against and the step CG-EGA differences its first rate against
 * (`SPEC/invariants.md` §6.3).
 */
data class ForecastWindow(
    val bandsMgdl: List<Double>,
    val medianBg: List<Double>,
    val realizedBg: List<Double>,
    val lastBg: Double,
)

/**
 * The matured windows of one model over a query range, with what did NOT survive the walk.
 *
 * A window is scoreable only when the realized trajectory covers EVERY step (the suite scores one
 * rectangular `windows × steps` grid, and CG-EGA reads the whole window), so a single CGM gap
 * drops the whole forecast — as does a fan whose width disagrees with the rest of the set.
 * [nIncomplete] is how many were dropped that way, out of [nMatured] considered — carried so the
 * drill-down can say why a panel is empty rather than merely that it is.
 */
data class ForecastWindowSet(
    val windows: List<ForecastWindow>,
    val nMatured: Int,
    val nIncomplete: Int,
) {
    companion object {
        val EMPTY = ForecastWindowSet(emptyList(), 0, 0)
    }
}

/**
 * What the excursion detectors are scored against. `SPEC/invariants.md` §6.1 fixes which band EDGE
 * they read but deliberately not what it is compared to: that is the consumer's own threshold — the
 * patient's configurable bands here. [excursionPrecisionToleranceMgdl] forgives a near-boundary
 * false alarm whose edge lies within it of the true value; recall is strict regardless.
 */
data class MetricsConfig(
    val hypoThresholdMgdl: Double,
    val hyperThresholdMgdl: Double,
    val excursionPrecisionToleranceMgdl: Double,
    val minSamples: Int,
)

/**
 * The per-horizon point-error block for ONE forecast basis (§6.2). `*Point` is the strict value at
 * the horizon step; `*Winmean` pools every step from 0 to that horizon. [clarkeAb] is the A∪B
 * share, not B alone. [skillPoint] is the fraction of the persistence baseline's RMSE removed, and
 * is null where persistence itself was perfect (an undefined ratio, never an infinity).
 */
data class PointBlock(
    val rmsePoint: Double,
    val maePoint: Double,
    val rmseWinmean: Double,
    val maeWinmean: Double,
    val mard: Double,
    val clarkeA: Double,
    val clarkeAb: Double,
    val clarkeD: Double,
    val clarkeE: Double,
    val skillPoint: Double?,
)

/**
 * Band-edge recall / precision for one threshold crossing. [recall] is null when the truth never
 * crossed, [precision] when the forecast never called one — an undefined ratio, not a zero.
 */
data class ExcursionAccuracy(
    val recall: Double?,
    val precision: Double?,
    val nTrue: Int,
    val nPred: Int,
)

/**
 * Everything scored at one horizon. [band] is the HEADLINE — the band projection of §6.2 — and
 * [medianLine] the same block on the median. Because a wider band can only lower the error, a band
 * figure must never be shown without [bandCov50] and [bandWidth50] beside it: a band widened until
 * it swallows every truth scores a flawless zero, and those two are the only things that expose it.
 */
data class HorizonMetrics(
    val horizonMin: Int,
    val n: Int,
    val sufficient: Boolean,
    val band: PointBlock,
    val medianLine: PointBlock,
    val rmsePersistPoint: Double,
    val rmsePersistWinmean: Double,
    val bandCov50: Double,
    val bandWidth50: Double,
    val bandCov90: Double,
    val bandWidth90: Double,
    val hypo: ExcursionAccuracy,
    val hyper: ExcursionAccuracy,
)

/** CG-EGA for one glycaemic region: accurate / benign / erroneous share, null where the region
 *  held no points, beside the raw counts they came from. */
data class CgEgaRegion(
    val apPct: Double?,
    val bePct: Double?,
    val epPct: Double?,
    val nAp: Int,
    val nBe: Int,
    val nEp: Int,
) {
    val n: Int get() = nAp + nBe + nEp
}

/**
 * CG-EGA over the WHOLE forecast window (§6.3), one triple per region. Not per horizon: a CG-EGA
 * computed at a single horizon is a different statistic and must not be published under this name,
 * so nothing rendering this may label it with one.
 */
data class CgEga(
    val hypo: CgEgaRegion,
    val eu: CgEgaRegion,
    val hyper: CgEgaRegion,
)

/**
 * The full suite. [nWindows] counts the windows actually scored and [nRejected] those the core
 * dropped for a non-finite value or a mis-ordered fan. [cgega] is null when the caller did not ask
 * for it — an all-zero triple would read as "no points in any region", which is a different fact.
 */
data class MetricsSuite(
    val horizons: List<HorizonMetrics>,
    val cgega: CgEga?,
    val nWindows: Int,
    val nRejected: Int,
    val nSteps: Int,
) {
    companion object {
        val EMPTY = MetricsSuite(emptyList(), null, 0, 0, 0)
    }
}

/**
 * What the Models drill-down renders: the [suite] together with the walk that fed it, so an empty
 * panel can state WHY — no matured forecast at all, or forecasts whose realized trajectory had a
 * gap ([ForecastWindowSet.nIncomplete]).
 */
data class ModelMetrics(
    val suite: MetricsSuite,
    val nMatured: Int,
    val nIncomplete: Int,
    val minSamples: Int,
) {
    companion object {
        val EMPTY = ModelMetrics(MetricsSuite.EMPTY, 0, 0, 0)
    }
}
