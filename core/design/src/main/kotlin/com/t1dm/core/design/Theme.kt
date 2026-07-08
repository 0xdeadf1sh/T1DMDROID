package com.t1dm.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Minimal single Tron-ish dark scheme so feature modules compile and render. The full
// three-theme + JSON-theme token system lands later (PLAN.private.md §3.4).
private val TronCyan = Color(0xFF00E5FF)
private val TronAmber = Color(0xFFFFB300)
private val TronDeep = Color(0xFF060A12)
private val TronPanel = Color(0xFF0E1626)
private val TronInk = Color(0xFFDCEAF5)

private val T1dmDarkColors = darkColorScheme(
    primary = TronCyan,
    onPrimary = TronDeep,
    secondary = TronAmber,
    onSecondary = TronDeep,
    background = TronDeep,
    onBackground = TronInk,
    surface = TronPanel,
    onSurface = TronInk,
)

@Composable
fun T1dmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = T1dmDarkColors, content = content)
}
