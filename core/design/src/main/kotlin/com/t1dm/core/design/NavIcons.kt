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

/** Geometry, not only colour, is re-derived per theme; every glyph drawn white, caller tints it. */
enum class IconStyle { TRON, UMBRELLA, KITTY }

fun iconStyleForTheme(themeId: String?): IconStyle = when (themeId) {
    ThemeIds.UMBRELLA -> IconStyle.UMBRELLA
    ThemeIds.HELLO_KITTY -> IconStyle.KITTY
    else -> IconStyle.TRON
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

/** Every glyph must be a CLOSED shape, so it reads filled and stroked alike. */
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

private fun dashboard(s: IconStyle) = glyph("bg", s) { // a droplet
    moveTo(12f, 3f); curveTo(16f, 9f, 18f, 12f, 18f, 15f)
    arcTo(6f, 6f, 0f, true, true, 6f, 15f)
    curveTo(6f, 12f, 8f, 9f, 12f, 3f); close()
}

private fun clock(s: IconStyle) = glyph("clock", s) { // a ring
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

private fun network(s: IconStyle) = glyph("net", s) { // a globe
    moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 11.99f, 3f); close()
    moveTo(3f, 12f); lineTo(21f, 12f); lineTo(21f, 13f); lineTo(3f, 13f); close()
    moveTo(11.5f, 3f); lineTo(12.5f, 3f); lineTo(12.5f, 21f); lineTo(11.5f, 21f); close()
}

private fun meals(s: IconStyle) = glyph("meals", s) { // plate ring
    moveTo(12f, 4f); arcTo(8f, 8f, 0f, true, true, 11.99f, 4f); close()
    moveTo(12f, 8f); arcTo(4f, 4f, 0f, true, false, 12.01f, 8f); close()
}

private fun insulin(s: IconStyle) = glyph("insulin", s) { // a syringe, diagonal
    moveTo(4f, 18f); lineTo(6f, 20f); lineTo(16f, 10f); lineTo(14f, 8f); close()
    moveTo(15f, 5f); lineTo(19f, 9f); lineTo(17f, 11f); lineTo(13f, 7f); close()
}

private fun cgm(s: IconStyle) = glyph("cgm", s) { // a sensor disc and a broadcast tick
    moveTo(12f, 5f); arcTo(6f, 6f, 0f, true, true, 11.99f, 5f); close()
    moveTo(12f, 9f); arcTo(2f, 2f, 0f, true, false, 12.01f, 9f); close()
    moveTo(17.5f, 3.5f); lineTo(20.5f, 3.5f); lineTo(20.5f, 4.5f); lineTo(17.5f, 4.5f); close()
    moveTo(19.5f, 3.5f); lineTo(20.5f, 3.5f); lineTo(20.5f, 6.5f); lineTo(19.5f, 6.5f); close()
}

private fun security(s: IconStyle) = glyph("watch", s) { // a wristwatch, watch panel route
    moveTo(12f, 6.5f); arcTo(5.5f, 5.5f, 0f, true, true, 11.99f, 6.5f); close()
    moveTo(9.6f, 6.9f); lineTo(14.4f, 6.9f); lineTo(13.9f, 2.2f); lineTo(10.1f, 2.2f); close()
    moveTo(9.6f, 17.1f); lineTo(14.4f, 17.1f); lineTo(13.9f, 21.8f); lineTo(10.1f, 21.8f); close()
    moveTo(17.4f, 10.7f); lineTo(19.2f, 10.7f); lineTo(19.2f, 13.3f); lineTo(17.4f, 13.3f); close()
}

private fun exercise(s: IconStyle) = glyph("exercise", s) { // a runner
    moveTo(14.5f, 2.5f); arcTo(2.5f, 2.5f, 0f, true, true, 14.49f, 2.5f); close()
    // Torso/arm/legs as ONE non-self-intersecting polygon; stride tells it from heart at 28dp.
    moveTo(13f, 8f); lineTo(17.5f, 10.5f); lineTo(16.5f, 12.5f); lineTo(13.5f, 11f)
    lineTo(12.5f, 14f); lineTo(15f, 16.5f); lineTo(15f, 21.5f); lineTo(12.8f, 21.5f)
    lineTo(12.8f, 17.5f); lineTo(9f, 14.5f); lineTo(6.5f, 20.5f); lineTo(4.5f, 19.5f)
    lineTo(8f, 11.5f); close()
}

private fun settings(s: IconStyle) = glyph("settings", s) { // a gear
    moveTo(12f, 3f); lineTo(15f, 5f); lineTo(19f, 5f); lineTo(19f, 9f); lineTo(21f, 12f)
    lineTo(19f, 15f); lineTo(19f, 19f); lineTo(15f, 19f); lineTo(12f, 21f); lineTo(9f, 19f)
    lineTo(5f, 19f); lineTo(5f, 15f); lineTo(3f, 12f); lineTo(5f, 9f); lineTo(5f, 5f)
    lineTo(9f, 5f); close()
    moveTo(12f, 9f); arcTo(3f, 3f, 0f, true, false, 12.01f, 9f); close()
}

private fun logs(s: IconStyle) = glyph("logs", s) { // a bulleted list
    moveTo(4f, 5.5f); lineTo(7f, 5.5f); lineTo(7f, 8.5f); lineTo(4f, 8.5f); close()
    moveTo(9.5f, 6f); lineTo(20f, 6f); lineTo(20f, 8f); lineTo(9.5f, 8f); close()
    moveTo(4f, 10.5f); lineTo(7f, 10.5f); lineTo(7f, 13.5f); lineTo(4f, 13.5f); close()
    moveTo(9.5f, 11f); lineTo(20f, 11f); lineTo(20f, 13f); lineTo(9.5f, 13f); close()
    moveTo(4f, 15.5f); lineTo(7f, 15.5f); lineTo(7f, 18.5f); lineTo(4f, 18.5f); close()
    moveTo(9.5f, 16f); lineTo(16f, 16f); lineTo(16f, 18f); lineTo(9.5f, 18f); close()
}

private fun backup(s: IconStyle) = glyph("backup", s) { // an archive box: lid, body, grip slot
    // The grip is wound opposite the lid, so NonZero fill knocks it out as a hole.
    moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 9f); lineTo(3f, 9f); close()
    moveTo(10f, 6f); lineTo(10f, 7f); lineTo(14f, 7f); lineTo(14f, 6f); close()
    moveTo(5f, 10.5f); lineTo(19f, 10f); lineTo(19f, 20f); lineTo(5f, 20f); close()
    // A downward arrow: the direction the data goes.
    moveTo(11f, 12f); lineTo(13f, 12f); lineTo(13f, 15.5f); lineTo(11f, 15.5f); close()
    moveTo(8.5f, 15f); lineTo(15.5f, 15f); lineTo(12f, 18.5f); close()
}

// BG panel lane marks opt out of per-theme geometry; always FILLED, re-tinted, never re-shaped.

private fun filledGlyph(name: String, body: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "t1dm_$name",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply { path(fill = SolidColor(Color.White), pathBuilder = body) }.build()

/** Carbohydrate: a burger — bun, patty, bun as three separate pieces. */
private val CarbMark: ImageVector = filledGlyph("carbmark") {
    moveTo(2.5f, 12f); arcTo(9.5f, 8.5f, 0f, false, true, 21.5f, 12f); close()
    moveTo(1.5f, 13.5f); lineTo(22.5f, 13.5f); lineTo(22.5f, 16.5f); lineTo(1.5f, 16.5f); close()
    moveTo(2.5f, 18f); lineTo(21.5f, 18f); lineTo(20.5f, 21f); lineTo(3.5f, 21f); close()
}

/** Insulin syringe, upright pointing DOWN; diagonal (nav set) would read as a trace stroke. */
private val InsulinMark: ImageVector = filledGlyph("insulinmark") {
    moveTo(8f, 1f); lineTo(16f, 1f); lineTo(16f, 3.2f); lineTo(13.5f, 3.2f); lineTo(13.5f, 6f)
    lineTo(17.5f, 6f); lineTo(17.5f, 8.2f); lineTo(15.5f, 8.2f); lineTo(15.5f, 16f)
    lineTo(13f, 16f); lineTo(13f, 23.5f); lineTo(11f, 23.5f); lineTo(11f, 16f)
    lineTo(8.5f, 16f); lineTo(8.5f, 8.2f); lineTo(6.5f, 8.2f); lineTo(6.5f, 6f)
    lineTo(10.5f, 6f); lineTo(10.5f, 3.2f); lineTo(8f, 3.2f); close()
}

/** Exercise dumbbell: two weights, two collars, the bar; all rectangles like the other two. */
private val ExerciseMark: ImageVector = filledGlyph("exercisemark") {
    moveTo(1.5f, 8f); lineTo(5f, 8f); lineTo(5f, 16f); lineTo(1.5f, 16f); close()
    moveTo(6f, 10f); lineTo(8.5f, 10f); lineTo(8.5f, 14f); lineTo(6f, 14f); close()
    moveTo(8.5f, 11f); lineTo(15.5f, 11f); lineTo(15.5f, 13f); lineTo(8.5f, 13f); close()
    moveTo(15.5f, 10f); lineTo(18f, 10f); lineTo(18f, 14f); lineTo(15.5f, 14f); close()
    moveTo(19f, 8f); lineTo(22.5f, 8f); lineTo(22.5f, 16f); lineTo(19f, 16f); close()
}

fun logMarkerIcon(kind: CurveKind): ImageVector = when (kind) {
    CurveKind.CARB -> CarbMark
    CurveKind.INSULIN -> InsulinMark
    CurveKind.EXERCISE -> ExerciseMark
}

enum class DayPeriod { MORNING, NOON, EVENING, NIGHT }

/** [hour] is the LOCAL hour, `[0,24)`. */
fun dayPeriodFor(hour: Int): DayPeriod = when (hour) {
    in 5..10 -> DayPeriod.MORNING
    in 11..16 -> DayPeriod.NOON
    in 17..20 -> DayPeriod.EVENING
    else -> DayPeriod.NIGHT
}

private fun morning(s: IconStyle) = glyph("morning", s) { // a sun over a horizon
    moveTo(4f, 16f); lineTo(20f, 16f); lineTo(20f, 17f); lineTo(4f, 17f); close()
    moveTo(6f, 16f); arcTo(6f, 6f, 0f, false, true, 18f, 16f); close()
}

private fun noon(s: IconStyle) = glyph("noon", s) { // a full sun disc
    moveTo(12f, 6f); arcTo(6f, 6f, 0f, true, true, 11.99f, 6f); close()
}

private fun evening(s: IconStyle) = glyph("evening", s) { // a sun below a horizon
    moveTo(4f, 8f); lineTo(20f, 8f); lineTo(20f, 9f); lineTo(4f, 9f); close()
    moveTo(6f, 9f); arcTo(6f, 6f, 0f, false, false, 18f, 9f); close()
}

private fun night(s: IconStyle) = glyph("night", s) { // crescent moon
    moveTo(15f, 4f)
    arcTo(8f, 8f, 0f, true, false, 15f, 20f)
    arcTo(6.4f, 6.4f, 0f, true, true, 15f, 4f)
    close()
}

private fun heart(s: IconStyle) = glyph("heart", s) {
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

fun heartIcon(style: IconStyle): ImageVector = heart(style)

fun timeOfDayIcon(period: DayPeriod, style: IconStyle): ImageVector = when (period) {
    DayPeriod.MORNING -> morning(style)
    DayPeriod.NOON -> noon(style)
    DayPeriod.EVENING -> evening(style)
    DayPeriod.NIGHT -> night(style)
}

fun navIcon(route: String, style: IconStyle): ImageVector = when (route) {
    "dashboard" -> dashboard(style)
    "circadian" -> clock(style)
    "stats" -> stats(style)
    "models" -> models(style)
    "network" -> network(style)
    "meals" -> meals(style)
    "insulin" -> insulin(style)
    "exercise" -> exercise(style)
    "cgm" -> cgm(style)
    "security" -> security(style)
    "backup" -> backup(style)
    "logs" -> logs(style)
    "settings" -> settings(style)
    else -> settings(style)
}
