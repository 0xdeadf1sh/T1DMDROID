package com.t1dm.feature.twin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.KeyValueRow
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinSource
import com.t1dm.core.model.TwinTrait
import java.util.Locale

/** [latest] null: no scan yet. The silhouette is a placeholder until the 3D twin lands. */
@Composable
fun TwinScreen(latest: TwinScan?, scanCount: Int, onMockScan: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberT1dmHaptics()
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val bmi = latest?.let { s ->
            val h = s[TwinTrait.HEIGHT] ?: return@let null
            val w = s[TwinTrait.WEIGHT] ?: return@let null
            w / ((h / 100) * (h / 100))
        }
        Canvas(Modifier.fillMaxWidth().height(320.dp)) {
            drawSilhouette(widthScale = bmi?.let { (it / 22.0).toFloat().coerceIn(0.8f, 1.4f) } ?: 1f, line = cs.primary)
        }
        if (latest == null) {
            Text("No scan yet", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (latest.source == TwinSource.MOCK) "MOCK" else latest.source.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.secondary,
                )
                Text("$scanCount scans", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            Column(Modifier.fillMaxWidth()) {
                for (t in TwinTrait.entries) KeyValueRow(t.label, latest[t]?.let { format(t, it) }, numeric = true)
            }
        }
        Button(onClick = { haptics.perform(HapticEvent.Tap); onMockScan() }) { Text("Mock scan") }
        Spacer(Modifier.height(8.dp))
    }
}

private fun format(t: TwinTrait, v: Double): String = when (t) {
    TwinTrait.SEX, TwinTrait.MUSCLE -> "%.2f".format(Locale.ROOT, v)
    TwinTrait.SURVIVAL_1Y, TwinTrait.SURVIVAL_5Y, TwinTrait.SURVIVAL_10Y, TwinTrait.SURVIVAL_20Y ->
        "%.1f %%".format(Locale.ROOT, v * 100)
    else -> "%.1f %s".format(Locale.ROOT, v, t.unit)
}

/** A holographic stand-in body on a 100×200 grid; [widthScale] widens the trunk and limbs. */
private fun DrawScope.drawSilhouette(widthScale: Float, line: Color) {
    val u = minOf(size.width / 100f, size.height / 200f)
    val ox = (size.width - 100f * u) / 2f
    fun p(x: Float, y: Float) = Offset(ox + (50f + (x - 50f) * widthScale) * u, y * u)
    val body = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(p(42f, 4f), p(58f, 26f)))
        moveTo(p(46f, 26f).x, p(46f, 26f).y)
        listOf(
            p(54f, 26f), p(55f, 31f), p(70f, 35f), p(78f, 90f), p(72f, 92f), p(64f, 50f),
            p(63f, 98f), p(62f, 192f), p(53f, 192f), p(50f, 112f), p(47f, 192f), p(38f, 192f),
            p(37f, 98f), p(36f, 50f), p(28f, 92f), p(22f, 90f), p(30f, 35f), p(45f, 31f),
        ).forEach { lineTo(it.x, it.y) }
        close()
    }
    clipPath(body) {
        var y = 0f
        while (y < size.height) {
            drawLine(line.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += 6f
        }
    }
    drawPath(body, line.copy(alpha = 0.25f), style = Stroke(width = 6f))
    drawPath(body, line, style = Stroke(width = 1.5f))
}
