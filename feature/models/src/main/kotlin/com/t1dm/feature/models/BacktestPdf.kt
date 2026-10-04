package com.t1dm.feature.models

import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.t1dm.core.design.PdfPager
import com.t1dm.core.design.PdfPager.Companion.M
import com.t1dm.core.design.PdfPager.Companion.W
import com.t1dm.core.model.BACKTEST_EXAMPLE_CONTEXT_MS
import com.t1dm.core.model.BacktestExample
import com.t1dm.core.model.BacktestStop
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.ErrorGridLattices
import com.t1dm.core.model.HorizonMetrics
import com.t1dm.core.model.ModelBacktest
import com.t1dm.core.model.ScoredPoint
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.ZoneLattice
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** mg/dL throughout; fans are raw, as the suite scored them (`SPEC/inference.md` §8.4). */
object BacktestPdf {
    /** [readings]: real measurements before the origin, ascending; events span the whole panel. */
    class Panel(
        val example: BacktestExample,
        val label: String,
        val readings: List<CgmReading>,
        val carbs: List<CurveEvent>,
        val boluses: List<CurveEvent>,
    )

    fun write(
        out: OutputStream,
        modelId: String,
        run: ModelBacktest.Done,
        sensorLabels: Map<String, String>,
        target: TargetRange,
        lattices: ErrorGridLattices,
        good: List<Panel>,
        bad: List<Panel>,
    ) {
        val p = PdfPager()
        p.title("T1DM — Backtest report")
        p.body("Model $modelId")
        p.body(
            "${run.days} d · %,d / %,d forecasts · ".format(Locale.US, run.nForecasts, run.nOrigins) +
                "${fmtDuration(run.elapsedMs.toDouble())} · finished ${stamp(run.finishedAtMs)}",
        )
        // Never the raw id: it can carry the serial the name-privacy setting hides.
        p.body(
            run.forecastsBySource.entries.joinToString(" · ") { (id, n) ->
                "${sensorLabels[id] ?: "CGM"} %,d".format(Locale.US, n)
            },
        )
        if (run.adapterAttached) p.body("Adapter attached — may be in-sample")
        when (run.stopped) {
            BacktestStop.TOO_HOT -> p.body("Stopped — too hot")
            BacktestStop.MODEL_CHANGED -> p.body("Stopped — model reloaded")
            null -> Unit
        }
        p.caption("BG in mg/dL. Raw fans: no band recalibration.")

        val suite = run.metrics.suite
        val scored = suite.horizons.filter { it.sufficient }
        p.section("Realized accuracy — band τ.25–.75")
        if (scored.isEmpty()) p.caption(emptyWhy(run.metrics)) else p.table(bandTable(scored))
        suite.horizons.filterNot { it.sufficient }.forEach {
            p.caption("${it.horizonMin} min: n=${it.n}, need ${run.metrics.minSamples}")
        }
        if (scored.isNotEmpty()) {
            p.section("Median line")
            p.table(medianTable(scored))
            p.section("Outer band τ.05–.95 · persistence")
            p.table(outerTable(scored))
            p.section("Excursions vs alarm bands")
            p.caption("Hypo off the τ.25 edge, hyper off τ.75")
            p.table(excursionTable(scored))
            p.section("DTS zones — band τ.25–.75")
            p.table(dtsTable(scored))
            p.section("Trend risk categories — median line")
            p.caption("1 no risk · 2 under · 3 over · 4/5 extreme")
            p.table(trendTable(scored))
        }
        suite.cgega?.let {
            p.section("CG-EGA")
            p.table(cgEgaTable(it))
        }
        if (scored.isNotEmpty()) {
            p.section("Error grids — median line", keepWith = CAPTION_H + GRID_PAIR_H)
            p.caption("x truth · y pred · mg/dL")
            scored.forEach { p.gridPair(it, lattices) }
        }

        p.examples("Good forecasts", "Largest MAE gain over persistence", good, target)
        p.examples("Bad forecasts", "Largest MAE", bad, target)
        p.finish(out)
    }

    private fun PdfPager.table(t: MetricTable) = table(t.columns, t.rows)

    private fun PdfPager.examples(title: String, rank: String, panels: List<Panel>, target: TargetRange) {
        section(title, keepWith = CAPTION_H + PANEL_BLOCK_H)
        val first = panels.firstOrNull()
        if (first == null) {
            caption("None scored")
            return
        }
        val spanMs = first.example.window.realizedBg.size * first.example.stepMs
        val apartH = (BACKTEST_EXAMPLE_CONTEXT_MS + spanMs) / 3_600_000.0
        caption(
            "$rank, median line, 0–${spanMs / 60_000} min, ≥${d(apartH, 0)} h apart. " +
                "Fan τ.05–.95 · .10–.90 · .25–.75.",
        )
        panels.forEach { panel(it, target) }
    }

    private const val CAPTION_H = 14f
    private const val GRID_GUTTER = 18f
    private const val GRID_COL_W = (W - 2 * M) / 2f
    private const val GRID_SIDE = GRID_COL_W - GRID_GUTTER - 10f
    private const val GRID_PAIR_H = GRID_SIDE + 52f

    private fun PdfPager.gridPair(h: HorizonMetrics, lattices: ErrorGridLattices) {
        need(GRID_PAIR_H)
        y += 4f
        val n = h.medianLine.points.size
        canvas.drawText("Clarke · ${h.horizonMin} min · n=$n", M + GRID_GUTTER, y + 10f, capP)
        canvas.drawText("DTS · ${h.horizonMin} min · n=$n", M + GRID_COL_W + GRID_GUTTER, y + 10f, capP)
        y += 18f
        val top = y
        errorGrid(M + GRID_GUTTER, top, GRID_SIDE, h.medianLine.points, { it.clarke.ordinal }, lattices.clarke)
        errorGrid(M + GRID_COL_W + GRID_GUTTER, top, GRID_SIDE, h.medianLine.points, { it.dts.ordinal }, lattices.dts)
        y = top + GRID_SIDE + 30f
    }

    /** Off-axis pairs are dropped and counted, never clamped into an unclassified zone. */
    private fun PdfPager.errorGrid(
        left: Float,
        top: Float,
        side: Float,
        points: List<ScoredPoint>,
        zoneOf: (ScoredPoint) -> Int,
        grid: ZoneLattice,
    ) {
        if (grid.isEmpty) {
            canvas.drawText("Zone regions unavailable", left, top + 12f, capP)
            return
        }
        val bottom = top + side
        val axisMax = grid.axisMaxMgdl.toFloat()
        val cell = side / grid.cells
        // Opaque, overlapping by a hair: abutting translucent runs show seams in most viewers.
        val fill = Paint()
        zoneRuns(grid).forEach { r ->
            fill.color = regionColor(r.zone)
            canvas.drawRect(
                left + r.truthIndex * cell, bottom - r.predUntil * cell,
                left + (r.truthIndex + 1) * cell + 0.3f, bottom - r.predFrom * cell + 0.3f, fill,
            )
        }
        canvas.drawRect(left, top, left + side, bottom, stroke(Color.LTGRAY, 0.6f))
        canvas.drawLine(left, bottom, left + side, top, stroke(Color.argb(115, 0, 0, 0), 0.8f))
        listOf(0f, 100f, 200f, 300f, 400f).filter { it <= axisMax }.forEach { t ->
            val gx = left + t / axisMax * side
            val gy = bottom - t / axisMax * side
            val label = t.toInt().toString()
            canvas.drawText(label, left - 3f - axisP.measureText(label), gy + 3f, axisP)
            canvas.drawText(label, gx - axisP.measureText(label) / 2f, bottom + 9f, axisP)
        }

        // One dot per point of page: thousands of coincident dots only bloat the file.
        val res = side.toInt() + 1
        val taken = BooleanArray(res * res)
        val dot = Paint().apply { isAntiAlias = true }
        val r = 0.8f
        var offScale = 0
        for (pt in points) {
            val t = pt.truth
            val q = pt.pred
            if (!t.isFinite() || !q.isFinite() || t < 0.0 || t > axisMax || q < 0.0 || q > axisMax) {
                offScale++
                continue
            }
            val x = left + (t / axisMax * side).toFloat()
            val yy = bottom - (q / axisMax * side).toFloat()
            val k = (yy - top).toInt().coerceIn(0, res - 1) * res + (x - left).toInt().coerceIn(0, res - 1)
            if (taken[k]) continue
            taken[k] = true
            val z = zoneOf(pt)
            dot.color = withAlpha(ZONE_BASE[z], DOT_ALPHA[z])
            canvas.drawRect(x - r, yy - r, x + r, yy + r, dot)
        }

        val letter = PdfPager.paint(Color.argb(200, 0, 0, 0), 10f)
        zoneAnchors(grid).forEach { a ->
            val s = ZONE_LETTERS[a.zone]
            val x = left + (a.truthMgdl / axisMax * side).toFloat()
            val yy = bottom - (a.predMgdl / axisMax * side).toFloat()
            canvas.drawText(s, x - letter.measureText(s) / 2f, yy + 3.5f, letter)
        }

        val shares = zoneShares(points, zoneOf)
        val legend = buildString {
            if (shares.isNotEmpty()) {
                append(ZONE_LETTERS.indices.joinToString(" · ") { "${ZONE_LETTERS[it]} ${d(shares[it].toDouble(), 1)}" })
                append(" %")
            }
            if (offScale > 0) append(" · $offScale off-scale")
        }
        canvas.drawText(legend, left, bottom + 20f, axisP)
    }

    private const val PANEL_H = 120f
    private const val PANEL_BLOCK_H = PANEL_H + 60f
    private const val PANEL_GUTTER = 22f
    private const val TICK_MS = 2L * 3_600_000L

    private fun PdfPager.panel(pn: Panel, target: TargetRange) {
        val e = pn.example
        val w = e.window
        val n = w.realizedBg.size
        if (n == 0) return
        val q = w.bandsMgdl.size / n
        val origin = e.cycleTsMs
        val fromMs = origin - BACKTEST_EXAMPLE_CONTEXT_MS
        val toMs = origin + n * e.stepMs
        val tzMin = pn.readings.lastOrNull()?.tzOffsetMin ?: (TimeZone.getDefault().getOffset(origin) / 60_000)

        need(PANEL_BLOCK_H)
        y += 6f
        canvas.drawText("${local(origin, tzMin)} · ${pn.label}", M, y + 11f, valP)
        y += 14f
        canvas.drawText(
            "MAE ${d(e.maeMgdl, 1)} · persistence ${d(e.persistMaeMgdl, 1)} mg/dL",
            M, y + 10f, capP,
        )
        y += 14f

        val left = M + PANEL_GUTTER
        val right = W - M
        val top = y + 4f
        val bot = top + PANEL_H
        val peak = maxOf(
            pn.readings.maxOfOrNull { it.bgMgdl ?: 0 }?.toDouble() ?: 0.0,
            w.realizedBg.max(),
            if (q > 0) (0 until n).maxOf { w.bandsMgdl[it * q + q - 1] } else 0.0,
            w.medianBg.max(),
        )
        val loBg = 40.0
        val hiBg = maxOf(300.0, ceil(peak / 50.0) * 50.0).coerceAtMost(600.0)
        fun xAt(ms: Long) = left + (ms - fromMs).toFloat() / (toMs - fromMs) * (right - left)
        fun yAt(bg: Double) = (bot - ((bg - loBg) / (hiBg - loBg) * PANEL_H)).toFloat().coerceIn(top, bot)
        fun stepX(i: Int) = xAt(origin + (i + 1) * e.stepMs)

        val band = Paint().apply { color = Color.rgb(0xE8, 0xF5, 0xE9) }
        canvas.drawRect(left, yAt(target.highMgdl.toDouble()), right, yAt(target.lowMgdl.toDouble()), band)
        canvas.drawRect(left, top, right, bot, stroke(Color.LTGRAY, 0.6f))
        listOf(loBg, target.lowMgdl.toDouble(), target.highMgdl.toDouble(), hiBg).forEach {
            val s = d(it, 0)
            canvas.drawText(s, left - 3f - axisP.measureText(s), yAt(it) + 3f, axisP)
        }
        val offMs = tzMin * 60_000L
        var tick = Math.floorDiv(fromMs + offMs, TICK_MS) * TICK_MS - offMs
        if (tick < fromMs) tick += TICK_MS
        val clock = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        while (tick <= toMs) {
            val x = xAt(tick)
            val s = clock.format(Date(tick + offMs))
            val sw = axisP.measureText(s)
            canvas.drawLine(x, bot, x, bot + 3f, axisP)
            canvas.drawText(s, (x - sw / 2f).coerceAtMost(right - sw), bot + 11f, axisP)
            tick += TICK_MS
        }

        val ox = xAt(origin)
        val dashed = stroke(Color.GRAY, 0.6f).apply { pathEffect = DashPathEffect(floatArrayOf(3f, 2f), 0f) }
        canvas.drawLine(ox, top, ox, bot, dashed)

        val ctxInk = Color.rgb(0x42, 0x42, 0x42)
        val ctxLine = stroke(ctxInk, 0.9f)
        val ctxDot = Paint().apply { color = ctxInk; isAntiAlias = true }
        var prevMs = -1L
        var prevY = 0f
        for (r in pn.readings) {
            val bg = r.bgMgdl ?: continue
            val x = xAt(r.tsMs)
            val yy = yAt(bg.toDouble())
            // A missing reading breaks the line; it is never bridged.
            if (prevMs >= 0 && r.tsMs - prevMs <= e.stepMs * 3 / 2) canvas.drawLine(xAt(prevMs), prevY, x, yy, ctxLine)
            canvas.drawCircle(x, yy, 0.7f, ctxDot)
            prevMs = r.tsMs
            prevY = yy
        }

        // Fan pairs outer→inner, positional as `SPEC/invariants.md` §6 fixes the level order.
        val fan = Paint().apply { isAntiAlias = true }
        for (b in 0 until q / 2) {
            val path = Path()
            for (i in 0 until n) {
                val hi = yAt(w.bandsMgdl[i * q + q - 1 - b])
                if (i == 0) path.moveTo(stepX(i), hi) else path.lineTo(stepX(i), hi)
            }
            for (i in n - 1 downTo 0) path.lineTo(stepX(i), yAt(w.bandsMgdl[i * q + b]))
            path.close()
            fan.color = Color.argb(40 + 35 * b, 0x42, 0x85, 0xF4)
            canvas.drawPath(path, fan)
        }
        val med = Path()
        for (i in 0 until n) {
            if (i == 0) med.moveTo(stepX(i), yAt(w.medianBg[i])) else med.lineTo(stepX(i), yAt(w.medianBg[i]))
        }
        canvas.drawPath(med, stroke(Color.rgb(0x15, 0x65, 0xC0), 1.4f))

        val truth = Paint().apply { color = Color.BLACK; isAntiAlias = true }
        for (i in 0 until n) canvas.drawCircle(stepX(i), yAt(w.realizedBg[i]), 1.1f, truth)
        canvas.drawCircle(ox, yAt(w.lastBg), 1.6f, truth)

        val carbInk = Paint().apply { color = Color.rgb(0xF5, 0x7C, 0x00); isAntiAlias = true }
        pn.carbs.filter { it.startMs in fromMs..toMs }.forEach { c ->
            val x = xAt(c.startMs)
            canvas.drawPath(triangle(x, bot - 1f, up = true), carbInk)
            canvas.drawText("${c.total.roundToInt()}g", x + 3f, bot - 3f, axisP)
        }
        val bolusInk = Paint().apply { color = Color.rgb(0x6A, 0x1B, 0x9A); isAntiAlias = true }
        pn.boluses.filter { it.startMs in fromMs..toMs }.forEach { b ->
            val x = xAt(b.startMs)
            canvas.drawPath(triangle(x, top + 1f, up = false), bolusInk)
            canvas.drawText("${d(b.total, 1)}U", x + 3f, top + 8f, axisP)
        }
        y = bot + 18f
    }

    private fun triangle(x: Float, baseY: Float, up: Boolean): Path {
        val h = if (up) -5f else 5f
        return Path().apply {
            moveTo(x - 3f, baseY)
            lineTo(x + 3f, baseY)
            lineTo(x, baseY + h)
            close()
        }
    }

    /** Print counterpart of the drill-down's zone ramp: in range, in range, low, high, urgent. */
    private val ZONE_BASE = intArrayOf(
        Color.rgb(0x2E, 0x7D, 0x32),
        Color.rgb(0x2E, 0x7D, 0x32),
        Color.rgb(0xF5, 0x7C, 0x00),
        Color.rgb(0xF9, 0xA8, 0x25),
        Color.rgb(0xC6, 0x28, 0x28),
    )

    /** [REGION_ALPHA] composited onto white paper. */
    private fun regionColor(zone: Int): Int {
        val c = ZONE_BASE[zone]
        val a = REGION_ALPHA[zone]
        fun ch(v: Int) = (255 + (v - 255) * a).roundToInt()
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    private fun withAlpha(c: Int, a: Float) =
        Color.argb((a * 255).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))

    private fun stroke(c: Int, width: Float) = Paint().apply {
        style = Paint.Style.STROKE; color = c; strokeWidth = width; isAntiAlias = true
    }

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    /** In the reading's own zone (`SPEC/invariants.md` §2), not the phone's current one. */
    private fun local(ms: Long, tzMin: Int): String {
        val f = SimpleDateFormat("yyyy-MM-dd EEE HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sign = if (tzMin < 0) "−" else "+"
        val off = abs(tzMin)
        return "${f.format(Date(ms + tzMin * 60_000L))} UTC$sign%02d:%02d".format(Locale.US, off / 60, off % 60)
    }

    private fun d(v: Double, dp: Int): String = String.format(Locale.US, "%.${dp}f", v)
}
