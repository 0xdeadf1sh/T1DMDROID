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

/**
 * A self-contained HSV colour picker: a saturation/value square, a hue slider and an alpha slider,
 * plus a row of swatches drawn from the ACTIVE theme so the first colours offered are ones that
 * already belong on the screen.
 *
 * Written from primitives rather than pulled in: the whole control is three `Canvas`es and one pointer
 * helper, so a third-party picker would be a transitive dependency bought for a gradient and a thumb.
 * It lives in `:core:design` because nothing about it is graph-specific — the BG panel's paint palette
 * is merely its first caller.
 *
 * The numeric kernel ([hsvToArgb] / [argbToHsv]) is deliberately Android-free and unit-tested: HSV
 * round-tripping is subtly wrong at exactly the edges a slider is dragged to — hue is undefined at
 * zero saturation, and 360° must fold onto 0°.
 */

// ── the pure kernel (no Android types; see ColorPickerTest) ────────────────────────────────────

/**
 * Pack an HSV colour into sRGB ARGB. [hue] is in degrees and may be any real value: it is folded into
 * `[0, 360)`, so a slider that overshoots its end wraps rather than sticking. [sat], [value] and
 * [alpha] are clamped to `[0, 1]`.
 */
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

/**
 * Decompose sRGB ARGB into `[hue°, saturation, value]`. Alpha is deliberately NOT returned — it is
 * carried by the picker's own slider (see [argbAlpha]); folding it in here would invite the classic
 * bug of a colour quietly losing its transparency on every round trip through the square. Hue is
 * undefined for a grey and reported as 0 rather than NaN, so the hue slider always has somewhere to sit.
 */
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

/** [argb] with its alpha replaced; the RGB triple is untouched. */
fun argbWithAlpha(argb: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    return (argb and 0x00FFFFFF) or (a shl 24)
}

// ── the control ────────────────────────────────────────────────────────────────────────────────

/**
 * The picker. Fully controlled — it holds no colour of its own, so the caller's state is the single
 * truth and an external change (a swatch tapped elsewhere, a tool's seeded alpha) lands at once.
 *
 * The hue is derived from [colorArgb] each frame rather than remembered, which has one consequence
 * worth stating: dragging saturation or value to zero makes the hue unrecoverable from the colour.
 * [hueOverride] lets the caller pin the hue the user last chose across such a pass through grey;
 * leaving it null accepts the slider snapping to red at `s = 0`.
 */
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
        // Saturation (x) × value (y): the standard construction — a white→hue ramp across, multiplied
        // by a transparent→black ramp down.
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

        // Hue: the wheel unrolled. Seven stops is exact — the sRGB hue ramp is piecewise linear in 60°
        // sectors, and the last stop closes the wrap back onto red.
        RampSlider(
            brush = Brush.horizontalGradient(List(7) { Color(hsvToArgb(it * 60f, 1f, 1f)) }),
            fraction = hue / 360f,
            // Nudge a fully-grey colour off the achromatic axis, or the hue slider would be inert.
            onFraction = { f ->
                onColorChange(hsvToArgb(f * 360f, sat.coerceAtLeast(0.02f), value.coerceAtLeast(0.02f), alpha))
            },
        )

        // Alpha over a checkerboard, so "transparent" reads as transparency rather than as the
        // surface colour behind the control.
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
                        // A swatch chooses hue/saturation/value ONLY — the alpha already dialled in (or
                        // seeded by the tool) survives, so picking a colour never silently turns a
                        // highlighter opaque.
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

/** One horizontal ramp with a thumb: the hue and alpha sliders differ only in their brush. */
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

/** The shared thumb: a white ring inside a dark one, so it survives on any underlying colour. */
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

/**
 * Report every pointer position from the touch-down onwards, with NO slop gate: a picker must land the
 * colour under the finger on contact, not after it has travelled far enough to be called a drag. The
 * lambda is read through [rememberUpdatedState] so the handler never restarts — and so never drops a
 * gesture — when the caller's state changes under it, which in a fully controlled picker is on every
 * sample.
 */
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

/**
 * Swatches derived from the ACTIVE theme: the glucose-band semantics first (a note about a hypo ought
 * to be able to be the same red the band is), then the Material accents. Alpha is stripped — a swatch
 * chooses a hue, never a transparency.
 */
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
