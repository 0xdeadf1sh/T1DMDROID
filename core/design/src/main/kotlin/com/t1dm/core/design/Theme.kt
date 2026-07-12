package com.t1dm.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
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

/** Whether DEATH mode is engaged app-wide (all safety warnings suppressed). Read by warning
 *  surfaces (e.g. DangerBanner) so they render nothing while it is on. */
val LocalDeathMode = staticCompositionLocalOf { false }

/**
 * The currently-active palette + Material [ColorScheme], mirrored into plain holders so the non-Compose
 * Glance widgets (refreshed headlessly from the FGS) snapshot the app theme — widget == app theme
 * (Phase 7B/7D). Written by exactly ONE authority, [applyWidgetPalette], which the FGS calls
 * synchronously right before it pushes the widgets; the widgets read the value it set at render time.
 *
 * The Activity does NOT write these — its own Compose tree reads the palette directly (MaterialTheme +
 * [LocalT1dmSemantics]), never these holders. A second, foreground writer used to race the FGS: while
 * the Activity is up it recomposes every second (the live "N s ago" ticker), so between the FGS setting
 * the new palette and the widget's async render it could clobber the holder back to the palette still in
 * its own not-yet-updated composition state, and the widget would render a beat behind the theme change.
 */
@Volatile
var T1dmColorScheme: ColorScheme = TronPalette.toColorScheme()
    private set

@Volatile
var T1dmActivePalette: T1dmPalette = TronPalette
    private set

/**
 * Seed the process-global palette holders the Glance widgets read, WITHOUT a composition — the SOLE
 * writer of [T1dmActivePalette]/[T1dmColorScheme]. The FGS calls this from its headless glance refresh
 * (once per reading/tick/theme-change) right before `updateAll`, so the widget renders the persisted
 * theme even when no Activity has run (boot / FGS-only start) and the instant the theme changes.
 */
fun applyWidgetPalette(palette: T1dmPalette) {
    T1dmActivePalette = palette
    T1dmColorScheme = palette.toColorScheme()
}

/**
 * The app theme (SPEC.private.md §3.4 / Phase 7D). Resolves the [palette] to a Material [ColorScheme]
 * + the chosen [font] to a [androidx.compose.material3.Typography], and provides the glucose-band
 * semantics and the animation flag. All parameters default so the pre-selector call sites (previews)
 * still compile. It deliberately does NOT touch the widget holders ([T1dmActivePalette]); those are the
 * FGS's alone (see [applyWidgetPalette]) to keep the foreground recompose from racing the widget render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun T1dmTheme(
    palette: T1dmPalette = TronPalette,
    font: T1dmFontId = T1dmFontId.SYSTEM,
    animationsEnabled: Boolean = true,
    deathMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = palette.toColorScheme()
    // U3 — the red-rectangle touch flash. Material's default ripple tints from the local content
    // colour; on a surface whose content resolves to `error` (= [urgentLow] = red) a bounded ripple on
    // an unclipped clickable painted a red rectangle on every tap. A prior fix pinned the ripple to
    // `palette.primary`, but on the Umbrella (and any red-accent Custom) theme the PRIMARY itself IS
    // red, so the press rectangle was still red. Decouple the press indication from the accent entirely:
    // pin it to the neutral INK role (light on dark themes, dark on light), so a tap can never render as
    // an alarm-red rectangle on any theme, while staying a visible, restrained highlight. Alpha is left
    // at Material's subtle default so it reads as a highlight, not a filled block.
    val ripple = RippleConfiguration(color = palette.ink)
    CompositionLocalProvider(
        LocalT1dmSemantics provides palette,
        LocalAnimationsEnabled provides animationsEnabled,
        LocalDeathMode provides deathMode,
        LocalRippleConfiguration provides ripple,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyWith(fontFamilyFor(font)),
            content = content,
        )
    }
}
