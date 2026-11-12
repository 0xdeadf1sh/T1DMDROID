package com.t1dm.feature.twin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinTrait
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private enum class Range(val label: String, val ms: Long?) {
    WEEK("7d", 7L * 86_400_000),
    MONTH("30d", 30L * 86_400_000),
    ALL("All", null),
}

private class Trend(val label: String, val unit: String, val digits: Int, val of: (TwinScan) -> Double?)

private val TRENDS = listOf(
    Trend("AGE", "y", 1) { it[TwinTrait.AGE] },
    Trend("HEIGHT", "cm", 1) { it[TwinTrait.HEIGHT] },
    Trend("WEIGHT", "kg", 1) { it[TwinTrait.WEIGHT] },
    Trend("BMI", "kg/m²", 1) { s ->
        val h = s[TwinTrait.HEIGHT] ?: return@Trend null
        val w = s[TwinTrait.WEIGHT] ?: return@Trend null
        w / ((h / 100) * (h / 100))
    },
    Trend("BODY FAT", "%", 1) { it[TwinTrait.BODY_FAT] },
    Trend("MUSCLE", "", 2) { it[TwinTrait.MUSCLE] },
    Trend("SURVIVAL 10 Y", "%", 1) { s -> s[TwinTrait.SURVIVAL_10Y]?.let { it * 100 } },
)

/** Scans only, oldest first; manual edits are not history. */
@Composable
fun TwinTrendsScreen(scans: List<TwinScan>, modifier: Modifier = Modifier) {
    var range by remember { mutableStateOf(Range.ALL) }
    val now = remember(scans) { System.currentTimeMillis() }
    val shown = remember(scans, range) { range.ms?.let { span -> scans.filter { it.atMs >= now - span } } ?: scans }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Range.entries.forEach { r -> FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) }) }
            }
        }
        items(TRENDS, key = { it.label }) { TrendCard(it, shown) }
    }
}

@Composable
private fun TrendCard(trend: Trend, scans: List<TwinScan>) {
    val cs = MaterialTheme.colorScheme
    val points = remember(scans, trend) { scans.mapNotNull { s -> trend.of(s)?.let { s.atMs to it } } }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, cs.primary.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .background(cs.surface.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(trend.label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            points.lastOrNull()?.let { (_, v) ->
                Text(fmt(v, trend), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            }
        }
        if (points.size >= 2) {
            val delta = points.last().second - points.first().second
            Text(
                (if (delta >= 0) "+" else "−") + fmt(abs(delta), trend) + " since first",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            TrendChart(points, trend, Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp))
        } else {
            Text("Needs 2 scans in range", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrendChart(points: List<Pair<Long, Double>>, trend: Trend, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant)
    val tip = MaterialTheme.typography.labelSmall.copy(color = cs.onSurface)
    var scrubX by remember { mutableStateOf<Float?>(null) }
    val stamp = remember { SimpleDateFormat("MMM d HH:mm", Locale.getDefault()) }
    Canvas(
        modifier
            .pointerInput(points) { detectTapGestures { scrubX = if (scrubX == null) it.x else null } }
            .pointerInput(points) {
                detectDragGestures(onDragEnd = { scrubX = null }, onDragCancel = { scrubX = null }) { change, _ ->
                    scrubX = change.position.x
                }
            },
    ) {
        val t0 = points.first().first.toDouble()
        val t1 = points.last().first.toDouble().let { if (it == t0) t0 + 1 else it }
        var lo = points.minOf { it.second }
        var hi = points.maxOf { it.second }
        if (hi - lo < 1e-9) {
            lo -= 1
            hi += 1
        }
        val pad = (hi - lo) * 0.12
        lo -= pad
        hi += pad
        val left = 0f
        val right = size.width
        val top = 4.dp.toPx()
        val bottom = size.height - 14.dp.toPx()
        fun x(t: Long) = (left + (t - t0) / (t1 - t0) * (right - left)).toFloat()
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        val grid = cs.onSurface.copy(alpha = 0.08f)
        listOf(top, (top + bottom) / 2, bottom).forEach { gy -> drawLine(grid, Offset(left, gy), Offset(right, gy), 1.dp.toPx()) }
        drawText(measurer.measure(fmt(hi - pad, trend), axis), topLeft = Offset(left, bottom + 1.dp.toPx()))

        val line = Path()
        val area = Path()
        points.forEachIndexed { i, (t, v) ->
            val px = x(t)
            val py = y(v)
            if (i == 0) {
                line.moveTo(px, py)
                area.moveTo(px, bottom)
                area.lineTo(px, py)
            } else {
                line.lineTo(px, py)
                area.lineTo(px, py)
            }
        }
        area.lineTo(x(points.last().first), bottom)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.18f), Color.Transparent), top, bottom))
        drawPath(line, cs.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = points.last()
        drawCircle(cs.surface, 6.dp.toPx(), Offset(x(last.first), y(last.second)))
        drawCircle(cs.primary, 4.dp.toPx(), Offset(x(last.first), y(last.second)))

        scrubX?.let { sx ->
            val near = points.minBy { abs(x(it.first) - sx) }
            val px = x(near.first)
            val py = y(near.second)
            drawLine(cs.onSurfaceVariant.copy(alpha = 0.5f), Offset(px, top), Offset(px, bottom), 1.dp.toPx())
            drawCircle(cs.surface, 6.dp.toPx(), Offset(px, py))
            drawCircle(cs.secondary, 4.dp.toPx(), Offset(px, py))
            val label = measurer.measure("${fmt(near.second, trend)} · ${stamp.format(Date(near.first))}", tip)
            val lx = (px - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            val box = 4.dp.toPx()
            drawRoundRect(
                cs.surface, Offset(lx - box, top), Size(label.size.width + box * 2, label.size.height.toFloat()),
                CornerRadius(box),
            )
            drawText(label, topLeft = Offset(lx, top))
        }
    }
}

private fun fmt(v: Double, t: Trend): String =
    "%.${t.digits}f".format(Locale.ROOT, v) + if (t.unit.isEmpty()) "" else " ${t.unit}"
