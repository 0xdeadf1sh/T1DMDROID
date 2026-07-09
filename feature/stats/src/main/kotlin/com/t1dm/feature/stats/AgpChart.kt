package com.t1dm.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.AgpBin
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import kotlin.math.max
import kotlin.math.min

/**
 * The Ambulatory Glucose Profile ribbon (PLAN "Phase 6 — Stats"): the p5-p95 and p25-p75 percentile
 * bands with the p50 median line, laid over the 24 h time-of-day axis. A dedicated Canvas in the
 * terminal aesthetic — everything heavy (the percentiles) was computed in Rust; this only maps the
 * already-tiny (≤48-bin) ribbon to pixels, so it repaints at 120 Hz without jank.
 *
 * All BG values arrive in mg/dL and are converted into [unit] via [kovatchevF] ONCE in a
 * [remember] keyed on the ribbon + unit, so pan/paint never re-touch the JNI boundary. Bins are
 * sparse (only populated ones emitted); consecutive bins are joined linearly, so a data gap is
 * bridged rather than left as a hole.
 */
@Composable
fun AgpChart(
    agp: List<AgpBin>,
    unit: UnitSpace,
    target: TargetRange,
    kovatchevF: (Double) -> Double,
    modifier: Modifier = Modifier,
    medianColor: Color = Color(0xFF00E5FF),
    innerBandColor: Color = Color(0x5500E5FF),
    outerBandColor: Color = Color(0x2200E5FF),
    targetBandColor: Color = Color(0x2233FF88),
    gridColor: Color = Color(0x33FFFFFF),
    axisTextColor: Color = Color(0xFFB8C7D6),
) {
    val data = remember(agp, unit) { buildAgpFrame(agp, unit, target, kovatchevF) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp),
    ) {
        if (data == null || data.n == 0) return@Canvas
        val leftPad = 108f   // room for the y labels (px; density-independent enough at this size)
        val bottomPad = 34f
        val topPad = 10f
        val rightPad = 10f
        val plotW = size.width - leftPad - rightPad
        val plotH = size.height - topPad - bottomPad
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        fun x(minuteOfDay: Float): Float = leftPad + (minuteOfDay / 1440f) * plotW
        fun y(v: Float): Float = topPad + (1f - (v - data.yMin) / (data.yMax - data.yMin)) * plotH

        // Target band (in-range) shaded across the full width.
        val tLoY = y(data.targetLo)
        val tHiY = y(data.targetHi)
        drawRect(
            color = targetBandColor,
            topLeft = androidx.compose.ui.geometry.Offset(leftPad, tHiY),
            size = androidx.compose.ui.geometry.Size(plotW, tLoY - tHiY),
        )

        // Vertical hour grid (0,6,12,18,24) + horizontal target lines.
        for (h in 0..24 step 6) {
            val gx = x(h / 24f * 1440f)
            drawLine(gridColor, androidx.compose.ui.geometry.Offset(gx, topPad), androidx.compose.ui.geometry.Offset(gx, topPad + plotH), 1f)
        }
        drawLine(gridColor, androidx.compose.ui.geometry.Offset(leftPad, tLoY), androidx.compose.ui.geometry.Offset(leftPad + plotW, tLoY), 1f)
        drawLine(gridColor, androidx.compose.ui.geometry.Offset(leftPad, tHiY), androidx.compose.ui.geometry.Offset(leftPad + plotW, tHiY), 1f)

        // Percentile ribbons (outer p5-p95, then inner p25-p75) as filled areas.
        drawBand(data.xs, data.p5, data.p95, outerBandColor, ::x, ::y)
        drawBand(data.xs, data.p25, data.p75, innerBandColor, ::x, ::y)

        // Median polyline.
        val median = Path()
        for (i in 0 until data.n) {
            val px = x(data.xs[i]); val py = y(data.p50[i])
            if (i == 0) median.moveTo(px, py) else median.lineTo(px, py)
        }
        drawPath(median, medianColor, style = Stroke(width = 3f))

        // Axis labels: hour ticks + a few BG gridlines in the active unit.
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(
                255,
                (axisTextColor.red * 255).toInt(),
                (axisTextColor.green * 255).toInt(),
                (axisTextColor.blue * 255).toInt(),
            )
            textSize = 22f
            isAntiAlias = true
        }
        for (h in 0..24 step 6) {
            val gx = x(h / 24f * 1440f)
            drawContext.canvas.nativeCanvas.drawText("${h}h", gx - 8f, size.height - 10f, paint)
        }
        // y labels at target low/high + the top extreme.
        drawContext.canvas.nativeCanvas.drawText(fmtAxis(data.targetLo, unit), 6f, tLoY + 7f, paint)
        drawContext.canvas.nativeCanvas.drawText(fmtAxis(data.targetHi, unit), 6f, tHiY + 7f, paint)
        drawContext.canvas.nativeCanvas.drawText(fmtAxis(data.yMax, unit), 6f, y(data.yMax) + 18f, paint)
    }
}

private inline fun DrawScope.drawBand(
    xs: FloatArray,
    lo: FloatArray,
    hi: FloatArray,
    color: Color,
    x: (Float) -> Float,
    y: (Float) -> Float,
) {
    val n = xs.size
    if (n == 0) return
    val path = Path()
    path.moveTo(x(xs[0]), y(hi[0]))
    for (i in 1 until n) path.lineTo(x(xs[i]), y(hi[i]))
    for (i in n - 1 downTo 0) path.lineTo(x(xs[i]), y(lo[i]))
    path.close()
    drawPath(path, color)
}

/** Immutable, unit-converted AGP frame: parallel arrays over the populated bins (ascending). */
private class AgpFrame(
    val xs: FloatArray,
    val p5: FloatArray,
    val p25: FloatArray,
    val p50: FloatArray,
    val p75: FloatArray,
    val p95: FloatArray,
    val yMin: Float,
    val yMax: Float,
    val targetLo: Float,
    val targetHi: Float,
) {
    val n: Int get() = xs.size
}

private fun buildAgpFrame(
    agp: List<AgpBin>,
    unit: UnitSpace,
    target: TargetRange,
    kovatchevF: (Double) -> Double,
): AgpFrame? {
    if (agp.isEmpty()) return null
    val sorted = agp.sortedBy { it.minuteOfDay }
    val n = sorted.size
    val xs = FloatArray(n)
    val p5 = FloatArray(n); val p25 = FloatArray(n); val p50 = FloatArray(n)
    val p75 = FloatArray(n); val p95 = FloatArray(n)
    fun c(v: Double) = convertBg(v, unit, kovatchevF).toFloat()
    var lo = Float.POSITIVE_INFINITY
    var hi = Float.NEGATIVE_INFINITY
    for (i in 0 until n) {
        val b = sorted[i]
        xs[i] = b.minuteOfDay.toFloat()
        p5[i] = c(b.p5); p25[i] = c(b.p25); p50[i] = c(b.p50); p75[i] = c(b.p75); p95[i] = c(b.p95)
        lo = min(lo, p5[i]); hi = max(hi, p95[i])
    }
    val tLo = c(target.lowMgdl.toDouble())
    val tHi = c(target.highMgdl.toDouble())
    // Ensure the target band is visible even if the ribbon is tight; pad 8 %.
    var yMin = min(lo, tLo)
    var yMax = max(hi, tHi)
    val pad = (yMax - yMin).let { if (it <= 0f) 1f else it * 0.08f }
    yMin -= pad; yMax += pad
    return AgpFrame(xs, p5, p25, p50, p75, p95, yMin, yMax, tLo, tHi)
}

/** mg/dL → the active unit space (mmol/L divides; Kovatchev uses the native `f`). */
internal fun convertBg(mgdl: Double, unit: UnitSpace, kovatchevF: (Double) -> Double): Double =
    when (unit) {
        UnitSpace.MgDl -> mgdl
        UnitSpace.MmolL -> mgdl / 18.0182
        UnitSpace.Kovatchev -> kovatchevF(mgdl)
    }

private fun fmtAxis(v: Float, unit: UnitSpace): String = when (unit) {
    UnitSpace.MgDl -> v.toInt().toString()
    UnitSpace.MmolL -> String.format("%.1f", v)
    UnitSpace.Kovatchev -> String.format("%.1f", v)
}
