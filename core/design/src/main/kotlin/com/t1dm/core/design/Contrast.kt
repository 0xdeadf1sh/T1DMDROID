package com.t1dm.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** WCAG AA for body text. */
const val CONTRAST_AA: Float = 4.5f

/** WCAG relative luminance of an OPAQUE argb, 0f..1f; composite with compositeArgb first. */
fun relativeLuminanceArgb(argb: Int): Float {
    fun channel(shift: Int): Float {
        val s = ((argb ushr shift) and 0xFF) / 255f
        return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
    }
    return 0.2126f * channel(16) + 0.7152f * channel(8) + 0.0722f * channel(0)
}

/** WCAG contrast ratio: 1.0 for identical colours, 21.0 for black on white. */
fun contrastRatioArgb(a: Int, b: Int): Float {
    val la = relativeLuminanceArgb(a)
    val lb = relativeLuminanceArgb(b)
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/** Source-over composite of [fg] onto [bg], taken as opaque. Result is opaque. */
fun compositeArgb(fg: Int, bg: Int): Int {
    val a = ((fg ushr 24) and 0xFF) / 255f
    if (a >= 1f) return fg or (0xFF shl 24)
    fun mix(shift: Int): Int {
        val f = (fg ushr shift) and 0xFF
        val b = (bg ushr shift) and 0xFF
        return (f * a + b * (1f - a)).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

/** Both taken as opaque; composite first if not. */
fun contrastRatio(a: Color, b: Color): Float = contrastRatioArgb(a.toArgb(), b.toArgb())

/** [background] is taken as opaque. */
fun Color.compositeOn(background: Color): Color =
    Color(compositeArgb(toArgb(), background.toArgb()))

/** Black or white, whichever contrasts more; curves cross at L≈0.179, clears AA over any colour. */
fun maxContrastInk(background: Color): Color {
    val bg = background.toArgb()
    val black = Color.Black.toArgb()
    val white = Color.White.toArgb()
    return if (contrastRatioArgb(black, bg) >= contrastRatioArgb(white, bg)) Color.Black else Color.White
}

/** preferred where it clears floor on on, else maxContrastInk; both must be OPAQUE. */
fun legibleInkOn(on: Color, preferred: Color, floor: Float = CONTRAST_AA): Color =
    legibleInkOver(on, on, preferred, floor)

/** legibleInkOn for ALPHA roles: composited for the ratio, preferred returned unmodified. */
fun legibleInkOver(backing: Color, on: Color, preferred: Color, floor: Float = CONTRAST_AA): Color {
    val painted = on.compositeOn(backing)
    return if (contrastRatio(preferred.compositeOn(painted), painted) >= floor) preferred
    else maxContrastInk(painted)
}
