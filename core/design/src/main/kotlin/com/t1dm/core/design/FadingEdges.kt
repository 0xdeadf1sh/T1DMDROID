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
 * Modifier order is load-bearing: BEFORE `verticalScroll`, so the draw scope's size is the viewport
 * and its coordinates do not translate with the scroll; after [verticalScrollbar], so the thumb
 * draws outside the layer.
 */

/** Public: a panel that scrolls a row INTO view has to clear this. */
val FADE_EDGE_DEPTH = 40.dp

// DstIn reads only the source's alpha, so the mask's colour is arbitrary. The band is squeezed onto
// its edge by SCALING the canvas: `drawRect`'s size does not bound an unbounded brush, and `inset`
// makes the resolved size the band's, rebuilding the native shader on every frame of a ramp.
private val ERASE_DOWNWARD = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
private val ERASE_UPWARD = Brush.verticalGradient(listOf(Color.Black, Color.Transparent))

fun Modifier.fadingEdges(state: ScrollState, depth: Dp = FADE_EDGE_DEPTH): Modifier = fadingEdges(
    depth,
    // `maxValue` is Int.MAX_VALUE until the scroll node has measured.
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
        // A lazy list knows no total height; the last visible item's overhang stands in for what is left.
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
        // Clamped to the viewport: a shorter panel would scale past 1 and never reach full opacity.
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
