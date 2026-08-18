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
 * The single lever that makes the "disable all animations" setting real (issue 17). Motion in the app
 * flows through three doors, and each reads [LocalAnimationsEnabled] via a helper here so the flag
 * genuinely collapses everything to a snap. A fourth door is not motion at all — see below:
 *
 *  1. **Screen transitions** — the `NavHost` enter/exit/pop specs (the crossfade the user sees on
 *     every tab change) come from [navEnter]/[navExit], which return [EnterTransition.None] /
 *     [ExitTransition.None] when motion is off.
 *  2. **Value/spec animations** — any `animate*AsState`/`AnimatedVisibility` spec should be built with
 *     [motionSpec], which returns [snap] when motion is off. [crossfadeOnSwap] is the same door for a
 *     surface whose whole contents are replaced at once.
 *  3. **Imperative scrolls** — call sites branch on [LocalAnimationsEnabled] to `scrollTo` instead of
 *     `animateScrollTo` (the bottom-nav auto-centre).
 *
 * Decorative/looping motion is simply not started when the flag is off.
 */

const val DEFAULT_MOTION_MS = 220

@Composable
@ReadOnlyComposable
fun animationsOn(): Boolean = LocalAnimationsEnabled.current

/** A finite spec that becomes an instant [snap] when motion is disabled. */
fun <T> motionSpec(enabled: Boolean, durationMs: Int = DEFAULT_MOTION_MS): FiniteAnimationSpec<T> =
    if (enabled) tween(durationMs) else snap()

fun navEnter(enabled: Boolean): EnterTransition =
    if (enabled) fadeIn(tween(DEFAULT_MOTION_MS)) else EnterTransition.None

fun navExit(enabled: Boolean): ExitTransition =
    if (enabled) fadeOut(tween(DEFAULT_MOTION_MS)) else ExitTransition.None

/**
 * Cross-dissolve this node's contents whenever [key] changes — the BG panel's chart, when the bottom
 * bar steps to another sensor. The outgoing picture and the incoming one are on screen together at
 * complementary alpha, so neither passes through the background.
 *
 * **It does not compose the content twice.** The outgoing picture is a [GraphicsLayer] recording — the
 * display list built for the frame before the swap — replayed beside the live one. Two layers,
 * ping-ponged on each swap so the one holding the pre-swap recording is never the one being written.
 *
 * **The recording must OWN its drawing commands.** Giving the content a layer of its own underneath
 * would make `record` cheap — a RenderNode replay rather than a re-run of the content's draw — but
 * both recordings would then merely reference that one shared RenderNode, so the held "outgoing"
 * picture would update to the incoming content the instant it changed and the dissolve would blend the
 * new picture with itself. The cost of a real snapshot is that a dissolve re-runs the content's draw
 * once per frame, for its ~13 frames. A caller with its own fade should keep it in a `graphicsLayer`
 * OUTSIDE this modifier, where an alpha change stays a layer-property update and never reaches here.
 *
 * A swap is a change between two KNOWN keys: the key's flow is seeded null, and dissolving that
 * placeholder into the first real value would fade the surface in on every cold start.
 *
 * With motion off this collapses to the bare receiver — no layers, no draw-phase work at all.
 * "Disabled" has to mean absent, not merely invisible.
 */
@Composable
fun Modifier.crossfadeOnSwap(key: Any?): Modifier {
    if (!animationsOn()) return this
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    val progress = remember { Animatable(1f) }
    val swap = remember { SwapCrossfade() }
    // Armed in COMPOSITION, so the first draw under a new key already knows not to overwrite the
    // outgoing recording; effect-versus-draw ordering within a frame is not something to bet on.
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
            // Read UNCONDITIONALLY, before any branch. A draw pass re-collects its snapshot reads from
            // scratch, so a pass that branches AROUND this read unsubscribes the node from the
            // animation — and the arming pass, which always runs before the effect that starts it, is
            // exactly such a pass. Nothing would invalidate the draw again: the flag below is a plain
            // field and every animation frame would land on no observer, so the dissolve would freeze
            // on the outgoing picture until some unrelated redraw cut it away.
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
                // Both halves inside ONE offscreen, the incoming one ADDED rather than laid over.
                //
                // Drawn straight onto the canvas, two source-over layers at t and 1 - t cover
                // `a + b - ab`, not `a + b`: they lose `t(1 - t)` of the node's own coverage, worst at
                // the midpoint, and anything solid enough to notice — the read-out's bold figures —
                // dips through the middle of every dissolve and reads as a flash. Added inside a layer
                // the coverages sum exactly, and the colour is the plain `old(1 - t) + new·t` a
                // dissolve is supposed to be. The offscreen also CLIPS, which keeps a recording made
                // at a different height from spilling outside the node while the two sizes disagree.
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

/** [crossfadeOnSwap]'s cross-draw state. Plain fields, not snapshot state: none of it is a fact
 *  anything may recompose on, and the draw phase is invalidated by [Animatable] alone. */
private class SwapCrossfade {
    var last: Any? = null
    var pending = false
    var flip = false
}
