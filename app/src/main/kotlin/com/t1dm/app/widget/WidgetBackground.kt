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

/**
 * The widget backdrop: the SAME per-theme motif the app paints ([drawThemeBackground]) rasterised to a
 * bitmap so it can back a Glance tile. Glance renders to `RemoteViews`, whose fixed view vocabulary has
 * no `Canvas`, so the app's live Compose painter cannot run there; we drive the identical [DrawScope]
 * extension through an offscreen [CanvasDrawScope] and hand the result to `ImageProvider(bitmap)`.
 *
 * The composite mirrors the app exactly (see `T1dmApp`/`ThemeBackdrop`): an opaque [T1dmPalette.background]
 * base, then the painter drawn once at the user's `backgroundAlphaPct` over it — a group alpha, so the
 * painter's own base-fill is a no-op on the flat regions and only the motif is dimmed. The output is
 * fully opaque (no launcher wallpaper bleeds through to wreck contrast on the big BG read-out).
 *
 * Because the painters are purely proportional (geometry scales with `size`, no dp/px constants), the
 * raster is density-free: we cap the long edge to [MAX_DIM] preserving aspect and let Glance FillBounds
 * scale it up — a pure upscale of a soft wash, with the crisp content drawn as real views on top.
 */
private const val MAX_DIM = 480

// Keyed on the palette's full content hash (T1dmPalette is a data class, so hashCode covers every
// colour role), NOT just p.id — every custom theme shares id "custom", so an id-only key served a stale
// backdrop after the user edited the custom palette.
private data class BackdropKey(val paletteHash: Int, val w: Int, val h: Int, val alphaPct: Int)

/** Process-level cache: widget refreshes are frequent (the 30 s ticker) but theme/size/alpha rarely
 *  change, and Glance's per-update composition would otherwise re-rasterise every push. Tiny bitmaps
 *  (≤ MAX_DIM², ARGB), a handful of entries — evicted wholesale past the cap. */
private val cache = LinkedHashMap<BackdropKey, Bitmap>()
private const val CACHE_CAP = 6

/**
 * The opaque backdrop bitmap for [p] at [alphaPct] opacity, sized from the widget's pixel [widthPx] ×
 * [heightPx] (capped). Null when the motif is off (`alphaPct <= 0`) — the caller then uses a flat base —
 * or when the size is degenerate / rasterisation fails.
 */
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
