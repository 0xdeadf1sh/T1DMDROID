package com.t1dm.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** The glucose-band and grid roles Material's [ColorScheme] cannot express. */
val LocalT1dmSemantics = staticCompositionLocalOf { TronPalette }

val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/** DEATH mode: warning surfaces render nothing while it is on. */
val LocalDeathMode = staticCompositionLocalOf { false }

/**
 * Plain holders the non-Compose Glance widgets read. Written only through [applyWidgetPalette] and
 * only from the widget-render path: the Activity must never write them, or its per-second recompose
 * races the FGS and the widget renders a beat behind the theme change.
 */
@Volatile
var T1dmColorScheme: ColorScheme = TronPalette.toColorScheme()
    private set

@Volatile
var T1dmActivePalette: T1dmPalette = TronPalette
    private set

/** The SOLE writer of [T1dmActivePalette] / [T1dmColorScheme]; needs no composition. */
fun applyWidgetPalette(palette: T1dmPalette) {
    T1dmActivePalette = palette
    T1dmColorScheme = palette.toColorScheme()
}

/** Deliberately does NOT touch the widget holders ([T1dmActivePalette]): those are the FGS's alone,
 *  so a foreground recompose cannot race the widget render. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun T1dmTheme(
    palette: T1dmPalette = TronPalette,
    font: T1dmFontId = T1dmFontId.SYSTEM,
    animationsEnabled: Boolean = true,
    deathMode: Boolean = false,
    hapticStrength: HapticStrength = HapticStrength.DEFAULT,
    content: @Composable () -> Unit,
) {
    val scheme = palette.toColorScheme()
    // The one place the process haptics engine is built. A preview context has no vibrator, so a
    // preview stays mute.
    val haptics = rememberHapticsEngine(hapticStrength)
    // Pinned to the neutral INK role, not to an accent: Material's ripple tints from the local content
    // colour, and on a surface resolving to `error` — or on a red-accent theme, to `primary` — a
    // bounded ripple painted an alarm-red rectangle on every tap.
    val ripple = RippleConfiguration(color = palette.ink)
    CompositionLocalProvider(
        LocalT1dmSemantics provides palette,
        LocalAnimationsEnabled provides animationsEnabled,
        LocalDeathMode provides deathMode,
        LocalT1dmHaptics provides haptics,
        LocalRippleConfiguration provides ripple,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyWith(fontFamilyFor(font)),
            content = content,
        )
    }
}
