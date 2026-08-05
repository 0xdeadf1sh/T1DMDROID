package com.t1dm.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.ClinicalCuts
import com.t1dm.core.model.HeatCell
import com.t1dm.core.model.HeatStat
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import kotlin.math.max
import kotlin.math.min

const val HEATMAP_DAYS = 7
const val HEATMAP_HOURS = 24

/** Row labels, index 0 = Monday — the ISO order `HeatCell.dow` is emitted in. Public because the
 *  PDF export renders the same grid and must not restate them. */
val DAY_LABELS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * Glucose by weekday and hour of day: 7 rows × 24 columns, each cell coloured on a continuous
 * blue → green → red scale, summarised by [stat].
 *
 * Both summaries come out of one core pass, so [stat] is a repaint and never a recompute — which is
 * what makes flipping the chip above this chart free.
 *
 * **The scale is diverging and anchored on real numbers, not on the data's own extremes.** Green
 * covers the whole of [target]; below it the colour runs to blue by the level-2 hypo cut and above
 * it to red by the level-2 hyper cut, both read from the Rust crate through [cuts] rather than
 * restated here. So the same cell colour means the same glucose across every window and every
 * patient, and widening the target range visibly widens the green plateau.
 *
 * Green in the middle is the clinical convention this screen already speaks — the TIR bar and the
 * histogram above it colour in-range green — and keeping the heatmap on it is what lets the three be
 * read together. It costs something, and the cost is paid for deliberately: red against green is the
 * pair that collapses under the common red-green colour blindness, so the three anchors are
 * separated in LIGHTNESS as well as hue (a calm dark green between two bright poles), which is what
 * carries the reading when hue does not. See the palette note at the foot of this file.
 *
 * An empty cell is drawn as an outline over the card, never as a fill: on a 7-day window a cell holds
 * a single hour of a single day, and a sensor gap must not read as a glucose value.
 */
@Composable
fun BgHeatmap(
    heatmap: List<HeatCell>,
    stat: HeatStat,
    target: TargetRange,
    cuts: ClinicalCuts,
    unit: UnitSpace,
    kovatchevF: (Double) -> Double,
    modifier: Modifier = Modifier,
    gridColor: Color = Color(0x33FFFFFF),
    axisTextColor: Color = Color(0xFFB8C7D6),
) {
    // Scatter the sparse cells into the dense lattice once, off the draw. NaN marks "no reading",
    // which no glucose value can collide with.
    val grid = remember(heatmap, stat) {
        FloatArray(HEATMAP_DAYS * HEATMAP_HOURS) { Float.NaN }.also { g ->
            for (c in heatmap) {
                if (c.dow in 0 until HEATMAP_DAYS && c.hour in 0 until HEATMAP_HOURS) {
                    g[c.dow * HEATMAP_HOURS + c.hour] = c.value(stat).toFloat()
                }
            }
        }
    }
    val colors = remember(grid, target, cuts) {
        Array(grid.size) { i -> grid[i].takeIf { !it.isNaN() }?.let { heatColor(it.toDouble(), target, cuts) } }
    }
    // Hoisted out of the draw: a Paint is a real allocation and this canvas repaints on every scroll
    // frame of the page it sits on.
    val textPaint = remember(axisTextColor) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(
                255,
                (axisTextColor.red * 255).toInt(),
                (axisTextColor.green * 255).toInt(),
                (axisTextColor.blue * 255).toInt(),
            )
            textSize = 22f
            isAntiAlias = true
        }
    }

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(168.dp)) {
            val leftPad = 62f
            val bottomPad = 30f
            val plotW = size.width - leftPad
            val plotH = size.height - bottomPad
            if (plotW <= 0f || plotH <= 0f) return@Canvas
            val cellW = plotW / HEATMAP_HOURS
            val cellH = plotH / HEATMAP_DAYS
            // A hairline of card between cells, so a run of same-valued hours reads as hours and not
            // as one block — but never wider than a third of the cell, which a narrow phone would
            // otherwise erase the fill entirely at.
            val gap = min(1.dp.toPx(), min(cellW, cellH) / 3f)

            for (d in 0 until HEATMAP_DAYS) {
                val top = d * cellH
                for (h in 0 until HEATMAP_HOURS) {
                    val left = leftPad + h * cellW
                    val c = colors[d * HEATMAP_HOURS + h]
                    if (c == null) {
                        // No reading in this hour: an outline, not a fill.
                        drawRect(
                            color = gridColor,
                            topLeft = Offset(left + gap, top + gap),
                            size = Size(cellW - gap * 2f, cellH - gap * 2f),
                            style = Stroke(width = 1f),
                        )
                    } else {
                        drawRect(
                            color = c,
                            topLeft = Offset(left + gap, top + gap),
                            size = Size(cellW - gap * 2f, cellH - gap * 2f),
                        )
                    }
                }
                drawContext.canvas.nativeCanvas.drawText(
                    DAY_LABELS[d], 4f, top + cellH / 2f + 8f, textPaint,
                )
            }
            // Hour ticks every three hours; every hour would collide on a phone width.
            for (h in 0..HEATMAP_HOURS step 3) {
                val x = leftPad + h * cellW
                drawContext.canvas.nativeCanvas.drawText(
                    h.toString(), x - 6f, size.height - 8f, textPaint,
                )
            }
        }
        HeatLegend(target, cuts, unit, kovatchevF, textPaint)
    }
}

/**
 * The scale, stated in numbers. The relief for a colour ramp whose poles sit under 3:1 against a
 * light card — and the only thing that makes the grid readable without discriminating hue at all.
 */
@Composable
private fun HeatLegend(
    target: TargetRange,
    cuts: ClinicalCuts,
    unit: UnitSpace,
    kovatchevF: (Double) -> Double,
    textPaint: android.graphics.Paint,
) {
    val lo = target.lowMgdl.toDouble()
    val hi = target.highMgdl.toDouble()
    val floor = min(cuts.veryLowMgdl, lo)
    val ceil = max(cuts.veryHighMgdl, hi)
    val span = (ceil - floor).takeIf { it > 0.0 } ?: 1.0
    val loStop = ((lo - floor) / span).toFloat().coerceIn(0f, 1f)
    val hiStop = ((hi - floor) / span).toFloat().coerceIn(loStop, 1f)
    val brush = remember(loStop, hiStop) {
        Brush.horizontalGradient(
            0f to HEAT_LOW,
            loStop to HEAT_IN,
            hiStop to HEAT_IN,
            1f to HEAT_HIGH,
        )
    }
    Canvas(Modifier.fillMaxWidth().height(30.dp)) {
        val leftPad = 62f
        val barW = size.width - leftPad
        if (barW <= 0f) return@Canvas
        val barH = 8f
        drawRect(brush = brush, topLeft = Offset(leftPad, 0f), size = Size(barW, barH))
        // Anchors only — the two clinical cuts and the two target edges, which is every number the
        // ramp actually turns on.
        for ((v, stop) in listOf(floor to 0f, lo to loStop, hi to hiStop, ceil to 1f)) {
            val x = leftPad + stop * barW
            drawContext.canvas.nativeCanvas.drawText(
                fmtHeatAxis(convertBg(v, unit, kovatchevF), unit),
                (x - 10f).coerceIn(0f, size.width - 40f),
                barH + 20f,
                textPaint,
            )
        }
    }
}

/**
 * A cell's glucose summary (mg/dL) → its colour.
 *
 * Pure, and deliberately takes both anchors: [target] is the user's own range and [cuts] the fixed
 * clinical ones, so this holds no glucose constant of its own. Green spans the whole target; each
 * arm interpolates from the target edge out to the matching cut and clamps beyond it.
 *
 * The cuts are clamped against the target edges exactly as the Rust clamps them when partitioning
 * the sub-bands: the target range is unbounded by design, so a target wider than `[54, 250]` would
 * otherwise leave an arm with zero or negative span.
 */
fun heatColor(mgdl: Double, target: TargetRange, cuts: ClinicalCuts): Color {
    val lo = target.lowMgdl.toDouble()
    val hi = target.highMgdl.toDouble()
    val floor = min(cuts.veryLowMgdl, lo)
    val ceil = max(cuts.veryHighMgdl, hi)
    return when {
        mgdl <= floor -> HEAT_LOW
        mgdl < lo -> lerp(HEAT_LOW, HEAT_IN, ((mgdl - floor) / (lo - floor)).toFloat())
        mgdl <= hi -> HEAT_IN
        mgdl < ceil -> lerp(HEAT_IN, HEAT_HIGH, ((mgdl - hi) / (ceil - hi)).toFloat())
        else -> HEAT_HIGH
    }
}

private fun fmtHeatAxis(v: Double, unit: UnitSpace): String = when (unit) {
    UnitSpace.MgDl -> v.toInt().toString()
    UnitSpace.MmolL -> String.format("%.1f", v)
    UnitSpace.Kovatchev -> String.format("%.1f", v)
}

// The diverging ramp's three anchors. Fixed, not theme-linked — the same decision, and for the same
// reason, as the BAND_* palette in StatsScreen: a cell colour is a clinical statement and must mean
// the same thing under every theme.
//
// These three are not eyeballed. Under the Machado-Oliveira-Fernandes simulation at full severity
// the worst pair separates by ΔE 9.1 (OKLab ×100) in protanopia and 26.5 under normal vision, both
// clear of the 8 / 15 floors, with every anchor above the 0.10 chroma floor and above 3:1 on the
// dark card. The margin is narrow and the surface is deceptive: lifting HEAT_IN by two steps, to a
// green that looks barely different, collapses the red-green pair to ΔE 4.0. Re-derive with the
// validator before changing any of them, and do not adjust one by eye.
val HEAT_LOW = Color(0xFF57ACEE)
val HEAT_IN = Color(0xFF24794A)
val HEAT_HIGH = Color(0xFFFF7062)
