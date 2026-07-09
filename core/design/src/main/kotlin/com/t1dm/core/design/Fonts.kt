package com.t1dm.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The bundled global fonts (progress.md Q8 / ux-decisions.md — "≥3 bundled, terminal-ish but
 * readable, tabular numerals, OFL"). Three OFL monos live in `res/font/`; their licences ship in
 * `res/raw/ofl_*` (GPL-3.0-compatible). "System" restores the platform default. The choice is global
 * and persists (kv); the active [Typography] is rebuilt from it in `T1dmTheme`.
 */
enum class T1dmFontId(val storageKey: String, val displayName: String) {
    SYSTEM("system", "System default"),
    IBM_PLEX_MONO("ibm_plex_mono", "IBM Plex Mono"),
    SHARE_TECH_MONO("share_tech_mono", "Share Tech Mono"),
    SPLINE_SANS_MONO("spline_sans_mono", "Spline Sans Mono");

    companion object {
        fun forKey(key: String?): T1dmFontId = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

/** The [FontFamily] for a font choice; [T1dmFontId.SYSTEM] yields Compose's default (null-family). */
fun fontFamilyFor(id: T1dmFontId): FontFamily = when (id) {
    T1dmFontId.SYSTEM -> FontFamily.Default
    T1dmFontId.IBM_PLEX_MONO -> FontFamily(Font(R.font.ibm_plex_mono))
    T1dmFontId.SHARE_TECH_MONO -> FontFamily(Font(R.font.share_tech_mono))
    T1dmFontId.SPLINE_SANS_MONO -> FontFamily(Font(R.font.spline_sans_mono))
}

/** Restamp every [Typography] role with [family] so the whole app renders in the chosen font while
 *  keeping Material's size/weight scale. */
fun typographyWith(family: FontFamily): Typography {
    val base = Typography()
    fun apply(s: androidx.compose.ui.text.TextStyle) = s.copy(fontFamily = family)
    return base.copy(
        displayLarge = apply(base.displayLarge), displayMedium = apply(base.displayMedium), displaySmall = apply(base.displaySmall),
        headlineLarge = apply(base.headlineLarge), headlineMedium = apply(base.headlineMedium), headlineSmall = apply(base.headlineSmall),
        titleLarge = apply(base.titleLarge), titleMedium = apply(base.titleMedium), titleSmall = apply(base.titleSmall),
        bodyLarge = apply(base.bodyLarge), bodyMedium = apply(base.bodyMedium), bodySmall = apply(base.bodySmall),
        labelLarge = apply(base.labelLarge), labelMedium = apply(base.labelMedium), labelSmall = apply(base.labelSmall),
    )
}

/** Unused weight guard kept so a variable-font upgrade can pick a weight instance later. */
internal val DefaultDisplayWeight = FontWeight.Normal
