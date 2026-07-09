package com.t1dm.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * The app's semantic colour token set (PLAN.private.md §3.4 / ux-decisions.md — "3 themes via
 * semantic colour tokens"). Every surface that must repaint per theme — the graph bands, the stats
 * risk bands, the alert tiers, the widgets, the Circadian dial — reads these *roles*, not raw
 * literals, so a theme swap recolours the whole app coherently.
 *
 * The chrome roles ([background]/[surface]/[primary]/[secondary]/[ink]) also project onto a Material3
 * [ColorScheme] via [toColorScheme] so plain Material widgets follow the theme for free; the
 * glucose-band roles ([urgentLow]…[urgentHigh], [grid]) have no Material equivalent and are carried
 * separately through `LocalT1dmSemantics`.
 */
data class T1dmPalette(
    val id: String,
    val displayName: String,
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val ink: Color,
    val inkMuted: Color,
    val grid: Color,
    // Glucose bands (low→high), reused by graph, stats, alerts, widgets.
    val urgentLow: Color,
    val low: Color,
    val inRange: Color,
    val high: Color,
    val urgentHigh: Color,
) {
    /** Project the chrome roles onto a Material3 [ColorScheme] (dark or light per [dark]). Bands map
     *  where they read best: [urgentLow] onto `error`, [inRange] onto `tertiary`. */
    fun toColorScheme(): ColorScheme {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = primary,
            onPrimary = onPrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            tertiary = inRange,
            onTertiary = if (dark) background else Color.White,
            background = background,
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = inkMuted,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceVariant,
            surfaceContainerLow = background,
            error = urgentLow,
            onError = if (dark) Color.Black else Color.White,
            outline = grid,
            outlineVariant = grid,
        )
    }
}

/** The stable ids the selection persists as (ux-decisions.md). */
object ThemeIds {
    const val TRON = "tron"
    const val UMBRELLA = "umbrella"
    const val HELLO_KITTY = "hello_kitty"
    const val CUSTOM = "custom"
}

/** Tron Legacy — the DEFAULT: cyan-and-amber on near-black (the prior app aesthetic, promoted). */
val TronPalette = T1dmPalette(
    id = ThemeIds.TRON,
    displayName = "Tron Legacy",
    dark = true,
    background = Color(0xFF060A12),
    surface = Color(0xFF0E1626),
    surfaceVariant = Color(0xFF152134),
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF04121A),
    secondary = Color(0xFFFFB300),
    onSecondary = Color(0xFF1A1200),
    ink = Color(0xFFDCEAF5),
    inkMuted = Color(0xFF8FA9BE),
    grid = Color(0xFF1E3350),
    urgentLow = Color(0xFFFF2D55),
    low = Color(0xFFFFC142),
    inRange = Color(0xFF00E5A8),
    high = Color(0xFFFF9E2C),
    urgentHigh = Color(0xFFFF3B30),
)

/** Umbrella Corp — dark red-on-black corporate: hazard red primary, steel secondary. */
val UmbrellaPalette = T1dmPalette(
    id = ThemeIds.UMBRELLA,
    displayName = "Umbrella Corp",
    dark = true,
    background = Color(0xFF080405),
    surface = Color(0xFF17090C),
    surfaceVariant = Color(0xFF230F13),
    primary = Color(0xFFE4002B),
    onPrimary = Color(0xFF150000),
    secondary = Color(0xFFB7C0C7),
    onSecondary = Color(0xFF10171B),
    ink = Color(0xFFEADFE0),
    inkMuted = Color(0xFFA98F92),
    grid = Color(0xFF3A1A1F),
    urgentLow = Color(0xFFFF375F),
    low = Color(0xFFFF8A50),
    inRange = Color(0xFF7ED957),
    high = Color(0xFFFFAA33),
    urgentHigh = Color(0xFFD50000),
)

/** Hello Kitty — light pink pastel: hot-pink primary on blush, plum ink. */
val HelloKittyPalette = T1dmPalette(
    id = ThemeIds.HELLO_KITTY,
    displayName = "Hello Kitty",
    dark = false,
    background = Color(0xFFFFF2F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFFFE1EE),
    primary = Color(0xFFFF4FA3),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF7EC8E3),
    onSecondary = Color(0xFF06222B),
    ink = Color(0xFF4A2C3A),
    inkMuted = Color(0xFF9C7080),
    grid = Color(0xFFF3C6DA),
    urgentLow = Color(0xFFE5397D),
    low = Color(0xFFFF8FB3),
    inRange = Color(0xFF57B85F),
    high = Color(0xFFFFA05C),
    urgentHigh = Color(0xFFFF2D6E),
)

/** The three bundled palettes, in selector order (Tron first = default). */
val BundledPalettes: List<T1dmPalette> = listOf(TronPalette, UmbrellaPalette, HelloKittyPalette)

/** Resolve a persisted id to a bundled palette, falling back to [TronPalette]. Custom themes are
 *  reconstructed from their JSON separately via [parseThemeJson]. */
fun paletteForId(id: String?): T1dmPalette =
    BundledPalettes.firstOrNull { it.id == id } ?: TronPalette

// ── Custom-theme JSON import (item 25 — "parse a colour-scheme JSON via SAF into a custom theme") ──

/**
 * The JSON a user imports to define a 4th, custom theme. Shape (every colour a `#RRGGBB` or
 * `#AARRGGBB` string):
 * ```
 * {
 *   "format": "t1dm.theme",
 *   "name": "My Theme",
 *   "dark": true,
 *   "colors": {
 *     "background": "#060A12", "surface": "#0E1626", "surfaceVariant": "#152134",
 *     "primary": "#00E5FF", "secondary": "#FFB300", "ink": "#DCEAF5",
 *     "urgentLow": "#FF2D55", "low": "#FFC142", "inRange": "#00E5A8",
 *     "high": "#FF9E2C", "urgentHigh": "#FF3B30"
 *   }
 * }
 * ```
 * `surfaceVariant`, `inkMuted`, `grid`, `onPrimary`, `onSecondary` are optional and derived from the
 * required roles when absent. Parsing is fail-closed with a plain-language message (ux-decisions.md).
 */
fun parseThemeJson(text: String): T1dmPalette {
    val root = runCatching { JSONObject(text) }
        .getOrElse { throw IllegalArgumentException("Not a valid theme file (could not parse JSON).") }
    if (root.optString("format") != "t1dm.theme") {
        throw IllegalArgumentException("Not a T1DM theme file (missing or wrong \"format\" tag — expected \"t1dm.theme\").")
    }
    val colors = root.optJSONObject("colors")
        ?: throw IllegalArgumentException("Theme file has no \"colors\" block.")
    val dark = root.optBoolean("dark", true)

    fun req(role: String): Color {
        if (!colors.has(role)) throw IllegalArgumentException("Theme is missing the required colour \"$role\".")
        return parseHexColor(colors.getString(role), role)
    }
    fun opt(role: String, fallback: Color): Color =
        if (colors.has(role)) parseHexColor(colors.getString(role), role) else fallback

    val bg = req("background")
    val surface = req("surface")
    val primary = req("primary")
    val secondary = req("secondary")
    val ink = req("ink")
    return T1dmPalette(
        id = ThemeIds.CUSTOM,
        displayName = root.optString("name").ifBlank { "Custom" },
        dark = dark,
        background = bg,
        surface = surface,
        surfaceVariant = opt("surfaceVariant", surface),
        primary = primary,
        onPrimary = opt("onPrimary", if (dark) Color.Black else Color.White),
        secondary = secondary,
        onSecondary = opt("onSecondary", if (dark) Color.Black else Color.White),
        ink = ink,
        inkMuted = opt("inkMuted", ink.copy(alpha = 0.6f)),
        grid = opt("grid", ink.copy(alpha = 0.15f)),
        urgentLow = req("urgentLow"),
        low = req("low"),
        inRange = req("inRange"),
        high = req("high"),
        urgentHigh = req("urgentHigh"),
    )
}

private fun parseHexColor(raw: String, role: String): Color {
    val s = raw.trim().removePrefix("#")
    val hex = when (s.length) {
        6 -> "FF$s"
        8 -> s
        else -> throw IllegalArgumentException("Colour \"$role\" must be #RRGGBB or #AARRGGBB (got \"$raw\").")
    }
    val v = hex.toLongOrNull(16)
        ?: throw IllegalArgumentException("Colour \"$role\" is not a valid hex colour (got \"$raw\").")
    return Color(v.toInt())
}
