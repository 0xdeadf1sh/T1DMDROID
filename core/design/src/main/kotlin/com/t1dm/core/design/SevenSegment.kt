package com.t1dm.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * A real seven-segment LCD, drawn cell by cell: `dd:hh:mm:ss` where every digit is seven mitred bars
 * and the UNLIT bars are still painted, faintly, beneath the lit ones — the ghost of the dead cells is
 * what makes a physical display read as a display rather than as text in a monospace font. Everything
 * derives from the two colour roles the caller passes, so the readout takes each theme's own light.
 *
 * Two properties are load-bearing for the circadian panel's insulin-exhaustion countdown:
 *
 *  - [remainingMs] is a LAMBDA, invoked inside the draw scope. A 1 s ticker held as a
 *    [androidx.compose.runtime.State] and read only here invalidates DRAW alone — the surrounding
 *    panel never recomposes (the idiom Scrollbar.kt and Pulse.kt already lean on).
 *  - a lapsed span reads `00:00:00:00` in [lapsed]; the split clamps at zero, so a landmark already in
 *    the past shows a stopped clock rather than a negative wrap.
 *
 * The colon pulse is the one piece of DECORATION here, so it is gated on [LocalAnimationsEnabled]
 * (Motion.kt): with motion off the colons simply stay lit. The digits keep ticking either way — they
 * carry data, not motion.
 */

/**
 * The classic segment map, bit 0…6 = a…g: a top, b upper-right, c lower-right, d bottom, e lower-left,
 * f upper-left, g middle.
 */
private val SEGMENT_MASKS = intArrayOf(
    0x3F, // 0 — abcdef
    0x06, // 1 — bc
    0x5B, // 2 — abdeg
    0x4F, // 3 — abcdg
    0x66, // 4 — bcfg
    0x6D, // 5 — acdfg
    0x7D, // 6 — acdefg
    0x07, // 7 — abc
    0x7F, // 8 — abcdefg
    0x6F, // 9 — abcdfg
)

/** Segments lit for [digit]; anything outside 0–9 yields a BLANK cell (0) rather than a wrong glyph. */
internal fun sevenSegmentMask(digit: Int): Int = if (digit in 0..9) SEGMENT_MASKS[digit] else 0

/**
 * `[dd, hh, mm, ss]` for a remaining span, clamped at zero. The day field is capped at 99 999 so a
 * user who dials an absurd offset gets a saturated readout instead of an `Int` wrap.
 */
internal fun ddHhMmSs(remainingMs: Long): IntArray {
    val s = (remainingMs / 1000L).coerceAtLeast(0L)
    return intArrayOf(
        (s / 86_400L).coerceAtMost(99_999L).toInt(),
        ((s % 86_400L) / 3_600L).toInt(),
        ((s % 3_600L) / 60L).toInt(),
        (s % 60L).toInt(),
    )
}

private const val DIGIT_ASPECT = 0.58f // cell width ÷ cell height
private const val COLON_ASPECT = 0.42f // colon cell width ÷ digit cell width
private const val CELL_GAP = 0.13f // inter-cell gap ÷ digit cell width
private const val SEG_SLANT = 0.07f // italic shear, x per unit y from the cell's middle
private const val GHOST_ALPHA = 0.14f // the unlit cells a physical LCD still shows

private const val COLON_CELL = -1

@Composable
fun SevenSegmentClock(
    remainingMs: () -> Long,
    modifier: Modifier = Modifier,
    lit: Color = MaterialTheme.colorScheme.primary,
    lapsed: Color = MaterialTheme.colorScheme.error,
) {
    val pulse = LocalAnimationsEnabled.current
    Canvas(modifier) {
        val ms = remainingMs()
        val on = if (ms <= 0L) lapsed else lit
        drawLcd(ddHhMmSs(ms), on, colonLit = !pulse || (ms / 1000L) % 2L == 0L)
    }
}

private fun DrawScope.drawLcd(fields: IntArray, on: Color, colonLit: Boolean) {
    // The day field widens past 99 rather than truncating; the layout below simply gets one more cell.
    val dayGlyphs = maxOf(2, fields[0].toString().length)
    val cells = ArrayList<Int>(dayGlyphs + 9)
    var d = fields[0]
    val dayDigits = IntArray(dayGlyphs)
    for (i in dayGlyphs - 1 downTo 0) {
        dayDigits[i] = d % 10
        d /= 10
    }
    dayDigits.forEach { cells += it }
    for (f in 1..3) {
        cells += COLON_CELL
        cells += fields[f] / 10
        cells += fields[f] % 10
    }

    val nColons = cells.count { it == COLON_CELL }
    val units = (cells.size - nColons) + nColons * COLON_ASPECT + (cells.size - 1) * CELL_GAP
    var dw = size.height * DIGIT_ASPECT
    if (dw * units > size.width) dw = size.width / units
    val dh = dw / DIGIT_ASPECT
    // Right-aligned: the readout is a value in a label/value row, and the seconds column must not
    // wander horizontally as the day field gains a digit.
    var x = size.width - dw * units
    val y = (size.height - dh) / 2f

    for (c in cells) {
        if (c == COLON_CELL) {
            drawColon(x, y, dw * COLON_ASPECT, dh, on, colonLit)
            x += dw * COLON_ASPECT
        } else {
            drawDigit(c, x, y, dw, dh, on)
            x += dw
        }
        x += dw * CELL_GAP
    }
}

private fun DrawScope.drawDigit(digit: Int, left: Float, top: Float, w: Float, h: Float, on: Color) {
    val mask = sevenSegmentMask(digit)
    val t = h * 0.058f // half-thickness of a bar
    val gap = t * 0.55f // the hairline between two abutting bars
    val xl = left + t * 1.6f
    val xr = left + w - t * 1.6f
    val yt = top + t * 1.7f
    val yb = top + h - t * 1.7f
    val ym = top + h / 2f
    val ghost = on.copy(alpha = GHOST_ALPHA)
    val segs = arrayOf(
        barH(xl, xr, yt, t, gap, ym), // a
        barV(xr, yt, ym, t, gap, ym), // b
        barV(xr, ym, yb, t, gap, ym), // c
        barH(xl, xr, yb, t, gap, ym), // d
        barV(xl, ym, yb, t, gap, ym), // e
        barV(xl, yt, ym, t, gap, ym), // f
        barH(xl, xr, ym, t, gap, ym), // g
    )
    for (i in 0..6) drawPath(segs[i], if ((mask shr i) and 1 == 1) on else ghost)
}

private fun DrawScope.drawColon(left: Float, top: Float, w: Float, h: Float, on: Color, lit: Boolean) {
    val ym = top + h / 2f
    val c = if (lit) on else on.copy(alpha = GHOST_ALPHA)
    for (s in intArrayOf(-1, 1)) {
        val y = ym + s * h * 0.17f
        drawCircle(c, radius = h * 0.055f, center = Offset(shear(left + w / 2f, y, ym), y))
    }
}

/** The italic lean every LCD has: above the cell's middle the glyph slides right, below it, left. */
private fun shear(x: Float, y: Float, ym: Float): Float = x + (ym - y) * SEG_SLANT

/** A horizontal bar with mitred (pointed) ends, so abutting segments meet at a hairline. */
private fun barH(x0: Float, x1: Float, y: Float, t: Float, gap: Float, ym: Float): Path {
    val a = x0 + gap
    val b = x1 - gap
    return Path().apply {
        moveTo(shear(a, y, ym), y)
        lineTo(shear(a + t, y - t, ym), y - t)
        lineTo(shear(b - t, y - t, ym), y - t)
        lineTo(shear(b, y, ym), y)
        lineTo(shear(b - t, y + t, ym), y + t)
        lineTo(shear(a + t, y + t, ym), y + t)
        close()
    }
}

private fun barV(x: Float, y0: Float, y1: Float, t: Float, gap: Float, ym: Float): Path {
    val a = y0 + gap
    val b = y1 - gap
    return Path().apply {
        moveTo(shear(x, a, ym), a)
        lineTo(shear(x + t, a + t, ym), a + t)
        lineTo(shear(x + t, b - t, ym), b - t)
        lineTo(shear(x, b, ym), b)
        lineTo(shear(x - t, b - t, ym), b - t)
        lineTo(shear(x - t, a + t, ym), a + t)
        close()
    }
}
