package com.t1dm.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Contrast arithmetic (WCAG 2.1 relative luminance), and the two guarantees the themed surfaces rest
 * on.
 *
 * It exists because three of this app's four palettes are *designed* and the fourth is *imported*.
 * [parseThemeJson] requires `background`, `surface`, `primary`, `secondary`, `ink` and the five bands
 * and derives the rest — `surfaceVariant` falls back to `surface` outright — and it validates no
 * relationship whatever between any two of them. A panel painted in a role a designer never saw can
 * therefore be arbitrarily close to the ink written over it. Every surface that wants to claim it is
 * legible needs a way to *ask*, and a defined answer for when the answer is no.
 *
 * The kernel takes packed `Int` argb and touches nothing Android — the same split [ColorPicker]'s
 * `argb*` helpers keep — so the palette guarantees can be asserted in a plain JVM unit test rather
 * than an instrumented one.
 */

/** WCAG AA for body text. The floor a panel's ink is held to. */
const val CONTRAST_AA: Float = 4.5f

/**
 * WCAG relative luminance of an OPAQUE argb, 0f (black) … 1f (white).
 *
 * Alpha is ignored rather than approximated: a contrast ratio between a translucent colour and
 * anything is undefined, so a colour carrying alpha must be composited onto its actual backing with
 * [compositeArgb] first. Handing one in raw is a caller error that silently reads as opaque.
 */
fun relativeLuminanceArgb(argb: Int): Float {
    fun channel(shift: Int): Float {
        val s = ((argb ushr shift) and 0xFF) / 255f
        return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
    }
    return 0.2126f * channel(16) + 0.7152f * channel(8) + 0.0722f * channel(0)
}

/** WCAG contrast ratio: 1.0 for two identical colours, 21.0 for black on white. Symmetric. */
fun contrastRatioArgb(a: Int, b: Int): Float {
    val la = relativeLuminanceArgb(a)
    val lb = relativeLuminanceArgb(b)
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/** Source-over composite of [fg] (its alpha honoured) onto [bg] (taken as opaque). Result is opaque. */
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

/** [contrastRatioArgb] over Compose colours. Both are taken as opaque — composite first if not. */
fun contrastRatio(a: Color, b: Color): Float = contrastRatioArgb(a.toArgb(), b.toArgb())

/** [compositeArgb] over Compose colours: this colour's alpha laid over an opaque [background]. */
fun Color.compositeOn(background: Color): Color =
    Color(compositeArgb(toArgb(), background.toArgb()))

/**
 * Black or white over [background], whichever contrasts more.
 *
 * The choice is worth stating exactly, because it is the only ink in the app with an *unconditional*
 * floor. Black's ratio against a background of relative luminance L is `(L + 0.05) / 0.05` and
 * white's is `1.05 / (L + 0.05)`; the two cross at L ≈ 0.179, where both stand at 4.58. Taking the
 * better of the pair therefore clears WCAG AA over every colour that exists — which is precisely why
 * it, and not some other palette role, is what a hostile theme falls back to.
 */
fun maxContrastInk(background: Color): Color {
    val bg = background.toArgb()
    val black = Color.Black.toArgb()
    val white = Color.White.toArgb()
    return if (contrastRatioArgb(black, bg) >= contrastRatioArgb(white, bg)) Color.Black else Color.White
}

/**
 * [preferred] where it still reads on [on], else [maxContrastInk] — for two colours already known
 * OPAQUE. The alpha-safe form is [legibleInkOver]; this is that function's degenerate case and is
 * defined as it, so the two can never answer differently.
 *
 * The fallback fires only on a palette whose own roles collide — all three bundled themes clear
 * [floor] comfortably and are unaffected — so it is a fail-closed backstop, not a colour policy. It
 * cannot itself fail: see [maxContrastInk] for why its result is always ≥ 4.58.
 */
fun legibleInkOn(on: Color, preferred: Color, floor: Float = CONTRAST_AA): Color =
    legibleInkOver(on, on, preferred, floor)

/**
 * [legibleInkOn] for roles that may carry ALPHA — which, in an imported palette, any of them may.
 *
 * [relativeLuminanceArgb] states the precondition it cannot enforce: a translucent colour handed in
 * raw reads as opaque, and the ratio computed from it is a ratio between two colours neither of
 * which is on screen. [parseThemeJson] accepts `#AARRGGBB` for every role it parses, validates no
 * relationship between any two of them, and mints a translucent one of its own (`inkMuted`), so a
 * surface role arriving here carrying alpha is ordinary rather than pathological. Read raw it can
 * invert the choice outright — picking the ink the guard exists to reject.
 *
 * So both colours are composited onto [backing], the opaque thing actually behind them, before the
 * ratio is taken. What is RETURNED is [preferred] itself, unmodified: its own translucency is part
 * of the design, and the caller draws it over the very backing this measured it against.
 */
fun legibleInkOver(backing: Color, on: Color, preferred: Color, floor: Float = CONTRAST_AA): Color {
    val painted = on.compositeOn(backing)
    return if (contrastRatio(preferred.compositeOn(painted), painted) >= floor) preferred
    else maxContrastInk(painted)
}
