package com.t1dm.core.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shared scroll-edge dissolve: content ramps to nothing over the last [FADE_EDGE_DEPTH] of a scrolling
 * panel instead of being cut off at the viewport's clip edge.
 *
 * The edge that matters is the bottom one. A panel's content column stops exactly at the top of the
 * nav bar, so anything still scrolling there was sliced by a razor-straight line — most visible on
 * Pubs, where a post's image is guillotined mid-picture, but the same cut lands on every list and
 * every settings row. The top edge has it too, against the screen title.
 *
 * **It has to be an alpha mask, not a scrim.** [ThemeBackdrop] paints a live motif behind every panel
 * at the user's opacity, so a gradient in the background colour would blank the motif inside the band
 * and trade a hard content edge for a hard *backdrop* edge. So the panel is composited into a layer
 * and the band is erased out of it with [BlendMode.DstIn], which leaves whatever is behind the panel
 * untouched.
 *
 * **The band grows with the scroll rather than switching on.** Its height is the distance actually
 * scrolled past that edge, clamped to [FADE_EDGE_DEPTH]: zero while the panel is parked at an end (so
 * content that genuinely ends there ends crisply, and the last row is never ghosted for no reason),
 * widening to the full ramp as content starts running past. The fade therefore means "there is more
 * this way" — the same thing [verticalScrollbar]'s thumb says, and it retracts smoothly on arrival
 * instead of popping.
 *
 * **Modifier order is load-bearing**, exactly as it is for [verticalScrollbar]: this must sit BEFORE
 * `verticalScroll` in the chain, so its [androidx.compose.ui.graphics.drawscope.DrawScope] size is the
 * VIEWPORT rather than the full content height and its coordinates do not translate with the scroll
 * offset. Put it after [verticalScrollbar] so the thumb draws outside the layer and stays crisp — it
 * rides in the corner where the fade is deepest, and an affordance that fades out with the content it
 * is reporting on is no affordance.
 *
 *     Modifier.fillMaxSize().verticalScrollbar(s).fadingEdges(s).verticalScroll(s).padding(16.dp)
 *
 * The [LazyListState] overload is the mirror image and goes on the `LazyColumn`'s own `modifier`,
 * which is already outside the lazy layout's internal scrollable.
 *
 * Cost: one offscreen buffer, and it is asked for ONLY on the frames a band is actually drawn — a
 * panel that does not overflow, or one sitting still at both ends, stays on
 * [CompositingStrategy.Auto] and pays for nothing beyond a bare RenderNode. Both the strategy and the
 * band heights are read inside a layer block and a draw lambda, never in composition, so a scroll
 * invalidates draw alone and never recomposes the panel — the discipline `Scrollbar.kt` and
 * `NavWheel.kt` already hold to.
 */

/**
 * The band's depth. Public because a panel that scrolls something INTO view has to clear it — see
 * `SettingsScaffold`, which lands a searched-for row below this rather than under the mask.
 */
val FADE_EDGE_DEPTH = 40.dp

// DstIn keeps the destination in proportion to the SOURCE's alpha, so only the mask's alpha carries
// meaning and its colour is arbitrary. Neither brush names its stops in pixels, so each resolves
// across the full draw scope — and the band is then squeezed down onto its edge by SCALING the
// canvas.
//
// The scale is doing real work; two more obvious constructions are both wrong. Passing the band as
// `drawRect`'s `size` does not bound the gradient at all — `drawRect` resolves an unbounded brush
// against the DRAW SCOPE's size, not against the rect handed to it, so the ramp spread over the whole
// panel and the band collapsed into a step down to `depth / panelHeight`. Drawing through `inset`
// bounds it correctly but makes the resolved size the BAND's, and a `ShaderBrush` caches exactly one
// native shader keyed on that size: because the band's height is the animated quantity, every frame
// of a ramp missed the cache and constructed a fresh `android.graphics.LinearGradient`. `scale` moves
// the canvas matrix and leaves the draw scope's size alone, so the shader is built once per viewport
// size and the ramp frames all hit it — which is the whole reason these are process-wide `val`s.
private val ERASE_DOWNWARD = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
private val ERASE_UPWARD = Brush.verticalGradient(listOf(Color.Black, Color.Transparent))

fun Modifier.fadingEdges(state: ScrollState, depth: Dp = FADE_EDGE_DEPTH): Modifier = fadingEdges(
    depth,
    // `maxValue` is Int.MAX_VALUE until the scroll node has measured; without the guard the first
    // frame of every panel — including the ones that do not overflow at all — would flash a band.
    top = { d -> if (isUnscrollable(state.maxValue)) 0f else state.value.toFloat().coerceAtMost(d) },
    bottom = { d ->
        if (isUnscrollable(state.maxValue)) 0f
        else (state.maxValue - state.value).toFloat().coerceAtMost(d)
    },
)

fun Modifier.fadingEdges(state: LazyListState, depth: Dp = FADE_EDGE_DEPTH): Modifier = fadingEdges(
    depth,
    top = { d ->
        if (state.firstVisibleItemIndex > 0) d
        else state.firstVisibleItemScrollOffset.toFloat().coerceAtMost(d)
    },
    bottom = { d ->
        // A lazy list knows no total height, so the overhang of the LAST visible item stands in for
        // "how much is left" — exact where it matters, which is the final item arriving at the edge.
        // Any earlier item still below it means there is more than a ramp's worth left regardless.
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        when {
            last == null -> 0f
            last.index < info.totalItemsCount - 1 -> d
            else -> (last.offset + last.size - info.viewportEndOffset).toFloat().coerceIn(0f, d)
        }
    },
)

private fun Modifier.fadingEdges(
    depth: Dp,
    top: (Float) -> Float,
    bottom: (Float) -> Float,
): Modifier = this
    .graphicsLayer {
        val d = depth.toPx()
        compositingStrategy =
            if (top(d) > 0f || bottom(d) > 0f) CompositingStrategy.Offscreen
            else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val d = depth.toPx()
        // Clamped to the viewport: a panel shorter than one band would otherwise be scaled by more
        // than 1 and left translucent everywhere, never reaching full opacity anywhere in view.
        val t = top(d).coerceAtMost(h)
        if (t > 0f) {
            scale(scaleX = 1f, scaleY = t / h, pivot = Offset.Zero) {
                drawRect(ERASE_DOWNWARD, blendMode = BlendMode.DstIn)
            }
        }
        val b = bottom(d).coerceAtMost(h)
        if (b > 0f) {
            scale(scaleX = 1f, scaleY = b / h, pivot = Offset(0f, h)) {
                drawRect(ERASE_UPWARD, blendMode = BlendMode.DstIn)
            }
        }
    }
