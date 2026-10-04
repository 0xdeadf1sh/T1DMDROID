package com.t1dm.feature.models

import com.t1dm.core.design.TableColumn
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.CgEgaRegion
import com.t1dm.core.model.ExcursionAccuracy
import com.t1dm.core.model.HorizonMetrics
import com.t1dm.core.model.PointBlock
import com.t1dm.core.model.TREND_CATEGORIES
import com.t1dm.core.model.TrendMatrix

/** One accuracy table as both the drill-down and the backtest PDF render it. */
internal class MetricTable(val columns: List<TableColumn>, val rows: List<List<String>>, val minWidth: Int = 320)

// Column order follows `T1DMAI/metrics/core/report.py::_suite_table`.

private fun col(header: String, weight: Float) = TableColumn(header, weight, numeric = true)

private const val WIDE = 980

/** §6.2: cov50/w50 share the row, keeping a widened band from reading flawless in the errors. */
internal fun bandTable(hs: List<HorizonMetrics>) = MetricTable(
    columns = listOf(
        TableColumn("h", 0.7f),
        col("RMSE pt", 1f), col("RMSE wm", 1f), col("MAE pt", 1f), col("MAE wm", 1f),
        col("MARD %", 1f), col("A %", 0.9f), col("A+B %", 1f), col("E %", 0.9f),
        col("cov50 %", 1f), col("w50", 0.9f), col("skill", 0.9f), col("n", 0.7f),
    ),
    rows = hs.map { h ->
        pointCells(h, h.band) + listOf(
            pct(h.bandCov50), f1(h.bandWidth50), skill(h.band), h.n.toString(),
        )
    },
    minWidth = WIDE,
)

/** §6.2 — a different quantity on one forecast, so a separate table. A line has no coverage. */
internal fun medianTable(hs: List<HorizonMetrics>) = MetricTable(
    columns = listOf(
        TableColumn("h", 0.7f),
        col("RMSE pt", 1f), col("RMSE wm", 1f), col("MAE pt", 1f), col("MAE wm", 1f),
        col("MARD %", 1f), col("A %", 0.9f), col("A+B %", 1f), col("E %", 0.9f),
        col("skill", 0.9f), col("n", 0.7f),
    ),
    rows = hs.map { h ->
        pointCells(h, h.medianLine) + listOf(skill(h.medianLine), h.n.toString())
    },
    minWidth = WIDE,
)

/** The persistence baseline both bases' skill is measured against. */
internal fun outerTable(hs: List<HorizonMetrics>) = MetricTable(
    columns = listOf(
        TableColumn("h", 0.8f),
        col("cov90 %", 1f), col("w90", 1f), col("persist pt", 1.2f), col("persist wm", 1.2f),
    ),
    rows = hs.map { h ->
        listOf(
            "${h.horizonMin}m", pct(h.bandCov90), f1(h.bandWidth90),
            f1(h.rmsePersistPoint), f1(h.rmsePersistWinmean),
        )
    },
)

/** §6.1. The denominators sit beside the ratios: 1.00 over one crossing is not 1.00 over forty. */
internal fun excursionTable(hs: List<HorizonMetrics>) = MetricTable(
    columns = listOf(
        TableColumn("h", 0.7f),
        col("hypo rec", 1.1f), col("hypo prec", 1.2f), col("hypo t/p", 1.1f),
        col("hyper rec", 1.2f), col("hyper prec", 1.3f), col("hyper t/p", 1.2f),
    ),
    rows = hs.map { h ->
        listOf("${h.horizonMin}m") + excursionCells(h.hypo) + excursionCells(h.hyper)
    },
    minWidth = 620,
)

/** No A+B: the panel that published the grid declined to report one; cov50/w50 share the row. */
internal fun dtsTable(hs: List<HorizonMetrics>) = MetricTable(
    columns = listOf(
        TableColumn("h", 0.8f),
        col("A %", 1f), col("B %", 1f), col("C %", 1f), col("D %", 1f), col("E %", 1f),
        col("|risk|", 1.1f), col("cov50 %", 1.1f), col("w50", 0.9f), col("n", 0.8f),
    ),
    rows = hs.map { h ->
        val b = h.band
        listOf(
            "${h.horizonMin}m",
            f1(b.dtsA), f1(b.dtsB), f1(b.dtsC), f1(b.dtsD), f1(b.dtsE),
            f3(b.dtsMeanAbsRisk), pct(h.bandCov50), f1(h.bandWidth50), h.n.toString(),
        )
    },
    minWidth = 760,
)

/** n is the matrix's own count, below the horizon's wherever the 15-min lookback did not reach. */
internal fun trendTable(hs: List<HorizonMetrics>) = MetricTable(
    columns = listOf(TableColumn("h", 0.8f)) +
        List(TREND_CATEGORIES) { col("${it + 1} %", 1f) } + listOf(col("n", 0.8f)),
    rows = hs.map { h -> listOf("${h.horizonMin}m") + trendCells(h.trend) },
    minWidth = 560,
)

/** §6.3 — the whole window, so no horizon column and no horizon in any label. */
internal fun cgEgaTable(cg: CgEga) = MetricTable(
    columns = listOf(
        TableColumn("region", 1f),
        col("AP %", 1f), col("BE %", 1f), col("EP %", 1f), col("n", 0.8f),
    ),
    rows = listOf(
        cgEgaCells("hypo", cg.hypo),
        cgEgaCells("eu", cg.eu),
        cgEgaCells("hyper", cg.hyper),
    ),
)

private fun trendCells(m: TrendMatrix): List<String> =
    List(TREND_CATEGORIES) { f1(m.categoryPct.getOrNull(it)) } + m.n.toString()

private fun pointCells(h: HorizonMetrics, b: PointBlock): List<String> = listOf(
    "${h.horizonMin}m",
    f1(b.rmsePoint), f1(b.rmseWinmean), f1(b.maePoint), f1(b.maeWinmean),
    f1(b.mard), f1(b.clarkeA), f1(b.clarkeAb), "%.2f".format(b.clarkeE),
)

private fun excursionCells(e: ExcursionAccuracy): List<String> =
    listOf(f2(e.recall), f2(e.precision), "${e.nTrue}/${e.nPred}")

private fun cgEgaCells(name: String, r: CgEgaRegion): List<String> =
    listOf(name, f1(r.apPct), f1(r.bePct), f1(r.epPct), r.n.toString())

internal fun f1(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.1f".format(v)

internal fun f2(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.2f".format(v)

private fun f3(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.3f".format(v)

private fun pct(v: Double): String = if (!v.isFinite()) "—" else "%.1f".format(v * 100)

private fun skill(b: PointBlock): String = f2(b.skillPoint)
