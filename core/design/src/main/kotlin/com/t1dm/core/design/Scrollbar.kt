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
 * Modifier order is load-bearing for the [ScrollState] overload: BEFORE `verticalScroll` in the chain.
 * Placed after, the draw node nests inside the scroll node, so its [DrawScope] size is the whole
 * content height and the thumb scrolls away with the content.
 */

private const val FADE_IN_MS = 120
private const val FADE_OUT_MS = 380
private const val IDLE_HOLD_MS = 700L

private val DEFAULT_WIDTH = 4.dp
private val DEFAULT_MIN_THUMB = 24.dp
private val DEFAULT_END_INSET = 2.dp

/** Pixels, relative to the viewport's top edge. */
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
        // Paired, so a maxValue change alone — the first measure, or content growing — also flashes.
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

/** [position] returns null while the content fits, which SNAPS the thumb away rather than fading it.
 *  The value is read from the draw lambda alone, so the panel around it never recomposes. */
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
            // collectLatest cancels on the next position, so the hold elapses only after the fling.
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

/** [ScrollState.maxValue] is `Int.MAX_VALUE` until the scroll node has measured. */
internal fun isUnscrollable(maxScrollPx: Int): Boolean =
    maxScrollPx <= 0 || maxScrollPx == Int.MAX_VALUE

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

/** The proportion is taken over item COUNTS — a lazy list knows no pixel height — so it is
 *  approximate wherever items differ in height. */
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
