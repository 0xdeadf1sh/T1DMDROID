package com.t1dm.core.design

import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalFontFamilyResolver
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

private val OrbitronFamily: FontFamily by lazy {
    FontFamily(
        variableFont(R.font.orbitron, FontWeight.Medium),
        variableFont(R.font.orbitron, FontWeight.Bold),
    )
}

private val SplineSansMonoFamily: FontFamily by lazy { FontFamily(Font(R.font.spline_sans_mono)) }

private val ShareTechMonoFamily: FontFamily by lazy { FontFamily(Font(R.font.share_tech_mono)) }

private val IbmPlexMonoFamily: FontFamily by lazy { FontFamily(Font(R.font.ibm_plex_mono)) }

private val CrimsonTextFamily: FontFamily by lazy {
    FontFamily(
        Font(R.font.crimson_text, FontWeight.Normal),
        Font(R.font.crimson_text_bold, FontWeight.Bold),
    )
}

/** [text] for prose and canvas labels; [mono] where digits must align. */
data class T1dmFonts(val text: FontFamily, val mono: FontFamily)

val LocalT1dmFonts = staticCompositionLocalOf { fontsFor(T1dmFontId.POLYPHEMUS) }

fun fontsFor(id: T1dmFontId): T1dmFonts = when (id) {
    T1dmFontId.POLYPHEMUS -> T1dmFonts(ShareTechMonoFamily, ShareTechMonoFamily)
    T1dmFontId.SYSTEM -> T1dmFonts(FontFamily.Default, FontFamily.Monospace)
    T1dmFontId.IBM_PLEX_MONO -> T1dmFonts(IbmPlexMonoFamily, IbmPlexMonoFamily)
    T1dmFontId.SHARE_TECH_MONO -> T1dmFonts(ShareTechMonoFamily, ShareTechMonoFamily)
    T1dmFontId.SPLINE_SANS_MONO -> T1dmFonts(SplineSansMonoFamily, SplineSansMonoFamily)
    T1dmFontId.CRIMSON_TEXT -> T1dmFonts(CrimsonTextFamily, FontFamily.Monospace)
}

fun typographyFor(id: T1dmFontId): Typography =
    if (id == T1dmFontId.POLYPHEMUS) polyphemusTypography() else typographyWith(fontsFor(id).text)

/** Orbitron headings; Share Tech Mono elsewhere, untracked: a mono face is wide already. */
private fun polyphemusTypography(): Typography {
    val base = Typography()
    fun display(s: TextStyle) = s.copy(fontFamily = OrbitronFamily, fontWeight = FontWeight.Medium, letterSpacing = 0.04.em)
    fun text(s: TextStyle) = s.copy(fontFamily = ShareTechMonoFamily, letterSpacing = 0.em)
    return base.copy(
        displayLarge = display(base.displayLarge), displayMedium = display(base.displayMedium), displaySmall = display(base.displaySmall),
        headlineLarge = display(base.headlineLarge), headlineMedium = display(base.headlineMedium), headlineSmall = display(base.headlineSmall),
        titleLarge = text(base.titleLarge), titleMedium = text(base.titleMedium), titleSmall = text(base.titleSmall),
        bodyLarge = text(base.bodyLarge), bodyMedium = text(base.bodyMedium), bodySmall = text(base.bodySmall),
        labelLarge = text(base.labelLarge), labelMedium = text(base.labelMedium), labelSmall = text(base.labelSmall),
    )
}

/** The theme's [T1dmFonts.text] as a platform [Typeface], for `android.graphics.Paint` text. */
@Composable
fun rememberThemeTypeface(): Typeface {
    val family = LocalT1dmFonts.current.text
    val resolver = LocalFontFamilyResolver.current
    return remember(family, resolver) { resolver.resolve(family).value as? Typeface ?: Typeface.DEFAULT }
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
