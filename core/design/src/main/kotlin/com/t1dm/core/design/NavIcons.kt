package com.t1dm.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.CurveKind

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
    else -> IconStyle.TRON // Tron default; a custom (or retired) theme borrows the Tron geometry.
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

// ── The destination silhouettes ───────────────────────────────────────────────────────────────────

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

private fun settings(s: IconStyle) = glyph("settings", s) { // gear (octagon + bore)
    moveTo(12f, 3f); lineTo(15f, 5f); lineTo(19f, 5f); lineTo(19f, 9f); lineTo(21f, 12f)
    lineTo(19f, 15f); lineTo(19f, 19f); lineTo(15f, 19f); lineTo(12f, 21f); lineTo(9f, 19f)
    lineTo(5f, 19f); lineTo(5f, 15f); lineTo(3f, 12f); lineTo(5f, 9f); lineTo(5f, 5f)
    lineTo(9f, 5f); close()
    moveTo(12f, 9f); arcTo(3f, 3f, 0f, true, false, 12.01f, 9f); close()
}

private fun pubs(s: IconStyle) = glyph("pubs", s) { // a page with text lines
    // Text lines wound opposite the page outline so NonZero fill (Umbrella) knocks them out as holes
    // instead of collapsing to a solid slab; stroke rendering is direction-agnostic, so the other
    // themes read the same lines either way.
    moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(6f, 21f); close()
    moveTo(8f, 7f); lineTo(8f, 8f); lineTo(16f, 8f); lineTo(16f, 7f); close()
    moveTo(8f, 11f); lineTo(8f, 12f); lineTo(16f, 12f); lineTo(16f, 11f); close()
    moveTo(8f, 15f); lineTo(8f, 16f); lineTo(13f, 16f); lineTo(13f, 15f); close()
}

private fun logs(s: IconStyle) = glyph("logs", s) { // a bulleted list: three marker + rule pairs
    // Deliberately unlike `pubs` (lines INSIDE a page outline): no enclosing shape at all, so the
    // two surfaces stay distinguishable at 28 dp in every geometry.
    // Each marker and each rule is its own closed rectangle, so it reads as ink when filled
    // (Umbrella) and as an outline when stroked (Tron / Kitty); the last rule is short, which is what
    // stops the set reading as a grid.
    moveTo(4f, 5.5f); lineTo(7f, 5.5f); lineTo(7f, 8.5f); lineTo(4f, 8.5f); close()
    moveTo(9.5f, 6f); lineTo(20f, 6f); lineTo(20f, 8f); lineTo(9.5f, 8f); close()
    moveTo(4f, 10.5f); lineTo(7f, 10.5f); lineTo(7f, 13.5f); lineTo(4f, 13.5f); close()
    moveTo(9.5f, 11f); lineTo(20f, 11f); lineTo(20f, 13f); lineTo(9.5f, 13f); close()
    moveTo(4f, 15.5f); lineTo(7f, 15.5f); lineTo(7f, 18.5f); lineTo(4f, 18.5f); close()
    moveTo(9.5f, 16f); lineTo(16f, 16f); lineTo(16f, 18f); lineTo(9.5f, 18f); close()
}

// ── Logged-event markers (the BG panel's lane marks) ──────────────────────────────────────────────
//
// Drawn in the lower region of the glucose plot, one per logged carb/insulin event, so the trace says
// when the user acted as well as what their glucose did.
//
// These are the ONE set that opts out of the per-theme geometry above: a single burger and a single
// syringe, always FILLED, re-tinted per theme by the caller and never re-shaped. Two reasons, both
// consequences of WHERE they render — on top of the trace, over live data, at whatever zoom the user
// has pinched to:
//
//  - The silhouette is the whole of the reading. A stroked outline competes with the grid, the curve
//    overlay and the BG line it stands among, so the fill-vs-stroke transform the nav set turns on has
//    nothing to express here that would not cost legibility.
//  - What the mark must say is WHICH CHANNEL it belongs to, instantly, at a glance across a busy
//    evening. A shape that changed with the theme would be one more thing to relearn, and there is no
//    second reading of it to fall back on: the panel is not permitted the amount, and colour already
//    carries the theme.
//
// So both are chunky, axis-aligned, mostly-rectangular silhouettes with generous internal gaps, drawn
// large in the 24-unit viewport — which is also what lets them scale to whatever `LOG_MARKER_DP` the
// panel draws them at without a second artwork. The BG point markers are circles and neither of these
// is round, so a mark cannot be mistaken for the plot's own furniture.

/** Build a 24dp icon from a closed silhouette, always filled and never re-derived per theme. */
private fun filledGlyph(name: String, body: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "t1dm_$name",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply { path(fill = SolidColor(Color.White), pathBuilder = body) }.build()

/** Carbohydrate: a burger — a domed bun over two bars. Three separate pieces, so the stack survives
 *  being read at a glance against a moving trace rather than resolving into one blob. */
private val CarbMark: ImageVector = filledGlyph("carbmark") {
    moveTo(2.5f, 12f); arcTo(9.5f, 8.5f, 0f, false, true, 21.5f, 12f); close() // top bun
    moveTo(1.5f, 13.5f); lineTo(22.5f, 13.5f); lineTo(22.5f, 16.5f); lineTo(1.5f, 16.5f); close() // patty
    moveTo(2.5f, 18f); lineTo(21.5f, 18f); lineTo(20.5f, 21f); lineTo(3.5f, 21f); close() // bottom bun
}

/** Insulin: a syringe, upright and pointing DOWN at the axis — plunger, finger flange, barrel, needle,
 *  as one closed polygon. Upright rather than the nav set's diagonal barrel: a 45° slab reads as a
 *  stroke of the trace it stands over, where the vertical needle pointing at the axis cannot. */
private val InsulinMark: ImageVector = filledGlyph("insulinmark") {
    moveTo(8f, 1f); lineTo(16f, 1f); lineTo(16f, 3.2f); lineTo(13.5f, 3.2f); lineTo(13.5f, 6f)
    lineTo(17.5f, 6f); lineTo(17.5f, 8.2f); lineTo(15.5f, 8.2f); lineTo(15.5f, 16f)
    lineTo(13f, 16f); lineTo(13f, 23.5f); lineTo(11f, 23.5f); lineTo(11f, 16f)
    lineTo(8.5f, 16f); lineTo(8.5f, 8.2f); lineTo(6.5f, 8.2f); lineTo(6.5f, 6f)
    lineTo(10.5f, 6f); lineTo(10.5f, 3.2f); lineTo(8f, 3.2f); close()
}

/** Resolve a logged event's channel to its marker glyph. [CurveKind] is the model's own channel
 *  vocabulary, so a marker and the curve it stands for are labelled identically. The caller tints it
 *  from the active palette; the shape itself is the same on every theme. */
fun logMarkerIcon(kind: CurveKind): ImageVector = when (kind) {
    CurveKind.CARB -> CarbMark
    CurveKind.INSULIN -> InsulinMark
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

private fun heart(s: IconStyle) = glyph("heart", s) { // a heart (the liveness/heartbeat glyph)
    moveTo(12f, 21.35f)
    lineTo(10.55f, 20.03f)
    curveTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
    curveTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
    curveTo(9.24f, 3f, 10.91f, 3.81f, 12f, 5.09f)
    curveTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
    curveTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
    curveTo(22f, 12.28f, 18.6f, 15.36f, 13.45f, 20.04f)
    close()
}

/** The themed heart glyph, in the same geometry family as [navIcon] — the dashboard's fixed-60-bpm
 *  liveness indicator. */
fun heartIcon(style: IconStyle): ImageVector = heart(style)

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
    "pubs" -> pubs(style)
    "circadian" -> clock(style)
    "stats" -> stats(style)
    "models" -> models(style)
    "hardware" -> hardware(style)
    "network" -> network(style)
    "meals" -> meals(style)
    "insulin" -> insulin(style)
    "security" -> security(style)
    "logs" -> logs(style)
    "settings" -> settings(style)
    else -> settings(style)
}
