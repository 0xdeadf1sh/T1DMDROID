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
import com.t1dm.core.model.HeatCell
import com.t1dm.core.model.HeatStat
import com.t1dm.core.model.UnitSpace
import kotlin.math.min

const val HEATMAP_DAYS = 7
const val HEATMAP_HOURS = 24

/** Index 0 = Monday, the ISO order `HeatCell.dow` is emitted in. */
val DAY_LABELS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** 7 rows × 24 columns summarised by [stat] — both summaries come from one core pass, so [stat] is
 *  a repaint. An empty cell is an outline, never a fill. */
@Composable
fun BgHeatmap(
    heatmap: List<HeatCell>,
    stat: HeatStat,
    unit: UnitSpace,
    kovatchevF: (Double) -> Double,
    modifier: Modifier = Modifier,
    gridColor: Color = Color(0x33FFFFFF),
    axisTextColor: Color = Color(0xFFB8C7D6),
) {
    // NaN marks "no reading"; no glucose value can collide with it.
    val grid = remember(heatmap, stat) {
        FloatArray(HEATMAP_DAYS * HEATMAP_HOURS) { Float.NaN }.also { g ->
            for (c in heatmap) {
                if (c.dow in 0 until HEATMAP_DAYS && c.hour in 0 until HEATMAP_HOURS) {
                    g[c.dow * HEATMAP_HOURS + c.hour] = c.value(stat).toFloat()
                }
            }
        }
    }
    val colors = remember(grid) {
        Array(grid.size) { i -> grid[i].takeIf { !it.isNaN() }?.let { heatColor(it.toDouble()) } }
    }
    // Hoisted out of the draw: a Paint is a real allocation, and this repaints every scroll frame.
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
            // Never wider than a third of the cell, or a narrow phone erases the fill.
            val gap = min(1.dp.toPx(), min(cellW, cellH) / 3f)

            for (d in 0 until HEATMAP_DAYS) {
                val top = d * cellH
                for (h in 0 until HEATMAP_HOURS) {
                    val left = leftPad + h * cellW
                    val c = colors[d * HEATMAP_HOURS + h]
                    if (c == null) {
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
            // Every hour would collide on a phone width.
            for (h in 0..HEATMAP_HOURS step 3) {
                val x = leftPad + h * cellW
                drawContext.canvas.nativeCanvas.drawText(
                    h.toString(), x - 6f, size.height - 8f, textPaint,
                )
            }
        }
        HeatLegend(unit, kovatchevF, textPaint)
    }
}

/** The scale in numbers — what makes the grid readable without discriminating hue at all. */
@Composable
private fun HeatLegend(
    unit: UnitSpace,
    kovatchevF: (Double) -> Double,
    textPaint: android.graphics.Paint,
) {
    val brush = remember {
        Brush.horizontalGradient(0f to HEAT_LOW, HEAT_MID_STOP to HEAT_IN, 1f to HEAT_HIGH)
    }
    Canvas(Modifier.fillMaxWidth().height(30.dp)) {
        val leftPad = 62f
        val barW = size.width - leftPad
        if (barW <= 0f) return@Canvas
        val barH = 8f
        drawRect(brush = brush, topLeft = Offset(leftPad, 0f), size = Size(barW, barH))
        for ((v, stop) in listOf(HEAT_FLOOR_MGDL to 0f, HEAT_MID_MGDL to HEAT_MID_STOP, HEAT_CEIL_MGDL to 1f)) {
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

/** Fixed, not the patient's target range: a cell colour means the same glucose on every phone. */
const val HEAT_FLOOR_MGDL = 70.0
const val HEAT_MID_MGDL = 105.0
const val HEAT_CEIL_MGDL = 140.0

private val HEAT_MID_STOP =
    ((HEAT_MID_MGDL - HEAT_FLOOR_MGDL) / (HEAT_CEIL_MGDL - HEAT_FLOOR_MGDL)).toFloat()

/** mg/dL → colour: blue at [HEAT_FLOOR_MGDL], green at [HEAT_MID_MGDL], red at [HEAT_CEIL_MGDL],
 *  clamped outside. */
fun heatColor(mgdl: Double): Color = when {
    mgdl <= HEAT_FLOOR_MGDL -> HEAT_LOW
    mgdl <= HEAT_MID_MGDL ->
        lerp(HEAT_LOW, HEAT_IN, ((mgdl - HEAT_FLOOR_MGDL) / (HEAT_MID_MGDL - HEAT_FLOOR_MGDL)).toFloat())
    mgdl < HEAT_CEIL_MGDL ->
        lerp(HEAT_IN, HEAT_HIGH, ((mgdl - HEAT_MID_MGDL) / (HEAT_CEIL_MGDL - HEAT_MID_MGDL)).toFloat())
    else -> HEAT_HIGH
}

private fun fmtHeatAxis(v: Double, unit: UnitSpace): String = when (unit) {
    UnitSpace.MgDl -> v.toInt().toString()
    UnitSpace.MmolL -> String.format("%.1f", v)
    UnitSpace.Kovatchev -> String.format("%.1f", v)
}

// Fixed, not theme-linked: a cell colour is a clinical statement. Validated under
// Machado-Oliveira-Fernandes — worst pair ΔE 9.1 (OKLab ×100) in protanopia, 26.5 under normal
// vision. Narrow: lifting HEAT_IN two steps collapses the red-green pair to ΔE 4.0. Never by eye.
val HEAT_LOW = Color(0xFF57ACEE)
val HEAT_IN = Color(0xFF24794A)
val HEAT_HIGH = Color(0xFFFF7062)
