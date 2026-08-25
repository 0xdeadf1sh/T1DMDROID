package com.t1dm.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The one place [motionSpec] would be actively wrong: with motion off it returns `snap()`, and a
 * snapped 1→0 fade is invisible. So the disabled branch is a STATIC tint held for
 * [PULSE_HIGHLIGHT_MS], not an animation.
 */

private const val PULSE_CYCLES = 2
private const val PULSE_RISE_MS = 260
private const val PULSE_FALL_MS = 340
private const val PULSE_PEAK_ALPHA = 0.30f

/** How long a highlight stays lit, motion on or off. */
const val PULSE_HIGHLIGHT_MS: Long = ((PULSE_RISE_MS + PULSE_FALL_MS) * PULSE_CYCLES).toLong()

/** [inset] is negative to bleed the wash OUTSIDE the node. */
@Composable
fun Modifier.pulseHighlight(
    active: Boolean,
    color: Color = MaterialTheme.colorScheme.primary,
    inset: Dp = (-6).dp,
    corner: Dp = 10.dp,
): Modifier {
    val motion = LocalAnimationsEnabled.current
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active, motion) {
        if (!active) {
            alpha.snapTo(0f)
            return@LaunchedEffect
        }
        if (motion) {
            repeat(PULSE_CYCLES) {
                alpha.animateTo(PULSE_PEAK_ALPHA, tween(PULSE_RISE_MS))
                alpha.animateTo(0f, tween(PULSE_FALL_MS))
            }
        } else {
            alpha.snapTo(PULSE_PEAK_ALPHA)
            delay(PULSE_HIGHLIGHT_MS)
            alpha.snapTo(0f)
        }
    }
    // Read inside the draw lambda, never in composition: a running pulse invalidates draw only.
    return this.drawBehind {
        val a = alpha.value
        if (a <= 0f) return@drawBehind
        val i = inset.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(i, i),
            size = Size(size.width - 2f * i, size.height - 2f * i),
            cornerRadius = CornerRadius(corner.toPx()),
            alpha = a,
        )
    }
}
