package com.t1dm.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/** Preview: values[i] is rate over [i·5,(i+1)·5) min, drawn at slot i+1; slot 0 zero baseline. */
@Composable
fun CurveSparkline(values: DoubleArray, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp)) {
        if (values.isEmpty()) return@Canvas
        val peak = values.maxOrNull()?.toFloat() ?: return@Canvas
        if (peak <= 0f) return@Canvas
        val n = values.size
        val dx = size.width / n
        val path = Path().apply {
            moveTo(0f, size.height)
            for (i in 0 until n) {
                lineTo((i + 1) * dx, size.height - (values[i].toFloat() / peak) * size.height * 0.9f)
            }
            lineTo(n * dx, size.height)
            close()
        }
        drawPath(path, color.copy(alpha = 0.25f))
    }
}
