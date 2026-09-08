package com.t1dm.core.model

/** Mirrors t1dm-core::accuracy; core owns every number. Not comparable to T1DMAI's metrics.py. */

/** bandsMgdl: steps x nQuantiles row-major ascending τ; lastBg anchors CG-EGA's first rate. */
data class ForecastWindow(
    val bandsMgdl: List<Double>,
    val medianBg: List<Double>,
    val realizedBg: List<Double>,
    val lastBg: Double,
)

/** nIncomplete of nMatured dropped for a gap/mis-sized fan; nForeignSource never entered. */
data class ForecastWindowSet(
    val windows: List<ForecastWindow>,
    val nMatured: Int,
    val nIncomplete: Int,
    val nForeignSource: Int = 0,
) {
    companion object {
        val EMPTY = ForecastWindowSet(emptyList(), 0, 0)
    }
}

/** SPEC/invariants.md §6.1 fixes the band edge; excursionPrecisionToleranceMgdl forgives it. */
data class MetricsConfig(
    val hypoThresholdMgdl: Double,
    val hyperThresholdMgdl: Double,
    val excursionPrecisionToleranceMgdl: Double,
    val minSamples: Int,
)

enum class ClarkeZone { A, B, C, D, E }

/** Klonoff et al. 2024, J Diabetes Sci Technol 18(6):1346. Bands [ScoredPoint.dtsRisk]. */
enum class DtsZone { A, B, C, D, E }

/** Plot truth on the reference axis; neither grid is symmetric, a transposed scatter misleads. */
data class ScoredPoint(
    val pred: Double,
    val truth: Double,
    val clarke: ClarkeZone,
    val dts: DtsZone,
    /** Signed: positive where the forecast read high of the truth. */
    val dtsRisk: Double,
)

/** §6.2: clarkeAb is A∪B; never combine dtsA with dtsB, paper reports pZA alone. */
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

/** Truth-major 5x5: cell (t,p) at t*TREND_BINS+p; categoryPct empty not zeroed when unscored. */
data class TrendMatrix(
    val counts: List<Int>,
    val categoryN: List<Int>,
    val categoryPct: List<Double>,
    val n: Int,
) {
    fun countAt(tb: Int, pb: Int): Int = counts.getOrElse(tb * TREND_BINS + pb) { 0 }

    val peak: Int get() = counts.maxOrNull() ?: 0

    val isEmpty: Boolean get() = n <= 0 || counts.size != TREND_BINS * TREND_BINS

    companion object {
        val EMPTY = TrendMatrix(List(TREND_BINS * TREND_BINS) { 0 }, List(TREND_CATEGORIES) { 0 }, emptyList(), 0)
    }
}

/** Structural counts only; the bin edges live in the core, via `NativeCore.trendBinEdges()`. */
const val TREND_BINS: Int = 5
const val TREND_CATEGORIES: Int = 5

/** Null where undefined: [recall] if the truth never crossed, [precision] if none was called. */
data class ExcursionAccuracy(
    val recall: Double?,
    val precision: Double?,
    val nTrue: Int,
    val nPred: Int,
)

/** What a display compares realized coverage against; no descriptor ships these (SPEC §6.1). */
const val BAND_COV50_TARGET: Double = 0.50
const val BAND_COV90_TARGET: Double = 0.90

/** band is the headline (§6.2); never show it without bandCov50/bandWidth50. */
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

/** Shares are null where the region held no points. */
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

/** Whole-window (§6.3), not per horizon — nothing rendering this may label it with one. */
data class CgEga(
    val hypo: CgEgaRegion,
    val eu: CgEgaRegion,
    val hyper: CgEgaRegion,
)

/** [cgega] is null when the caller did not ask for it, never an all-zero triple. */
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

/** Row-major and TRUTH-MAJOR: [ordinalAt] takes the truth index first. */
class ZoneLattice private constructor(
    val axisMaxMgdl: Double,
    val cells: Int,
    /** One past the largest ordinal present; derived from cells, never taken from the caller. */
    val zoneCount: Int,
    // Identity equality deliberate: process-wide singleton, keyed remember skips 25 600 cells.
    private val ordinals: ByteArray,
) {
    val isEmpty: Boolean get() = cells <= 0 || ordinals.size != cells * cells

    /** mg/dL at the centre of index [i]. */
    fun coordAt(i: Int): Double = (i + 0.5) * axisMaxMgdl / cells

    /** Lattice index holding [mgdl]; null off the axis. */
    fun indexOf(mgdl: Double): Int? =
        ((mgdl / axisMaxMgdl) * cells).toInt().takeIf { mgdl >= 0.0 && it in 0 until cells }

    fun ordinalAt(ti: Int, pi: Int): Int = ordinals[ti * cells + pi].toInt()

    companion object {
        val EMPTY = ZoneLattice(0.0, 0, 0, ByteArray(0))

        /** Clarke's conventional extent; cropping DTS's 600 changes no classification. */
        const val AXIS_MAX_MGDL: Double = 400.0

        /** Cells per side — 2.5 mg/dL, under two pixels on any plot this app draws. */
        const val CELLS: Int = 160

        /** classify is a core *_zone_grid export; fails closed to EMPTY on a bad result. */
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

        /** Refused whole unless it fills the square: a partial lattice paints wrong, not absent. */
        fun <Z : Enum<Z>> of(axisMaxMgdl: Double, cells: Int, zones: List<Z>): ZoneLattice {
            if (cells <= 0 || zones.size != cells * cells) return EMPTY
            val ordinals = ByteArray(zones.size) { zones[it].ordinal.toByte() }
            var max = 0
            for (b in ordinals) if (b > max) max = b.toInt()
            return ZoneLattice(axisMaxMgdl, cells, max + 1, ordinals)
        }
    }
}

data class ErrorGridLattices(val clarke: ZoneLattice, val dts: ZoneLattice) {
    companion object {
        val EMPTY = ErrorGridLattices(ZoneLattice.EMPTY, ZoneLattice.EMPTY)
    }
}

data class ModelMetrics(
    val suite: MetricsSuite,
    val nMatured: Int,
    val nIncomplete: Int,
    val minSamples: Int,
    /** Refused because the sensor that made them no longer holds authority. */
    val nForeignSource: Int = 0,
) {
    companion object {
        val EMPTY = ModelMetrics(MetricsSuite.EMPTY, 0, 0, 0)
    }
}
