package com.t1dm.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The glucose-band + grid semantic roles a screen reads to paint per theme (the roles Material's
 * [ColorScheme] cannot express). Provided by [T1dmTheme] via [LocalT1dmSemantics] and defaulted to
 * the Tron palette so a stray un-themed preview still renders sanely.
 */
val LocalT1dmSemantics = staticCompositionLocalOf { TronPalette }

/** Whether motion is enabled app-wide (ux-decisions.md — the global "disable all animations" toggle,
 *  surfaced here so any animated surface can honour it without re-plumbing the setting). */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/**
 * The currently-active Material [ColorScheme], mirrored into a plain holder so non-Compose surfaces
 * (the Glance widgets, refreshed headlessly from the FGS) snapshot the SAME palette the Activity
 * renders — widget == app theme (PLAN Phase 7B/7D). [T1dmTheme] keeps it in sync on every recompose;
 * the widgets read it live at refresh time.
 */
@Volatile
var T1dmColorScheme: ColorScheme = TronPalette.toColorScheme()
    private set

@Volatile
var T1dmActivePalette: T1dmPalette = TronPalette
    private set

/**
 * The app theme (PLAN.private.md §3.4 / Phase 7D). Resolves the [palette] to a Material [ColorScheme]
 * + the chosen [font] to a [androidx.compose.material3.Typography], provides the glucose-band
 * semantics and the animation flag, and mirrors the active scheme into [T1dmColorScheme] for the
 * widgets. All parameters default so the pre-selector call sites (previews) still compile.
 */
@Composable
fun T1dmTheme(
    palette: T1dmPalette = TronPalette,
    font: T1dmFontId = T1dmFontId.SYSTEM,
    animationsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = palette.toColorScheme()
    T1dmColorScheme = scheme
    T1dmActivePalette = palette
    CompositionLocalProvider(
        LocalT1dmSemantics provides palette,
        LocalAnimationsEnabled provides animationsEnabled,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyWith(fontFamilyFor(font)),
            content = content,
        )
    }
}
