package com.t1dm.app.widget

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.t1dm.core.design.T1dmPalette
import com.t1dm.core.design.drawThemeBackground
import kotlin.math.max
import kotlin.math.roundToInt

/** Glance has no Canvas: motif rasterises offscreen, proportional, capped, FillBounds-rescaled. */
private const val MAX_DIM = 480

// Keyed on palette hash, not p.id: every custom theme shares id "custom" (else stale backdrop).
private data class BackdropKey(val paletteHash: Int, val w: Int, val h: Int, val alphaPct: Int)

/** Process-wide: theme, size and alpha rarely change, but Glance re-composes on every push. */
private val cache = LinkedHashMap<BackdropKey, Bitmap>()
private const val CACHE_CAP = 6

/** Opaque; null when motif is off (`alphaPct<=0`), size is degenerate, or rasterising fails. */
internal fun widgetBackdropBitmap(p: T1dmPalette, widthPx: Int, heightPx: Int, alphaPct: Int): Bitmap? {
    val a = (alphaPct / 100f)
    if (a <= 0f || widthPx <= 0 || heightPx <= 0) return null

    val longest = max(widthPx, heightPx)
    val scale = if (longest > MAX_DIM) MAX_DIM.toFloat() / longest else 1f
    val w = (widthPx * scale).roundToInt().coerceAtLeast(1)
    val h = (heightPx * scale).roundToInt().coerceAtLeast(1)

    val key = BackdropKey(p.hashCode(), w, h, alphaPct)
    synchronized(cache) { cache[key]?.let { return it } }

    val bmp = runCatching {
        val image = ImageBitmap(w, h)
        val canvas = Canvas(image)
        val size = Size(w.toFloat(), h.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, size) {
            drawRect(p.background)
            drawIntoCanvas { c ->
                val paint = Paint().apply { this.alpha = a.coerceIn(0f, 1f) }
                c.saveLayer(Rect(Offset.Zero, size), paint)
                drawThemeBackground(p)
                c.restore()
            }
        }
        image.asAndroidBitmap()
    }.getOrNull() ?: return null

    synchronized(cache) {
        if (cache.size >= CACHE_CAP) cache.clear()
        cache[key] = bmp
    }
    return bmp
}
