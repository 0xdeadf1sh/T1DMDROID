package com.t1dm.core.design

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * Every path motion takes reads [LocalAnimationsEnabled]: specs through [motionSpec], nav transitions
 * through [navEnter]/[navExit], imperative scrolls by branching at the call site. Decorative and
 * looping motion is simply not started when the flag is off.
 */

const val DEFAULT_MOTION_MS = 220

@Composable
@ReadOnlyComposable
fun animationsOn(): Boolean = LocalAnimationsEnabled.current

fun <T> motionSpec(enabled: Boolean, durationMs: Int = DEFAULT_MOTION_MS): FiniteAnimationSpec<T> =
    if (enabled) tween(durationMs) else snap()

fun navEnter(enabled: Boolean): EnterTransition =
    if (enabled) fadeIn(tween(DEFAULT_MOTION_MS)) else EnterTransition.None

fun navExit(enabled: Boolean): ExitTransition =
    if (enabled) fadeOut(tween(DEFAULT_MOTION_MS)) else ExitTransition.None

/**
 * Cross-dissolve this node's contents when [key] changes. The recording must OWN its draw commands: a
 * layer under the content would leave both recordings referencing one RenderNode and blend the new
 * picture with itself, so a caller's own fade belongs in a `graphicsLayer` OUTSIDE this modifier.
 */
@Composable
fun Modifier.crossfadeOnSwap(key: Any?): Modifier {
    if (!animationsOn()) return this
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    val progress = remember { Animatable(1f) }
    val swap = remember { SwapCrossfade() }
    // Armed in COMPOSITION: the first draw under a new key must already know not to overwrite the
    // outgoing recording. Effect-versus-draw ordering within a frame is not safe to bet on.
    remember(key) {
        if (swap.last != null && key != null) {
            swap.pending = true
            swap.flip = !swap.flip
        }
        swap.last = key
    }
    LaunchedEffect(key) {
        if (!swap.pending) return@LaunchedEffect
        progress.snapTo(0f)
        swap.pending = false
        progress.animateTo(1f, tween(DEFAULT_MOTION_MS))
    }
    return this
        .drawWithContent {
            // Read UNCONDITIONALLY, before any branch: a draw pass re-collects its snapshot reads, so
            // one that branches around this read unsubscribes the node from the animation and freezes
            // the dissolve on the outgoing picture.
            val p = progress.value
            val t = if (swap.pending) 0f else p
            val live = if (swap.flip) second else first
            val held = if (swap.flip) first else second
            live.record { this@drawWithContent.drawContent() }
            if (t >= 1f) {
                live.alpha = 1f
                live.blendMode = BlendMode.SrcOver
                drawLayer(live)
            } else {
                // Both halves inside ONE offscreen, the incoming one ADDED. Two source-over layers at
                // t and 1 - t cover `a + b - ab`, losing `t(1 - t)` of coverage and flashing at the
                // midpoint; added inside a layer they sum exactly. The offscreen also clips.
                held.alpha = 1f - t
                held.blendMode = BlendMode.SrcOver
                live.alpha = t
                live.blendMode = BlendMode.Plus
                drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                drawLayer(held)
                drawLayer(live)
                drawContext.canvas.restore()
            }
        }
}

/** Plain fields, not snapshot state: nothing here may recompose, and [Animatable] alone invalidates
 *  the draw. */
private class SwapCrossfade {
    var last: Any? = null
    var pending = false
    var flip = false
}
