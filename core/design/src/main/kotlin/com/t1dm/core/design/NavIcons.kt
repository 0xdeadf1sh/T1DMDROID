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
 *  - **Windows XP** — glossy Luna beveling: a medium *stroke* with rounded joins but clean butt caps.
 *  - **Teto Kasane** — chunky and brash: a heavy *stroke* with square caps and hard mitred corners.
 *
 * Fill-vs-stroke + sharp-vs-round is a real geometric transform of the same path, coherent across the
 * whole set, and legible small in both light and dark palettes (the [ImageVector] is drawn white and
 * tinted by the caller's `LocalContentColor`, so contrast is the theme's job).
 */
enum class IconStyle { TRON, UMBRELLA, KITTY, XP, TETO }

fun iconStyleForTheme(themeId: String?): IconStyle = when (themeId) {
    ThemeIds.UMBRELLA -> IconStyle.UMBRELLA
    ThemeIds.HELLO_KITTY -> IconStyle.KITTY
    ThemeIds.WINDOWS_XP -> IconStyle.XP
    ThemeIds.TETO -> IconStyle.TETO
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
    // Windows XP — glossy Luna beveling: a medium stroke, rounded joins, clean butt caps.
    IconStyle.XP -> Geo(stroke = true, strokeWidth = 1.9f, cap = StrokeCap.Butt, join = StrokeJoin.Round)
    // Teto Kasane — chunky, brash, stamped: a heavy stroke with square caps and hard mitred corners.
    IconStyle.TETO -> Geo(stroke = true, strokeWidth = 2.8f, cap = StrokeCap.Square, join = StrokeJoin.Miter)
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

private fun security(s: IconStyle) = glyph("watch", s) { // a wristwatch (the companion-device panel)
    moveTo(12f, 6.5f); arcTo(5.5f, 5.5f, 0f, true, true, 11.99f, 6.5f); close() // round case
    moveTo(9.6f, 6.9f); lineTo(14.4f, 6.9f); lineTo(13.9f, 2.2f); lineTo(10.1f, 2.2f); close() // upper strap
    moveTo(9.6f, 17.1f); lineTo(14.4f, 17.1f); lineTo(13.9f, 21.8f); lineTo(10.1f, 21.8f); close() // lower strap
    moveTo(17.4f, 10.7f); lineTo(19.2f, 10.7f); lineTo(19.2f, 13.3f); lineTo(17.4f, 13.3f); close() // crown
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

// ── Time-of-day glyphs (issue N4a) — the same per-theme geometry system as the nav icons, so morning /
//    noon / evening / night read as Tron-angular · Umbrella-blocky · Kitty-rounded like everything else.

enum class DayPeriod { MORNING, NOON, EVENING, NIGHT }

/** Map a local hour-of-day `[0,24)` to its period: morning 5–11, noon 11–17, evening 17–21, night else. */
fun dayPeriodFor(hour: Int): DayPeriod = when (hour) {
    in 5..10 -> DayPeriod.MORNING
    in 11..16 -> DayPeriod.NOON
    in 17..20 -> DayPeriod.EVENING
    else -> DayPeriod.NIGHT
}

private fun morning(s: IconStyle) = glyph("morning", s) { // sun rising over a horizon (upper half-disc)
    moveTo(4f, 16f); lineTo(20f, 16f); lineTo(20f, 17f); lineTo(4f, 17f); close()
    moveTo(6f, 16f); arcTo(6f, 6f, 0f, false, true, 18f, 16f); close()
}

private fun noon(s: IconStyle) = glyph("noon", s) { // full sun disc
    moveTo(12f, 6f); arcTo(6f, 6f, 0f, true, true, 11.99f, 6f); close()
}

private fun evening(s: IconStyle) = glyph("evening", s) { // sun setting below a horizon (lower half-disc)
    moveTo(4f, 8f); lineTo(20f, 8f); lineTo(20f, 9f); lineTo(4f, 9f); close()
    moveTo(6f, 9f); arcTo(6f, 6f, 0f, false, false, 18f, 9f); close()
}

private fun night(s: IconStyle) = glyph("night", s) { // crescent moon
    moveTo(15f, 4f)
    arcTo(8f, 8f, 0f, true, false, 15f, 20f)
    arcTo(6.4f, 6.4f, 0f, true, true, 15f, 4f)
    close()
}

/** Resolve a day-period to its themed [ImageVector], in the same geometry family as [navIcon]. */
fun timeOfDayIcon(period: DayPeriod, style: IconStyle): ImageVector = when (period) {
    DayPeriod.MORNING -> morning(style)
    DayPeriod.NOON -> noon(style)
    DayPeriod.EVENING -> evening(style)
    DayPeriod.NIGHT -> night(style)
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
