package com.t1dm.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The shared vertical scrollbar (N11). Compose ships an indicator for neither [ScrollState] nor
 * [LazyListState], so every panel that overflows does so with no evidence that it overflows — the
 * defect the N10 containment fix exposed on Meals/Insulin, where content silently ran past the
 * viewport. It lives in `:core:design` (generalizing the private copy the meal builder's food browser
 * grew under N8) so every panel draws the SAME thumb rather than a handful of drifting hand-rolled
 * ones, and so the thumb is tinted from the active palette instead of a literal.
 *
 * Behaviour: a thin rounded thumb that fades in on scroll and back out once the gesture settles,
 * plus a single flash the moment the content is first measured as overflowing — the affordance that
 * says "there is more below" without leaving a permanent stripe over the content. Both fades run
 * through [motionSpec], so the global "disable all animations" toggle collapses them to an instant
 * show/hide rather than defeating the indicator.
 *
 * **Modifier order is load-bearing** for the [ScrollState] overload: it must sit BEFORE
 * `verticalScroll` in the chain —
 * `Modifier.fillMaxSize().verticalScrollbar(s).verticalScroll(s).padding(16.dp)`.
 * Placed after, the draw node is nested inside the scroll node, so its [DrawScope] size is the whole
 * CONTENT height and its coordinates translate with the scroll offset: the thumb would scroll away
 * with the content and be clipped. The [LazyListState] overload is the mirror image — it goes on the
 * `LazyColumn`'s own `modifier`, which is already outside the lazy layout's internal scrollable.
 *
 * The scroll position is read INSIDE the draw lambda, never in composition, so dragging invalidates
 * draw only and never recomposes the panel.
 */

private const val FADE_IN_MS = 120
private const val FADE_OUT_MS = 380
private const val IDLE_HOLD_MS = 700L

private val DEFAULT_WIDTH = 4.dp
private val DEFAULT_MIN_THUMB = 24.dp
private val DEFAULT_END_INSET = 2.dp

/** A visible thumb, in pixels, relative to the viewport's top edge. */
internal data class ScrollbarThumb(val topPx: Float, val heightPx: Float)

@Composable
fun Modifier.verticalScrollbar(
    state: ScrollState,
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
    width: Dp = DEFAULT_WIDTH,
    minThumbHeight: Dp = DEFAULT_MIN_THUMB,
    endInset: Dp = DEFAULT_END_INSET,
): Modifier {
    val alpha = rememberThumbAlpha {
        // Pair so a maxValue change alone (the first measure, or content growing/shrinking past the
        // viewport) also drives a flash — not just a change in scroll offset.
        if (isUnscrollable(state.maxValue)) null else state.maxValue to state.value
    }
    return drawWithContent {
        drawContent()
        val a = alpha.value
        if (a <= 0f) return@drawWithContent
        val thumb = scrollThumb(size.height, state.maxValue, state.value, minThumbHeight.toPx())
            ?: return@drawWithContent
        drawThumb(thumb, color, a, width.toPx(), endInset.toPx())
    }
}

@Composable
fun Modifier.verticalScrollbar(
    state: LazyListState,
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
    width: Dp = DEFAULT_WIDTH,
    minThumbHeight: Dp = DEFAULT_MIN_THUMB,
    endInset: Dp = DEFAULT_END_INSET,
): Modifier {
    val alpha = rememberThumbAlpha {
        val info = state.layoutInfo
        if (info.visibleItemsInfo.size >= info.totalItemsCount) null
        else state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
    }
    return drawWithContent {
        drawContent()
        val a = alpha.value
        if (a <= 0f) return@drawWithContent
        val info = state.layoutInfo
        val thumb = lazyThumb(
            viewportPx = size.height,
            totalItems = info.totalItemsCount,
            firstVisibleIndex = state.firstVisibleItemIndex,
            visibleCount = info.visibleItemsInfo.size,
            minThumbPx = minThumbHeight.toPx(),
        ) ?: return@drawWithContent
        drawThumb(thumb, color, a, width.toPx(), endInset.toPx())
    }
}

/**
 * The fade envelope, held in an [Animatable] rather than plain state so the panel that owns the
 * scroll column is never recomposed by it: the value is read from the draw lambda alone.
 * [position] returns null while the content fits, which snaps the thumb away instead of fading it —
 * a bar that fades out because the content shrank would read as a scroll that never happened.
 */
@Composable
private fun rememberThumbAlpha(position: () -> Any?): State<Float> {
    val motion = animationsOn()
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(alpha, motion) {
        snapshotFlow(position).collectLatest { p ->
            if (p == null) {
                alpha.snapTo(0f)
                return@collectLatest
            }
            alpha.animateTo(1f, motionSpec(motion, FADE_IN_MS))
            // collectLatest cancels this the instant the next scroll position lands, so the hold only
            // elapses once the gesture (and its fling) has actually settled.
            delay(IDLE_HOLD_MS)
            alpha.animateTo(0f, motionSpec(motion, FADE_OUT_MS))
        }
    }
    return alpha.asState()
}

private fun DrawScope.drawThumb(
    thumb: ScrollbarThumb,
    color: Color,
    fade: Float,
    widthPx: Float,
    endInsetPx: Float,
) {
    drawRoundRect(
        color = color.copy(alpha = color.alpha * fade),
        topLeft = Offset(size.width - widthPx - endInsetPx, thumb.topPx),
        size = Size(widthPx, thumb.heightPx),
        cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
    )
}

/** [ScrollState.maxValue] is `Int.MAX_VALUE` until the scroll node has measured, so an unmeasured
 *  column is indistinguishable from an infinitely long one unless it is excluded explicitly. */
internal fun isUnscrollable(maxScrollPx: Int): Boolean =
    maxScrollPx <= 0 || maxScrollPx == Int.MAX_VALUE

/** Pixel-exact thumb for a [ScrollState]: the viewport's share of the content, positioned by how
 *  much of the scrollable range has been consumed. */
internal fun scrollThumb(
    viewportPx: Float,
    maxScrollPx: Int,
    scrollPx: Int,
    minThumbPx: Float,
): ScrollbarThumb? {
    if (viewportPx <= 0f || isUnscrollable(maxScrollPx)) return null
    return thumb(
        viewportPx = viewportPx,
        visibleFraction = viewportPx / (viewportPx + maxScrollPx),
        progress = scrollPx.toFloat() / maxScrollPx,
        minThumbPx = minThumbPx,
    )
}

/** Item-counted thumb for a [LazyListState]: a lazy list knows neither its total pixel height nor its
 *  scroll offset, so the proportion is taken over item counts. It is therefore approximate whenever
 *  items differ in height — good enough for a position hint, and the only estimate available without
 *  measuring every item. */
internal fun lazyThumb(
    viewportPx: Float,
    totalItems: Int,
    firstVisibleIndex: Int,
    visibleCount: Int,
    minThumbPx: Float,
): ScrollbarThumb? {
    if (viewportPx <= 0f || visibleCount <= 0 || visibleCount >= totalItems) return null
    return thumb(
        viewportPx = viewportPx,
        visibleFraction = visibleCount.toFloat() / totalItems,
        progress = firstVisibleIndex.toFloat() / (totalItems - visibleCount),
        minThumbPx = minThumbPx,
    )
}

private fun thumb(
    viewportPx: Float,
    visibleFraction: Float,
    progress: Float,
    minThumbPx: Float,
): ScrollbarThumb {
    val height = (viewportPx * visibleFraction)
        .coerceIn(minThumbPx.coerceAtMost(viewportPx), viewportPx)
    return ScrollbarThumb(topPx = (viewportPx - height) * progress.coerceIn(0f, 1f), heightPx = height)
}
