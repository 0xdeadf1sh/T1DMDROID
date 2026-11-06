package com.t1dm.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em

/** OFL faces from `res/font/`; their licences ship in `res/raw/ofl_*`. */
enum class T1dmFontId(val storageKey: String, val displayName: String) {
    POLYPHEMUS("polyphemus", "Polyphemus"),
    SYSTEM("system", "System default"),
    IBM_PLEX_MONO("ibm_plex_mono", "IBM Plex Mono"),
    SHARE_TECH_MONO("share_tech_mono", "Share Tech Mono"),
    SPLINE_SANS_MONO("spline_sans_mono", "Spline Sans Mono"),
    CRIMSON_TEXT("crimson_text", "Crimson Text");

    companion object {
        fun forKey(key: String?): T1dmFontId = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

@OptIn(ExperimentalTextApi::class)
private fun variableFont(res: Int, weight: FontWeight, vararg extra: FontVariation.Setting) =
    Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), *extra))

private val NewsreaderFamily: FontFamily by lazy {
    val opsz = FontVariation.Setting("opsz", 16f)
    FontFamily(
        variableFont(R.font.newsreader, FontWeight.Normal, opsz),
        variableFont(R.font.newsreader, FontWeight.Medium, opsz),
        variableFont(R.font.newsreader, FontWeight.Bold, opsz),
    )
}

private val JetBrainsMonoFamily: FontFamily by lazy {
    FontFamily(
        variableFont(R.font.jetbrains_mono, FontWeight.Normal),
        variableFont(R.font.jetbrains_mono, FontWeight.Medium),
        variableFont(R.font.jetbrains_mono, FontWeight.Bold),
    )
}

private val InstrumentSerifFamily: FontFamily by lazy { FontFamily(Font(R.font.instrument_serif)) }

fun fontFamilyFor(id: T1dmFontId): FontFamily = when (id) {
    T1dmFontId.POLYPHEMUS -> NewsreaderFamily
    T1dmFontId.SYSTEM -> FontFamily.Default
    T1dmFontId.IBM_PLEX_MONO -> FontFamily(Font(R.font.ibm_plex_mono))
    T1dmFontId.SHARE_TECH_MONO -> FontFamily(Font(R.font.share_tech_mono))
    T1dmFontId.SPLINE_SANS_MONO -> FontFamily(Font(R.font.spline_sans_mono))
    T1dmFontId.CRIMSON_TEXT -> FontFamily(
        Font(R.font.crimson_text, FontWeight.Normal),
        Font(R.font.crimson_text_bold, FontWeight.Bold),
    )
}

fun typographyFor(id: T1dmFontId): Typography =
    if (id == T1dmFontId.POLYPHEMUS) polyphemusTypography() else typographyWith(fontFamilyFor(id))

/** The site's type: Instrument Serif headings, Newsreader text, JetBrains Mono labels. */
private fun polyphemusTypography(): Typography {
    val base = Typography()
    fun display(s: TextStyle) = s.copy(fontFamily = InstrumentSerifFamily, fontWeight = FontWeight.Normal)
    fun text(s: TextStyle) = s.copy(fontFamily = NewsreaderFamily)
    fun label(s: TextStyle) = s.copy(fontFamily = JetBrainsMonoFamily, letterSpacing = 0.06.em)
    return base.copy(
        displayLarge = display(base.displayLarge), displayMedium = display(base.displayMedium), displaySmall = display(base.displaySmall),
        headlineLarge = display(base.headlineLarge), headlineMedium = display(base.headlineMedium), headlineSmall = display(base.headlineSmall),
        titleLarge = text(base.titleLarge), titleMedium = text(base.titleMedium), titleSmall = text(base.titleSmall),
        bodyLarge = text(base.bodyLarge), bodyMedium = text(base.bodyMedium), bodySmall = text(base.bodySmall),
        labelLarge = label(base.labelLarge), labelMedium = label(base.labelMedium), labelSmall = label(base.labelSmall),
    )
}

fun typographyWith(family: FontFamily): Typography {
    val base = Typography()
    fun apply(s: TextStyle) = s.copy(fontFamily = family)
    return base.copy(
        displayLarge = apply(base.displayLarge), displayMedium = apply(base.displayMedium), displaySmall = apply(base.displaySmall),
        headlineLarge = apply(base.headlineLarge), headlineMedium = apply(base.headlineMedium), headlineSmall = apply(base.headlineSmall),
        titleLarge = apply(base.titleLarge), titleMedium = apply(base.titleMedium), titleSmall = apply(base.titleSmall),
        bodyLarge = apply(base.bodyLarge), bodyMedium = apply(base.bodyMedium), bodySmall = apply(base.bodySmall),
        labelLarge = apply(base.labelLarge), labelMedium = apply(base.labelMedium), labelSmall = apply(base.labelSmall),
    )
}

/** Unused; kept for a variable-font upgrade. */
internal val DefaultDisplayWeight = FontWeight.Normal
