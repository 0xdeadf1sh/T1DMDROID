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
 * A one-shot "this is the row you asked for" highlight — the generalisation of the dashboard's
 * private data-movement flash, raised into `:core:design` for the Settings search (which scrolls a
 * matched knob into view and must then say *which* row it landed on).
 *
 * It is the fourth door motion flows through (Motion.kt enumerates the other three) and the one place
 * where [motionSpec] would be actively wrong: with motion off it returns `snap()`, and a snapped 1→0
 * fade is invisible — the highlight would silently do nothing exactly when the user has asked for a
 * static UI. So the disabled branch is not an animation at all but a STATIC tint held for
 * [PULSE_HIGHLIGHT_MS] and then cleared; the row is still marked, it simply does not breathe.
 *
 * The tint is the theme accent, not the neutral ink the press ripple was pinned to under U3. That fix
 * was about an *accidental* red rectangle under every tap; this mark is deliberate, momentary, and its
 * whole job is to be found, so it belongs to the accent the rest of the theme answers to.
 */

private const val PULSE_CYCLES = 2
private const val PULSE_RISE_MS = 260
private const val PULSE_FALL_MS = 340
private const val PULSE_PEAK_ALPHA = 0.30f

/** How long a highlight stays lit, motion on or off — the caller's cue for when it may release. */
const val PULSE_HIGHLIGHT_MS: Long = ((PULSE_RISE_MS + PULSE_FALL_MS) * PULSE_CYCLES).toLong()

/**
 * Paint a transient accent wash behind this node while [active], then fade (or, with motion off,
 * blink) back to nothing. [inset] is negative to bleed the wash OUTSIDE the node, so a dense settings
 * row reads as a highlighted band rather than a tight box drawn inside its own padding.
 */
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
