package com.t1dm.app.stats

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.AgpBin
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.HeatCell
import com.t1dm.core.model.HeatStat
import com.t1dm.core.model.EpisodeSummary
import com.t1dm.core.model.StatsComposite
import com.t1dm.core.model.SubBands
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import androidx.compose.ui.graphics.toArgb
import com.t1dm.feature.stats.DAY_LABELS
import com.t1dm.feature.stats.HEATMAP_HOURS
import com.t1dm.feature.stats.HEAT_HIGH
import com.t1dm.feature.stats.HEAT_IN
import com.t1dm.feature.stats.HEAT_LOW
import com.t1dm.feature.stats.heatColor
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Renders a [StatsComposite] to a multi-page A4 PDF (item 6 — "Export to PDF") using the platform
 * [PdfDocument] (no dependency). The report is drawn on a WHITE page regardless of the active theme
 * so it prints legibly, and every BG level is in mg/dL (the canonical space the stats are computed
 * in) — the active unit space is stated in the header for provenance. A [Pager] auto-breaks pages,
 * stamping the advisory footer + page number on each. The caller streams it to a SAF-chosen file.
 */
object StatsPdf {
    private const val W = 595 // A4 @ 72 dpi
    private const val H = 842
    private const val M = 40f
    private const val FOOT = 34f

    /**
     * [cuts] anchors the heatmap's colour ramp. [ClinicalCuts.UNAVAILABLE] omits the heatmap sections
     * rather than printing a grid cut in the wrong place — the same refusal the screen makes.
     */
    fun write(out: OutputStream, c: StatsComposite, cuts: ClinicalCuts) {
        val doc = PdfDocument()
        val p = Pager(doc)
        val s = c.local

        p.title("T1DM — Glucose statistics report")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        p.body(
            "Window ${c.window.wire}   ·   Target ${c.targetRange.lowMgdl}–${c.targetRange.highMgdl} mg/dL   ·   " +
                "Units ${unitName(c.unitSpace)}   ·   Generated $stamp",
        )
        p.body("n = ${s.nSamples} readings over ${d(s.spanMs / 86_400_000.0, 1)} days.  Levels below are in mg/dL.")
        p.gap(4f)

        // ── Time in range ──
        p.section("Time in range")
        p.tirStrip(s.subBands)
        p.kv("Below / In range / Above", "${pct(s.tbr)}  /  ${pct(s.tir)}  /  ${pct(s.tar)}")
        p.kv("Very low <54 / low", "${pct(s.subBands.veryLow)}  /  ${pct(s.subBands.low)}")
        p.kv("High / very high >250", "${pct(s.subBands.high)}  /  ${pct(s.subBands.veryHigh)}")

        // ── AGP ──
        if (s.agp.isNotEmpty()) {
            p.section("Ambulatory glucose profile")
            p.caption("Median (line) with the 25–75 % and 5–95 % percentile bands across the day.")
            p.agpChart(s.agp, c.targetRange)
        }

        // ── Glycemic metrics ──
        p.section("Glycemic metrics")
        p.kv("Mean BG", "${d(s.meanBg, 0)} mg/dL")
        p.kv("GMI (est. HbA1c)", "${d(s.gmi, 1)} %")
        p.kv("CV / SD", "${d(s.cv, 1)} %  /  ${d(s.sd, 0)} mg/dL")
        p.kv("LBGI / HBGI", "${d(s.lbgi, 1)}  /  ${d(s.hbgi, 1)}")
        p.kv("MAGE", "${d(s.mage, 0)} mg/dL")
        p.kv("GRI (glycemia risk index)", d(gri(s.subBands), 0))

        // ── Variability & risk ──
        p.section("Variability & risk")
        p.kv("MODD", "${d(s.modd, 0)} mg/dL")
        p.kv("CONGA 1h / 2h / 4h", "${d(s.conga1, 0)} / ${d(s.conga2, 0)} / ${d(s.conga4, 0)} mg/dL")
        p.kv("Day-to-day SD", "${d(s.dtdSd, 0)} mg/dL")
        p.kv("J-index / M-value", "${d(s.jIndex, 1)}  /  ${d(s.mValue, 1)}")
        p.kv("ADRR", d(s.adrr, 1))
        p.kv("GRADE", "${d(s.grade.grade, 1)}   (hypo ${pct(s.grade.hypo)} · eu ${pct(s.grade.eu)} · hyper ${pct(s.grade.hyper)})")

        // ── Diurnal TIR ──
        if (s.tod.any { it.n > 0 }) {
            p.section("Time in range by time of day")
            s.tod.forEach { b ->
                if (b.n > 0) {
                    p.kv(todLabel(b.startMin), "in ${pct(b.tir)} · below ${pct(b.tbr)} · above ${pct(b.tar)}  (n=${b.n})")
                }
            }
        }

        // ── Weekday × hour heatmaps ──
        // Both summaries, because the export is read away from the app where the chip cannot be
        // flipped: the median is what that hour usually looks like, the mean is what it averages to,
        // and a pair of cells that disagree is itself the finding. Median first, matching the screen's
        // default. LOCAL time, unlike the diurnal card above it — see stats.rs's day-boundary block.
        if (s.heatmap.isNotEmpty() && cuts.isUsable) {
            p.section("Glucose by day and hour")
            p.caption("Local time. Colour runs blue (low) → green (in range) → red (high); an outlined cell has no reading.")
            p.heatLegend(c.targetRange, cuts)
            HeatStat.entries.forEach { stat ->
                p.heatGrid(s.heatmap, stat, c.targetRange, cuts)
            }
        }

        // ── Glucose distribution ──
        if (s.histogram.any { it.count > 0 }) {
            p.section("Glucose distribution")
            p.caption("Share of readings per 20 mg/dL band (40–400).")
            p.histogram(s, c.targetRange)
        }

        // ── Episodes ──
        p.section("Excursion episodes")
        p.episode("Hypoglycemia (< ${c.targetRange.lowMgdl})", s.hypoEpisodes, isHypo = true)
        p.episode("Hyperglycemia (> ${c.targetRange.highMgdl})", s.hyperEpisodes, isHypo = false)
        if (s.hypoEpisodes.count == 0 && s.hyperEpisodes.count == 0) {
            p.caption("No sustained excursions (≥2 consecutive readings) outside the target range in this window.")
        }

        // ── Treatments ──
        val hasTx = s.totalCarbs > 0 || s.totalBolus > 0 || s.totalBasal > 0 || s.meanSteps != null
        if (hasTx) {
            p.section("Treatments & activity")
            if (s.totalCarbs > 0) p.kv("Carbs / day", "${d(s.meanDailyCarbs, 0)} g")
            if (s.totalBolus > 0 || s.totalBasal > 0) p.kv("TDD", "${d(s.tdd, 1)} U/day")
            if (s.totalBasal > 0) p.kv("Bolus : basal", d(s.bolusBasalRatio, 2))
            s.meanSteps?.let { p.kv("Steps", "${d(it, 0)} /5min") }
            s.mood?.let { p.kv("Mood", "${d(it.mean, 1)} (n=${it.n})") }
        }

        // ── Server cross-check ──
        c.server?.let { sv ->
            p.section("Server cache (cross-check)")
            p.kv("Server mean / GMI / CV", "${d(sv.meanBg, 0)}  /  ${d(sv.gmi, 1)}%  /  ${d(sv.cv, 1)}%")
            p.kv("Local recompute", "${d(s.meanBg, 0)}  /  ${d(s.gmi, 1)}%  /  ${d(s.cv, 1)}%")
        }

        p.finish(out)
    }

    // ── the paginator: owns the current page/canvas, y-cursor, and page breaks ──
    private class Pager(private val doc: PdfDocument) {
        private var pageNo = 0
        private lateinit var page: PdfDocument.Page
        private lateinit var cv: Canvas
        private var y = 0f

        private val titleP = paint(Color.BLACK, 20f, bold = true)
        private val sectionP = paint(Color.rgb(30, 60, 100), 13f, bold = true)
        private val keyP = paint(Color.DKGRAY, 11f)
        private val valP = paint(Color.BLACK, 11f)
        private val capP = paint(Color.GRAY, 9.5f)
        private val footP = paint(Color.GRAY, 9f)
        private val axisP = paint(Color.GRAY, 8.5f)

        init { newPage() }

        private fun newPage() {
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            cv = page.canvas
            cv.drawColor(Color.WHITE)
            y = M + 8f
        }

        private fun need(h: Float) {
            if (y + h > H - FOOT) {
                stampFooter()
                doc.finishPage(page)
                newPage()
            }
        }

        private fun stampFooter() {
            cv.drawText(
                "Personal advisory tool — NOT medical advice. Computed locally from CGM history.",
                M, H - 18f, footP,
            )
            cv.drawText("Page $pageNo", W - M - 40f, H - 18f, footP)
        }

        fun title(t: String) { cv.drawText(t, M, y, titleP); y += 22f }
        fun body(t: String) { need(16f); cv.drawText(t, M, y + 12f, keyP); y += 15f }
        fun caption(t: String) { need(14f); cv.drawText(t, M, y + 10f, capP); y += 13f }
        fun gap(h: Float) { y += h }

        fun section(t: String) {
            need(30f)
            y += 16f
            cv.drawText(t, M, y, sectionP)
            cv.drawLine(M, y + 4f, W - M, y + 4f, axisP)
            y += 12f
        }

        fun kv(k: String, v: String) {
            need(16f)
            y += 15f
            cv.drawText(k, M, y, keyP)
            cv.drawText(v, W / 2f - 30f, y, valP)
        }

        fun tirStrip(b: SubBands) {
            need(26f)
            y += 8f
            val x0 = M; val w = W - 2 * M; val bh = 16f
            val segs = listOf(
                b.veryLow to Color.rgb(0xB7, 0x1C, 0x1C),
                b.low to Color.rgb(0xF5, 0x7C, 0x00),
                b.inRange to Color.rgb(0x2E, 0x7D, 0x32),
                b.high to Color.rgb(0xF9, 0xA8, 0x25),
                b.veryHigh to Color.rgb(0xC6, 0x28, 0x28),
            )
            val total = segs.sumOf { it.first }.takeIf { it > 0 } ?: 1.0
            var x = x0
            val fill = Paint().apply { isAntiAlias = true }
            segs.forEach { (frac, col) ->
                val sw = (frac / total * w).toFloat()
                if (sw > 0) { fill.color = col; cv.drawRect(x, y, x + sw, y + bh, fill); x += sw }
            }
            y += bh + 2f
        }

        /**
         * The 7×24 grid for one summary. Cells are sized off the page width, so the whole week fits
         * a single A4 column without a second page break mid-grid; an unmeasured cell is outlined
         * rather than filled, exactly as on screen, so a gap can never read as a glucose value.
         */
        fun heatGrid(cells: List<HeatCell>, stat: HeatStat, target: TargetRange, cuts: ClinicalCuts) {
            val labelW = 26f
            val gridW = W - 2 * M - labelW
            val cw = gridW / HEATMAP_HOURS
            val rh = 13f
            val gh = rh * DAY_LABELS.size
            // The caption is claimed by the SAME need() as the grid, so a page break can never land
            // between them and strand a heading at the foot of a page.
            need(gh + 40f)
            cv.drawText(
                if (stat == HeatStat.Median) "Median per weekday and hour" else "Mean per weekday and hour",
                M, y + 10f, capP,
            )
            y += 17f
            val top = y
            // Scattered into the dense lattice once; the same NaN sentinel the screen uses for absent.
            val grid = FloatArray(DAY_LABELS.size * HEATMAP_HOURS) { Float.NaN }
            for (cell in cells) {
                if (cell.dow in DAY_LABELS.indices && cell.hour in 0 until HEATMAP_HOURS) {
                    grid[cell.dow * HEATMAP_HOURS + cell.hour] = cell.value(stat).toFloat()
                }
            }
            val fill = Paint().apply { isAntiAlias = true }
            val outline = Paint().apply { style = Paint.Style.STROKE; color = Color.LTGRAY; isAntiAlias = true }
            for (dRow in DAY_LABELS.indices) {
                val ry = top + dRow * rh
                cv.drawText(DAY_LABELS[dRow], M, ry + rh - 3f, axisP)
                for (h in 0 until HEATMAP_HOURS) {
                    val cx = M + labelW + h * cw
                    val v = grid[dRow * HEATMAP_HOURS + h]
                    if (v.isNaN()) {
                        cv.drawRect(cx + 0.5f, ry + 0.5f, cx + cw - 0.5f, ry + rh - 0.5f, outline)
                    } else {
                        fill.color = heatColor(v.toDouble(), target, cuts).toArgb()
                        cv.drawRect(cx + 0.5f, ry + 0.5f, cx + cw - 0.5f, ry + rh - 0.5f, fill)
                    }
                }
            }
            for (h in 0..HEATMAP_HOURS step 3) {
                cv.drawText(h.toString(), M + labelW + h * cw - 4f, top + gh + 9f, axisP)
            }
            y = top + gh + 14f
        }

        /** The ramp with its four anchors named, so the grids are readable without the app. */
        fun heatLegend(target: TargetRange, cuts: ClinicalCuts) {
            need(26f)
            y += 4f
            val lo = target.lowMgdl.toDouble()
            val hi = target.highMgdl.toDouble()
            val floor = minOf(cuts.veryLowMgdl, lo)
            val ceil = maxOf(cuts.veryHighMgdl, hi)
            val span = (ceil - floor).takeIf { it > 0.0 } ?: 1.0
            val x0 = M + 26f
            val w = W - 2 * M - 26f
            val bh = 7f
            // Sampled per pixel column through the SAME `heatColor` the screen and the grids use, so
            // the key cannot drift from what it is a key to.
            val fill = Paint().apply { isAntiAlias = false }
            var px = 0
            while (px < w.toInt()) {
                fill.color = heatColor(floor + (px / w) * span, target, cuts).toArgb()
                cv.drawRect(x0 + px, y, x0 + px + 1f, y + bh, fill)
                px++
            }
            listOf(floor, lo, hi, ceil).forEach { v ->
                val x = x0 + ((v - floor) / span * w).toFloat()
                cv.drawText(d(v, 0), (x - 6f).coerceIn(M, W - M - 18f), y + bh + 9f, axisP)
            }
            y += bh + 14f
        }

        fun agpChart(agp: List<AgpBin>, target: TargetRange) {
            val ch = 170f
            need(ch + 16f)
            y += 6f
            val x0 = M; val w = W - 2 * M; val top = y; val bot = y + ch
            val loBg = 40.0
            val hiBg = maxOf(300.0, ceil(agp.maxOf { it.p95 } / 50.0) * 50.0)
            fun xAt(minute: Int) = x0 + (minute / 1440f) * w
            fun yAt(bg: Double) = (bot - ((bg - loBg) / (hiBg - loBg) * ch)).toFloat().coerceIn(top, bot)

            val bandPaint = Paint().apply { color = Color.rgb(0xE8, 0xF5, 0xE9); isAntiAlias = true }
            cv.drawRect(x0, yAt(target.highMgdl.toDouble()), x0 + w, yAt(target.lowMgdl.toDouble()), bandPaint)
            cv.drawRect(x0, top, x0 + w, bot, Paint().apply { style = Paint.Style.STROKE; color = Color.LTGRAY })

            ribbon(agp, ::xAt, ::yAt, { it.p5 }, { it.p95 }, Color.argb(60, 0x42, 0x85, 0xF4))
            ribbon(agp, ::xAt, ::yAt, { it.p25 }, { it.p75 }, Color.argb(110, 0x42, 0x85, 0xF4))
            val med = Path()
            agp.forEachIndexed { i, b ->
                val px = xAt(b.minuteOfDay); val py = yAt(b.p50)
                if (i == 0) med.moveTo(px, py) else med.lineTo(px, py)
            }
            cv.drawPath(med, Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(0x15, 0x65, 0xC0); isAntiAlias = true })

            listOf(loBg, target.lowMgdl.toDouble(), target.highMgdl.toDouble(), hiBg).forEach {
                cv.drawText(d(it, 0), x0 - 20f, yAt(it) + 3f, axisP)
            }
            listOf(0, 360, 720, 1080, 1440).forEach {
                cv.drawText("${it / 60}h", xAt(it) - 6f, bot + 11f, axisP)
            }
            y = bot + 16f
        }

        private fun ribbon(
            agp: List<AgpBin>, xAt: (Int) -> Float, yAt: (Double) -> Float,
            lo: (AgpBin) -> Double, hi: (AgpBin) -> Double, color: Int,
        ) {
            if (agp.size < 2) return
            val path = Path()
            path.moveTo(xAt(agp.first().minuteOfDay), yAt(hi(agp.first())))
            agp.forEach { path.lineTo(xAt(it.minuteOfDay), yAt(hi(it))) }
            for (i in agp.indices.reversed()) path.lineTo(xAt(agp[i].minuteOfDay), yAt(lo(agp[i])))
            path.close()
            cv.drawPath(path, Paint().apply { this.color = color; isAntiAlias = true })
        }

        fun histogram(s: AdvancedStats, target: TargetRange) {
            val ch = 110f
            need(ch + 18f)
            y += 4f
            val x0 = M; val w = W - 2 * M; val bot = y + ch
            val maxFrac = s.histogram.maxOfOrNull { it.frac }?.takeIf { it > 0 } ?: 1.0
            val bw = w / s.histogram.size
            val fill = Paint().apply { isAntiAlias = true }
            s.histogram.forEachIndexed { i, bin ->
                val mid = (bin.lo + bin.hi) / 2.0
                fill.color = when {
                    mid < target.lowMgdl -> Color.rgb(0xF5, 0x7C, 0x00)
                    mid > target.highMgdl -> Color.rgb(0xF9, 0xA8, 0x25)
                    else -> Color.rgb(0x2E, 0x7D, 0x32)
                }
                val bh = (bin.frac / maxFrac * ch).toFloat()
                val bx = x0 + i * bw
                cv.drawRect(bx, bot - bh, bx + bw - 1f, bot, fill)
            }
            cv.drawText("40", x0, bot + 11f, axisP)
            cv.drawText("400 mg/dL", x0 + w - 46f, bot + 11f, axisP)
            y = bot + 16f
        }

        fun episode(label: String, e: EpisodeSummary, isHypo: Boolean) {
            need(16f)
            y += 15f
            cv.drawText(label, M, y, keyP)
            val v = if (e.count == 0) "none" else {
                "${e.count} · avg ${durMin(e.meanDurationMs)} · ${if (isHypo) "nadir" else "peak"} " +
                    "${d(e.meanExtreme, 0)} (worst ${d(e.worstExtreme, 0)}) mg/dL"
            }
            cv.drawText(v, W / 2f - 60f, y, valP)
        }

        fun finish(out: OutputStream) {
            stampFooter()
            doc.finishPage(page)
            doc.writeTo(out)
            doc.close()
        }
    }

    private fun paint(c: Int, size: Float, bold: Boolean = false) = Paint().apply {
        color = c; textSize = size; isFakeBoldText = bold; isAntiAlias = true
    }

    private fun todLabel(startMin: Int): String = when (startMin) {
        0 -> "Night 00–06"
        360 -> "Morning 06–12"
        720 -> "Afternoon 12–18"
        else -> "Evening 18–24"
    }

    private fun durMin(ms: Double): String {
        val min = (ms / 60_000.0).toInt()
        return if (min >= 60) "${min / 60}h${(min % 60).toString().padStart(2, '0')}" else "${min}m"
    }

    private fun gri(b: SubBands): Double =
        (3.0 * b.veryLow * 100 + 2.4 * b.low * 100 + 1.6 * b.veryHigh * 100 + 0.8 * b.high * 100).coerceIn(0.0, 100.0)

    private fun unitName(u: UnitSpace): String = when (u) {
        UnitSpace.MgDl -> "mg/dL"
        UnitSpace.MmolL -> "mmol/L"
        UnitSpace.Kovatchev -> "Kovatchev risk"
    }

    private fun pct(frac: Double): String = "${d(frac * 100, 1)}%"
    private fun d(v: Double, dp: Int): String = String.format(Locale.US, "%.${dp}f", v)
}
