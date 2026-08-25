package com.t1dm.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** HSV colour picker. The numeric kernel ([hsvToArgb] / [argbToHsv]) is deliberately Android-free
 *  and unit-tested: hue is undefined at zero saturation, and 360° must fold onto 0°. */

/** [hue] is in degrees, folded into `[0, 360)`; [sat], [value] and [alpha] are clamped to `[0, 1]`. */
fun hsvToArgb(hue: Float, sat: Float, value: Float, alpha: Float = 1f): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = sat.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val sector = (h / 60f).toInt()
    val r1: Float; val g1: Float; val b1: Float
    when (sector) {
        0 -> { r1 = c; g1 = x; b1 = 0f }
        1 -> { r1 = x; g1 = c; b1 = 0f }
        2 -> { r1 = 0f; g1 = c; b1 = x }
        3 -> { r1 = 0f; g1 = x; b1 = c }
        4 -> { r1 = x; g1 = 0f; b1 = c }
        else -> { r1 = c; g1 = 0f; b1 = x }
    }
    fun chan(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
    val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (chan(r1) shl 16) or (chan(g1) shl 8) or chan(b1)
}

/** `[hue°, saturation, value]`. Alpha is deliberately NOT returned; the picker's own slider carries
 *  it (see [argbAlpha]). Hue is undefined for a grey and reported as 0, never NaN. */
fun argbToHsv(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf(((h % 360f) + 360f) % 360f, if (max == 0f) 0f else d / max, max)
}

/** The alpha channel of [argb] as `[0, 1]`. */
fun argbAlpha(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f

fun argbWithAlpha(argb: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    return (argb and 0x00FFFFFF) or (a shl 24)
}

/** Fully controlled; holds no colour of its own. The hue is derived from [colorArgb] each frame, so
 *  it is unrecoverable once saturation or value reaches zero — [hueOverride] pins the last chosen
 *  hue across that pass through grey, and null accepts the slider snapping to red. */
@Composable
fun ColorPicker(
    colorArgb: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hueOverride: Float? = null,
    swatches: List<Int> = themeSwatches(),
) {
    val hsv = argbToHsv(colorArgb)
    val hue = hueOverride ?: hsv[0]
    val sat = hsv[1]
    val value = hsv[2]
    val alpha = argbAlpha(colorArgb)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Saturation (x) × value (y).
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .trackPointer { pos, bounds ->
                    val s = (pos.x / bounds.width).coerceIn(0f, 1f)
                    val v = 1f - (pos.y / bounds.height).coerceIn(0f, 1f)
                    onColorChange(hsvToArgb(hue, s, v, alpha))
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvToArgb(hue, 1f, 1f)))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            thumb(Offset(sat * size.width, (1f - value) * size.height))
        }

        // Seven stops is exact: the sRGB hue ramp is piecewise linear in 60° sectors, and the last
        // closes the wrap back onto red.
        RampSlider(
            brush = Brush.horizontalGradient(List(7) { Color(hsvToArgb(it * 60f, 1f, 1f)) }),
            fraction = hue / 360f,
            // Nudge a fully-grey colour off the achromatic axis, or the hue slider would be inert.
            onFraction = { f ->
                onColorChange(hsvToArgb(f * 360f, sat.coerceAtLeast(0.02f), value.coerceAtLeast(0.02f), alpha))
            },
        )

        // Over a checkerboard, so "transparent" reads as transparency.
        RampSlider(
            brush = Brush.horizontalGradient(
                listOf(Color(argbWithAlpha(colorArgb, 0f)), Color(argbWithAlpha(colorArgb, 1f))),
            ),
            fraction = alpha,
            checker = true,
            onFraction = { f -> onColorChange(argbWithAlpha(colorArgb, f)) },
        )

        Text(
            "#%08X".format(colorArgb),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.forEach { s ->
                val selected = (s and 0x00FFFFFF) == (colorArgb and 0x00FFFFFF)
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(argbWithAlpha(s, 1f)))
                        // Hue/saturation/value ONLY: the alpha already dialled in survives.
                        .hapticClickable(HapticEvent.Tap) { onColorChange(argbWithAlpha(s, alpha)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (argbToHsv(s)[2] > 0.6f) Color.Black else Color.White),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RampSlider(
    brush: Brush,
    fraction: Float,
    onFraction: (Float) -> Unit,
    checker: Boolean = false,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .trackPointer { pos, bounds -> onFraction((pos.x / bounds.width).coerceIn(0f, 1f)) },
    ) {
        if (checker) checkerboard()
        drawRect(brush)
        thumb(Offset(fraction.coerceIn(0f, 1f) * size.width, size.height / 2f))
    }
}

/** A white ring inside a dark one, so it survives on any underlying colour. */
private fun DrawScope.thumb(at: Offset) {
    drawCircle(Color.Black.copy(alpha = 0.55f), 8.5f, at, style = Stroke(width = 3f))
    drawCircle(Color.White, 8.5f, at, style = Stroke(width = 2f))
}

private fun DrawScope.checkerboard(cell: Float = 7f) {
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    for (y in 0 until rows) {
        for (x in 0 until cols) {
            drawRect(
                if ((x + y) % 2 == 0) Color(0xFF9E9E9E) else Color(0xFFE0E0E0),
                topLeft = Offset(x * cell, y * cell),
                size = Size(cell, cell),
            )
        }
    }
}

/** Every pointer position from the touch-down onwards, with NO slop gate. The lambda is read through
 *  [rememberUpdatedState] so the handler never restarts, and so never drops a gesture. */
@Composable
private fun Modifier.trackPointer(onPos: (Offset, Size) -> Unit): Modifier {
    val current by rememberUpdatedState(onPos)
    return this.pointerInput(Unit) {
        val bounds = Size(size.width.toFloat(), size.height.toFloat())
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            current(down.position, bounds)
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val active = event.changes.firstOrNull { it.pressed } ?: break
                current(active.position, bounds)
                event.changes.forEach { if (it.pressed) it.consume() }
            }
        }
    }
}

/** From the ACTIVE theme: glucose-band semantics first, then the Material accents. Alpha is stripped. */
@Composable
fun themeSwatches(): List<Int> {
    val p = LocalT1dmSemantics.current
    val cs = MaterialTheme.colorScheme
    return remember(p, cs) {
        listOf(
            p.urgentLow, p.low, p.inRange, p.high, p.urgentHigh,
            cs.primary, cs.secondary, cs.tertiary, cs.onSurface,
        ).map { argbWithAlpha(it.toArgb(), 1f) }
    }
}
