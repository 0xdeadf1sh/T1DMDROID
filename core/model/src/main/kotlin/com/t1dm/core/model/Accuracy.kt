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
 * The Clarke Error Grid zone of one scored pair. The core's `clarke_zones` sets exactly one of its
 * five flags for any pair, so this is that partition named rather than a second reading of it.
 */
enum class ClarkeZone { A, B, C, D, E }

/**
 * The DTS Error Grid zone of one scored pair — Klonoff et al. 2024 (J Diabetes Sci Technol
 * 18(6):1346), the grid that supersedes the 2014 Surveillance Error Grid.
 *
 * A banding of the continuous risk the core computes, not a stack of inequalities like Clarke's.
 * [ScoredPoint.dtsRisk] is the underlying quantity; this is where it fell.
 */
enum class DtsZone { A, B, C, D, E }

/**
 * One scored `(prediction, truth)` pair in mg/dL carrying every zone verdict passed on it — the
 * per-point series behind [PointBlock]'s aggregate shares.
 *
 * [pred] is the BASIS's own prediction at the horizon step (band-projected in [HorizonMetrics.band],
 * the median line in [HorizonMetrics.medianLine]); [truth] the realized BG at that step. Plot the
 * truth on the reference axis: neither grid is symmetric, and a transposed one is a well-formed
 * picture of a different statistic.
 *
 * **One series, two grids.** The core classifies the same pair on both and returns it once, so two
 * scatters drawn on one screen are guaranteed to be plotting the same pairs — and half the marshalling.
 */
data class ScoredPoint(
    val pred: Double,
    val truth: Double,
    val clarke: ClarkeZone,
    val dts: DtsZone,
    /** Signed: positive where the forecast read HIGH of the truth, which the grid penalises harder. */
    val dtsRisk: Double,
)

/**
 * The per-horizon point-error block for ONE forecast basis (§6.2). `*Point` is the strict value at
 * the horizon step; `*Winmean` pools every step from 0 to that horizon. [clarkeAb] is the A∪B
 * share, not B alone. [skillPoint] is the fraction of the persistence baseline's RMSE removed, and
 * is null where persistence itself was perfect (an undefined ratio, never an infinity).
 *
 * The `dts*` shares are the same window on the 2024 DTS grid. [dtsA] is the paper's headline `pZA`,
 * and all five are published individually — **never combine [dtsA] and [dtsB]**: the paper's panel
 * explicitly declines to report a combined A+B and names `pZA` alone as the metric, which is the one
 * place this grid's presentation departs from Clarke's. [dtsMeanAbsRisk] is the mean `|risk|` the
 * zones band, the continuous reading that does not round a near-miss up into a whole zone.
 *
 * [points] is the per-point series every share was counted from, one entry per scored window. The
 * core classifies each pair ONCE on both grids and derives all of it from that single call, so a
 * scatter drawn from this series cannot disagree with the percentages printed beside it.
 *
 * **Carried on the MEDIAN LINE only; empty on the band**, by the core's contract. The band
 * projection is `clip(truth, lo, hi)`, so every pair whose truth fell inside the band lands on the
 * identity diagonal — Clarke zone A, and DTS risk exactly zero, which is zone A there too. A scatter
 * of it would picture band coverage as a flawless forecast. The band's grid performance is its
 * shares. Never fall back to the band's series for a scatter: it is empty because nothing may draw
 * it, not because nothing computed it.
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
    val dtsA: Double,
    val dtsB: Double,
    val dtsC: Double,
    val dtsD: Double,
    val dtsE: Double,
    val dtsMeanAbsRisk: Double,
    val skillPoint: Double?,
    val points: List<ScoredPoint>,
)

/**
 * The Trend Accuracy Matrix of one horizon — Klonoff et al. 2024, the same paper as the DTS grid.
 *
 * [counts] is the `5 × 5` contingency table over the core's rate bins, TRUTH-MAJOR: cell `(t, p)` is
 * truth bin `t` against forecast bin `p`, at index `t * TREND_BINS + p`. Use [countAt]; a transposed
 * read is well-formed and describes the opposite failure.
 *
 * [categoryPct] holds the paper's five risk categories at indices 0–4: no risk, underestimate,
 * overestimate, extreme underestimate, extreme overestimate. It is EMPTY when no pair scored — a
 * matrix of five zeroes would render as a partition of nothing, which is a different fact.
 *
 * `categoryPct[0]` is also the diagonal share: category 1 occupies exactly the diagonal in all three
 * of the paper's stratified tables, so exact bin agreement and "no risk" are one number here, not two.
 *
 * **Median line only.** There is no band counterpart, deliberately — §6.2's own consequence is that
 * the band projection equals the truth wherever the band covered, so a rate differenced from it
 * inherits the truth's derivative and would sit on the diagonal by construction.
 */
data class TrendMatrix(
    val counts: List<Int>,
    val categoryN: List<Int>,
    val categoryPct: List<Double>,
    val n: Int,
) {
    /** The count at truth bin [tb] against forecast bin [pb], or 0 off the table. */
    fun countAt(tb: Int, pb: Int): Int = counts.getOrElse(tb * TREND_BINS + pb) { 0 }

    /** The largest cell, for scaling a heatmap. 0 for an empty matrix. */
    val peak: Int get() = counts.maxOrNull() ?: 0

    val isEmpty: Boolean get() = n <= 0 || counts.size != TREND_BINS * TREND_BINS

    companion object {
        val EMPTY = TrendMatrix(List(TREND_BINS * TREND_BINS) { 0 }, List(TREND_CATEGORIES) { 0 }, emptyList(), 0)
    }
}

/**
 * Rate bins and risk categories in the Trend Accuracy Matrix. Structural counts only — the bin EDGES
 * live in the core and reach this side through `NativeCore.trendBinEdges()`, so no label drawn here
 * can come to disagree with the binning it captions.
 */
const val TREND_BINS: Int = 5
const val TREND_CATEGORIES: Int = 5

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
 * What a calibrated band is aiming at — the span of the levels its edges are drawn from, one per
 * band [HorizonMetrics] carries (`SPEC/invariants.md` §6.2: the metric band's target is
 * `METRIC_BAND_TAU_HI − METRIC_BAND_TAU_LO`, the outer envelope's the span between the fan's first
 * and last levels). The levels themselves live in `t1dm-core::accuracy`, which owns every scored
 * number; these are only what a DISPLAY compares a realized coverage against, and they are the same
 * spans [HorizonMetrics.bandCov50] / [HorizonMetrics.bandCov90] already name. §6.1 is explicit that
 * no descriptor ships these and that each consumer holds its own copy.
 */
const val BAND_COV50_TARGET: Double = 0.50
const val BAND_COV90_TARGET: Double = 0.90

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
    val trend: TrendMatrix,
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
 * A square lattice of error-grid zones over `[0, axisMaxMgdl]` on both axes, classified by the core.
 *
 * This is how an error grid gets its REGIONS without a second copy of the zone algebra. The
 * boundaries exist only inside the core — as inequalities for Clarke, as a log-ratio for the DTS
 * grid — and a renderer samples this lattice and paints the cells it is handed, so the outline it
 * draws is the classifier's own output rasterised. It cannot disagree with where the points land,
 * and the only thing separating the painted edge from the true one is [CELLS]. For the DTS grid the
 * argument is stronger still: its borders are level sets of a logarithm, so a renderer that wanted
 * to outline them would have to reimplement the function rather than copy four inequalities.
 *
 * **One class, both grids.** Zones are held as ORDINALS, so nothing here needs to know which grid it
 * came from; the figure that paints it supplies the letters and the colours. That is what lets the
 * run-length encoder and the letter placement be written once rather than per grid.
 *
 * Row-major and TRUTH-MAJOR: [ordinalAt] takes the truth index first, matching the core's declared
 * layout and the axis an error grid puts the reference on.
 */
class ZoneLattice private constructor(
    val axisMaxMgdl: Double,
    val cells: Int,
    /**
     * How many distinct zones this lattice actually contains — one past the largest ordinal in it.
     *
     * DERIVED from the classified cells, never taken from the caller. A consumer sizes per-zone
     * arrays from this and then indexes them with the very ordinals stored here ([zoneAnchors] does
     * exactly that), so a caller-supplied count that undershot would throw inside composition rather
     * than fail closed to [EMPTY] the way every other malformed input here does. Deriving it removes
     * the possibility instead of checking for it.
     */
    val zoneCount: Int,
    // Ordinals, not references: a 160-square lattice is 25 600 cells, and a whole instance is
    // long-lived (one serves every model). Identity equality is deliberate too — this is a
    // process-wide singleton, so a `remember` keyed on it must not walk 25 600 elements per
    // recomposition to conclude it is the same lattice it was.
    private val ordinals: ByteArray,
) {
    val isEmpty: Boolean get() = cells <= 0 || ordinals.size != cells * cells

    /** mg/dL at the centre of lattice index [i], on either axis. */
    fun coordAt(i: Int): Double = (i + 0.5) * axisMaxMgdl / cells

    /** Lattice index holding [mgdl], or null where it falls off the axis. */
    fun indexOf(mgdl: Double): Int? =
        ((mgdl / axisMaxMgdl) * cells).toInt().takeIf { mgdl >= 0.0 && it in 0 until cells }

    /** The zone ordinal at truth index [ti] against prediction index [pi]. */
    fun ordinalAt(ti: Int, pi: Int): Int = ordinals[ti * cells + pi].toInt()

    companion object {
        val EMPTY = ZoneLattice(0.0, 0, 0, ByteArray(0))

        /**
         * The extent both grids are drawn over, and the sensor's own ceiling.
         *
         * Clarke's conventional extent is exactly this. The DTS paper defines its grid to 600 mg/dL,
         * and cropping the view to 400 changes no classification — the classifier is unbounded and
         * the regions inside 400 are a true subset of the published ones. The two grids share the
         * extent so their scatters can be read against each other without rescaling.
         */
        const val AXIS_MAX_MGDL: Double = 400.0

        /** Cells per side — 2.5 mg/dL, under two pixels on any plot this app draws. */
        const val CELLS: Int = 160

        /**
         * Build the lattice by asking [classify] — `(truthAxis, predAxis) -> zones`, one of the
         * core's `*_zone_grid` exports. Nothing here knows a boundary; every zone in the result came
         * back across the seam.
         *
         * Fail-closed on anything unexpected: a short result, or a handful of ASYMMETRIC probes
         * whose transpose lands in a different zone disagreeing with the cells at their own
         * coordinates. The probes carry no expected answer — the classifier supplies it — so they
         * catch a swapped index here without restating a single inequality.
         */
        fun <Z : Enum<Z>> build(classify: (List<Double>, List<Double>) -> List<Z>): ZoneLattice {
            val axis = List(CELLS) { (it + 0.5) * AXIS_MAX_MGDL / CELLS }
            val zones = classify(axis, axis)
            val grid = of(AXIS_MAX_MGDL, CELLS, zones)
            if (grid.isEmpty) return EMPTY
            val probes = listOf(30.0 to 125.0, 210.0 to 60.0, 100.0 to 300.0).mapNotNull { (t, p) ->
                val ti = grid.indexOf(t) ?: return@mapNotNull null
                val pi = grid.indexOf(p) ?: return@mapNotNull null
                ti to pi
            }
            if (probes.size != 3) return EMPTY
            val direct = probes.map { (ti, pi) ->
                classify(listOf(grid.coordAt(ti)), listOf(grid.coordAt(pi))).singleOrNull()?.ordinal
            }
            val sampled = probes.map { (ti, pi) -> grid.ordinalAt(ti, pi) }
            return if (direct == sampled) grid else EMPTY
        }

        /** Wrap an already-classified lattice; a result that does not fill the square is refused
         *  whole, because a partial lattice paints regions that are wrong rather than absent. */
        fun <Z : Enum<Z>> of(axisMaxMgdl: Double, cells: Int, zones: List<Z>): ZoneLattice {
            if (cells <= 0 || zones.size != cells * cells) return EMPTY
            val ordinals = ByteArray(zones.size) { zones[it].ordinal.toByte() }
            // One pass over the bytes we just wrote, rather than a second over the boxed enums.
            var max = 0
            for (b in ordinals) if (b > max) max = b.toInt()
            return ZoneLattice(axisMaxMgdl, cells, max + 1, ordinals)
        }
    }
}

/** Both error-grid lattices, built once and shared by every model's drill-down. */
data class ErrorGridLattices(val clarke: ZoneLattice, val dts: ZoneLattice) {
    companion object {
        val EMPTY = ErrorGridLattices(ZoneLattice.EMPTY, ZoneLattice.EMPTY)
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
