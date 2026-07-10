package com.t1dm.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The per-theme nav + launcher icon system (issues 2 + 6). One glyph silhouette per destination, but
 * the GEOMETRY — not merely the colour — is re-derived from the active theme:
 *
 *  - **Tron Legacy** — thin, angular, circuit-like: the silhouette is *stroked* with a fine line and
 *    hard mitred joins (sharp corners), so the icons read as etched conduit.
 *  - **Umbrella Corp** — blocky, hard-edged, hazard-ish: the silhouette is *filled* solid with square
 *    caps/joins, giving heavy, stamped, corporate marks.
 *  - **Hello Kitty** — rounded, soft, cute: the silhouette is *stroked* with a thicker line and fully
 *    rounded caps/joins, so every corner softens.
 *
 * Fill-vs-stroke + sharp-vs-round is a real geometric transform of the same path, coherent across the
 * whole set, and legible small in both light and dark palettes (the [ImageVector] is drawn white and
 * tinted by the caller's `LocalContentColor`, so contrast is the theme's job).
 */
enum class IconStyle { TRON, UMBRELLA, KITTY }

fun iconStyleForTheme(themeId: String?): IconStyle = when (themeId) {
    ThemeIds.UMBRELLA -> IconStyle.UMBRELLA
    ThemeIds.HELLO_KITTY -> IconStyle.KITTY
    else -> IconStyle.TRON // Tron default; a custom theme borrows the Tron geometry.
}

private data class Geo(
    val stroke: Boolean,
    val strokeWidth: Float,
    val cap: StrokeCap,
    val join: StrokeJoin,
)

private fun geo(style: IconStyle): Geo = when (style) {
    IconStyle.TRON -> Geo(stroke = true, strokeWidth = 1.5f, cap = StrokeCap.Butt, join = StrokeJoin.Miter)
    IconStyle.UMBRELLA -> Geo(stroke = false, strokeWidth = 0f, cap = StrokeCap.Square, join = StrokeJoin.Miter)
    IconStyle.KITTY -> Geo(stroke = true, strokeWidth = 2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
}

/** Build a 24dp icon from a closed silhouette, rendered fill-or-stroke per [style]. Every glyph is a
 *  closed shape so it reads both filled (Umbrella) and stroked (Tron/Kitty). */
private fun glyph(name: String, style: IconStyle, body: PathBuilder.() -> Unit): ImageVector {
    val g = geo(style)
    val white = SolidColor(Color.White)
    return ImageVector.Builder(
        name = "t1dm_${name}_${style.name.lowercase()}",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        if (g.stroke) {
            path(
                stroke = white,
                strokeLineWidth = g.strokeWidth,
                strokeLineCap = g.cap,
                strokeLineJoin = g.join,
                pathBuilder = body,
            )
        } else {
            path(fill = white, pathBuilder = body)
        }
    }.build()
}

// ── The eleven destination silhouettes ───────────────────────────────────────────────────────────

private fun dashboard(s: IconStyle) = glyph("bg", s) { // a droplet
    moveTo(12f, 3f); curveTo(16f, 9f, 18f, 12f, 18f, 15f)
    arcTo(6f, 6f, 0f, true, true, 6f, 15f)
    curveTo(6f, 12f, 8f, 9f, 12f, 3f); close()
}

private fun clock(s: IconStyle) = glyph("clock", s) { // a ring (annulus)
    moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 11.99f, 3f); close()
    moveTo(12f, 7f); arcTo(5f, 5f, 0f, true, false, 12.01f, 7f); close()
}

private fun stats(s: IconStyle) = glyph("stats", s) { // three bars
    moveTo(4f, 13f); lineTo(8f, 13f); lineTo(8f, 20f); lineTo(4f, 20f); close()
    moveTo(10f, 8f); lineTo(14f, 8f); lineTo(14f, 20f); lineTo(10f, 20f); close()
    moveTo(16f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(16f, 20f); close()
}

private fun models(s: IconStyle) = glyph("models", s) { // hexagon node
    moveTo(12f, 3f); lineTo(20f, 7.5f); lineTo(20f, 16.5f); lineTo(12f, 21f)
    lineTo(4f, 16.5f); lineTo(4f, 7.5f); close()
}

private fun hardware(s: IconStyle) = glyph("hw", s) { // chip with pins
    moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close()
    moveTo(10f, 3f); lineTo(11f, 3f); lineTo(11f, 7f); lineTo(10f, 7f); close()
    moveTo(13f, 3f); lineTo(14f, 3f); lineTo(14f, 7f); lineTo(13f, 7f); close()
    moveTo(10f, 17f); lineTo(11f, 17f); lineTo(11f, 21f); lineTo(10f, 21f); close()
    moveTo(13f, 17f); lineTo(14f, 17f); lineTo(14f, 21f); lineTo(13f, 21f); close()
}

private fun network(s: IconStyle) = glyph("net", s) { // globe (ring + equator band)
    moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 11.99f, 3f); close()
    moveTo(3f, 12f); lineTo(21f, 12f); lineTo(21f, 13f); lineTo(3f, 13f); close()
    moveTo(11.5f, 3f); lineTo(12.5f, 3f); lineTo(12.5f, 21f); lineTo(11.5f, 21f); close()
}

private fun meals(s: IconStyle) = glyph("meals", s) { // plate ring
    moveTo(12f, 4f); arcTo(8f, 8f, 0f, true, true, 11.99f, 4f); close()
    moveTo(12f, 8f); arcTo(4f, 4f, 0f, true, false, 12.01f, 8f); close()
}

private fun insulin(s: IconStyle) = glyph("insulin", s) { // syringe barrel (diagonal)
    moveTo(4f, 18f); lineTo(6f, 20f); lineTo(16f, 10f); lineTo(14f, 8f); close()
    moveTo(15f, 5f); lineTo(19f, 9f); lineTo(17f, 11f); lineTo(13f, 7f); close()
}

private fun security(s: IconStyle) = glyph("sec", s) { // padlock
    moveTo(6f, 10f); lineTo(18f, 10f); lineTo(18f, 20f); lineTo(6f, 20f); close()
    moveTo(8f, 10f); lineTo(8f, 7f); arcTo(4f, 4f, 0f, true, true, 16f, 7f); lineTo(16f, 10f)
    lineTo(14f, 10f); lineTo(14f, 7f); arcTo(2f, 2f, 0f, true, false, 10f, 7f); lineTo(10f, 10f); close()
}

private fun journal(s: IconStyle) = glyph("journal", s) { // book with spine
    moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(5f, 20f); close()
    moveTo(8f, 4f); lineTo(9f, 4f); lineTo(9f, 20f); lineTo(8f, 20f); close()
}

private fun settings(s: IconStyle) = glyph("settings", s) { // gear (octagon + bore)
    moveTo(12f, 3f); lineTo(15f, 5f); lineTo(19f, 5f); lineTo(19f, 9f); lineTo(21f, 12f)
    lineTo(19f, 15f); lineTo(19f, 19f); lineTo(15f, 19f); lineTo(12f, 21f); lineTo(9f, 19f)
    lineTo(5f, 19f); lineTo(5f, 15f); lineTo(3f, 12f); lineTo(5f, 9f); lineTo(5f, 5f)
    lineTo(9f, 5f); close()
    moveTo(12f, 9f); arcTo(3f, 3f, 0f, true, false, 12.01f, 9f); close()
}

/** Resolve a nav route to its themed [ImageVector]. Unknown routes fall back to the settings gear. */
fun navIcon(route: String, style: IconStyle): ImageVector = when (route) {
    "dashboard" -> dashboard(style)
    "circadian" -> clock(style)
    "stats" -> stats(style)
    "models" -> models(style)
    "hardware" -> hardware(style)
    "network" -> network(style)
    "meals" -> meals(style)
    "insulin" -> insulin(style)
    "security" -> security(style)
    "journal" -> journal(style)
    "settings" -> settings(style)
    else -> settings(style)
}
