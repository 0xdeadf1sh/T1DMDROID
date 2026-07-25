package com.t1dm.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour picker's Android-free kernel. The interesting cases are all edges a finger reaches by
 * dragging a slider to its stop: the achromatic axis (where hue is undefined), the hue wrap at 360°,
 * and alpha, which must survive every trip through the saturation/value square.
 */
class ColorPickerTest {

    private fun hsvOf(argb: Int) = argbToHsv(argb)

    // ── the primaries, exactly ──────────────────────────────────────────────────────────────────

    @Test fun `the six hue sectors land on the sRGB primaries`() {
        assertEquals(0xFFFF0000.toInt(), hsvToArgb(0f, 1f, 1f))
        assertEquals(0xFFFFFF00.toInt(), hsvToArgb(60f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), hsvToArgb(120f, 1f, 1f))
        assertEquals(0xFF00FFFF.toInt(), hsvToArgb(180f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), hsvToArgb(240f, 1f, 1f))
        assertEquals(0xFFFF00FF.toInt(), hsvToArgb(300f, 1f, 1f))
    }

    @Test fun `black and white are reachable`() {
        assertEquals(0xFF000000.toInt(), hsvToArgb(210f, 0.8f, 0f))
        assertEquals(0xFFFFFFFF.toInt(), hsvToArgb(210f, 0f, 1f))
    }

    // ── the wrap ────────────────────────────────────────────────────────────────────────────────

    @Test fun `hue folds into 0 to 360 rather than clamping`() {
        assertEquals(hsvToArgb(0f, 1f, 1f), hsvToArgb(360f, 1f, 1f))
        assertEquals(hsvToArgb(30f, 1f, 1f), hsvToArgb(390f, 1f, 1f))
        // A slider dragged past its left end must wrap to the other end, not stick on red.
        assertEquals(hsvToArgb(330f, 1f, 1f), hsvToArgb(-30f, 1f, 1f))
    }

    @Test fun `a decomposed hue is always in 0 to 360`() {
        for (deg in -720..1080 step 7) {
            val h = hsvOf(hsvToArgb(deg.toFloat(), 1f, 1f))[0]
            assertTrue("hue $h out of range for input $deg", h >= 0f && h < 360f)
        }
    }

    // ── the achromatic axis ─────────────────────────────────────────────────────────────────────

    @Test fun `grey reports zero hue and zero saturation rather than NaN`() {
        val hsv = hsvOf(0xFF808080.toInt())
        assertEquals(0f, hsv[0], 0f)
        assertEquals(0f, hsv[1], 0f)
        assertTrue(hsv[2] > 0.49f && hsv[2] < 0.51f)
    }

    @Test fun `pure black decomposes without dividing by zero`() {
        val hsv = hsvOf(0xFF000000.toInt())
        assertEquals(0f, hsv[0], 0f)
        assertEquals(0f, hsv[1], 0f)
        assertEquals(0f, hsv[2], 0f)
    }

    // ── round trips ─────────────────────────────────────────────────────────────────────────────

    @Test fun `hsv round-trips within one 8-bit step`() {
        var worst = 0
        for (h in 0 until 360 step 13) {
            for (s in 1..10) {
                for (v in 1..10) {
                    val argb = hsvToArgb(h.toFloat(), s / 10f, v / 10f)
                    val back = hsvOf(argb)
                    val again = hsvToArgb(back[0], back[1], back[2])
                    for (shift in intArrayOf(16, 8, 0)) {
                        val d = kotlin.math.abs(((argb shr shift) and 0xFF) - ((again shr shift) and 0xFF))
                        if (d > worst) worst = d
                    }
                }
            }
        }
        assertTrue("round trip drifted by $worst levels", worst <= 1)
    }

    // ── alpha is carried separately, and survives ───────────────────────────────────────────────

    @Test fun `alpha survives a pass through the saturation value square`() {
        val start = hsvToArgb(200f, 0.8f, 0.9f, alpha = 0.25f)
        assertEquals(0.25f, argbAlpha(start), 1f / 255f)
        // What the square does: re-pack the same alpha with new s/v.
        val moved = hsvToArgb(argbToHsv(start)[0], 0.1f, 0.2f, argbAlpha(start))
        assertEquals(0.25f, argbAlpha(moved), 1f / 255f)
    }

    @Test fun `argbWithAlpha replaces only the alpha channel`() {
        val c = 0x8012_3456.toInt()
        val opaque = argbWithAlpha(c, 1f)
        assertEquals(0xFF, (opaque ushr 24) and 0xFF)
        assertEquals(c and 0x00FFFFFF, opaque and 0x00FFFFFF)
        assertEquals(0, (argbWithAlpha(c, 0f) ushr 24) and 0xFF)
    }

    @Test fun `alpha and channels clamp instead of wrapping`() {
        assertEquals(0xFF, (hsvToArgb(0f, 1f, 1f, 4f) ushr 24) and 0xFF)
        assertEquals(0x00, (hsvToArgb(0f, 1f, 1f, -1f) ushr 24) and 0xFF)
        assertEquals(0xFFFF0000.toInt(), hsvToArgb(0f, 9f, 9f))
    }
}
