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

/** Plain holders Glance widgets read; written only via applyWidgetPalette from the FGS path. */
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

/** Does NOT touch T1dmActivePalette (the FGS's alone), so recompose can't race the widget. */
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
    // The haptics engine is built here; a preview context has no vibrator, stays mute.
    val haptics = rememberHapticsEngine(hapticStrength)
    // Pinned to neutral INK not an accent; a red-accent theme paints an alarm-red ripple.
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
