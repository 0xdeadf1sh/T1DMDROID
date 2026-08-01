package com.t1dm.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast kernel, and the two claims the themed panel surfaces make with it: that every bundled
 * palette's own ink is legible on its own panel, and that a palette which makes that false is caught
 * rather than shipped.
 *
 * The interesting cases are all at the ends — the anchors WCAG fixes exactly (black on white = 21),
 * the crossover where black and white are equally bad, and the imported theme that collapses
 * `surfaceVariant` onto `surface`, which [parseThemeJson] does by design when the JSON omits it.
 */
class ContrastTest {

    // ── the kernel, against values WCAG pins exactly ────────────────────────────────────────────

    @Test fun `luminance anchors at black and white`() {
        assertEquals(0f, relativeLuminanceArgb(Color.Black.toArgb()), 1e-6f)
        assertEquals(1f, relativeLuminanceArgb(Color.White.toArgb()), 1e-6f)
    }

    @Test fun `black on white is 21 and a colour on itself is 1`() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 1e-3f)
        assertEquals(1f, contrastRatio(TronPalette.primary, TronPalette.primary), 1e-6f)
    }

    @Test fun `the ratio does not care which colour is named first`() {
        val a = TronPalette.ink
        val b = TronPalette.surface
        assertEquals(contrastRatio(a, b), contrastRatio(b, a), 1e-6f)
    }

    @Test fun `sRGB is not linear — mid grey is nowhere near half`() {
        // The whole reason the kernel cannot average channels: #808080 sits at ~0.216, not 0.5.
        assertEquals(0.2158f, relativeLuminanceArgb(0xFF808080.toInt()), 1e-3f)
    }

    // ── compositing ─────────────────────────────────────────────────────────────────────────────

    @Test fun `a fully opaque foreground is itself and a fully transparent one is the background`() {
        val fg = 0xFF00E5FF.toInt()
        val bg = 0xFF060A12.toInt()
        assertEquals(fg, compositeArgb(fg, bg))
        assertEquals(bg, compositeArgb(fg and 0x00FFFFFF, bg))
    }

    @Test fun `half alpha lands halfway, per channel`() {
        val out = compositeArgb(argbWithAlpha(0xFFFFFFFF.toInt(), 0.5f), 0xFF000000.toInt())
        // 0.5f quantises to 128/255, so the midpoint is 128 rather than 127.
        assertEquals(128, (out shr 16) and 0xFF)
        assertEquals(128, (out shr 8) and 0xFF)
        assertEquals(128, out and 0xFF)
        assertEquals(0xFF, (out ushr 24) and 0xFF)
    }

    @Test fun `a composite is always opaque, whatever went in`() {
        val out = compositeArgb(argbWithAlpha(0xFF123456.toInt(), 0.3f), 0x00FFFFFF)
        assertEquals(0xFF, (out ushr 24) and 0xFF)
    }

    // ── the unconditional floor ─────────────────────────────────────────────────────────────────

    @Test fun `black-or-white clears AA over every colour there is`() {
        // The claim [maxContrastInk] rests on. Swept coarsely over the whole cube, plus the greys
        // around the crossover (L ~ 0.179) where the two candidates are at their closest.
        var worst = Float.MAX_VALUE
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val c = Color(r / 255f, g / 255f, b / 255f)
            worst = minOf(worst, contrastRatio(maxContrastInk(c), c))
        }
        for (v in 100..160) {
            val c = Color(v / 255f, v / 255f, v / 255f)
            worst = minOf(worst, contrastRatio(maxContrastInk(c), c))
        }
        assertTrue("worst black/white contrast was $worst", worst >= CONTRAST_AA)
    }

    @Test fun `the fallback flips sides across the crossover`() {
        assertEquals(Color.Black, maxContrastInk(Color.White))
        assertEquals(Color.White, maxContrastInk(Color.Black))
        assertEquals(Color.Black, maxContrastInk(TronPalette.inRange))
        assertEquals(Color.White, maxContrastInk(TronPalette.surface))
    }

    // ── the panel's ink, per palette ────────────────────────────────────────────────────────────

    @Test fun `every bundled palette's ink clears AA on its own panel`() {
        for (p in BundledPalettes) {
            val ratio = contrastRatio(p.ink, p.surfaceVariant)
            assertTrue("${p.id}: ink on surfaceVariant was $ratio", ratio >= CONTRAST_AA)
        }
    }

    @Test fun `a bundled palette therefore keeps its own ink, never the fallback`() {
        for (p in BundledPalettes) {
            assertEquals(p.id, p.ink, legibleInkOn(p.surfaceVariant, p.ink))
        }
    }

    @Test fun `the panel is a different colour under every bundled palette`() {
        // "Theme-variant" is the whole point: the three must not resolve to one shared grey.
        val containers = BundledPalettes.map { it.surfaceVariant }
        assertEquals(containers.size, containers.distinct().size)
        for (p in BundledPalettes) {
            // …and none of them is Material's baseline card grey, on either scheme.
            assertNotEquals(Color(0xFF36343B), p.surfaceVariant)
            assertNotEquals(Color(0xFFE6E0E9), p.surfaceVariant)
        }
    }

    // ── the hostile import ──────────────────────────────────────────────────────────────────────

    /**
     * An imported palette as [parseThemeJson] would build one: `surfaceVariant` is optional there and
     * derives from `surface` outright, so a minimal import paints its panels the same colour as its
     * page. Assembled directly rather than parsed — the parser is `org.json`, which is an unmocked
     * stub in a JVM unit test; what is under test here is the panel's reaction, not the parse.
     */
    private fun imported(surface: Color, ink: Color) = TronPalette.copy(
        id = ThemeIds.CUSTOM,
        surface = surface,
        surfaceVariant = surface,
        ink = ink,
    )

    @Test fun `an import that omits surfaceVariant gets surface, and the panel still holds its ink`() {
        val p = imported(surface = Color(0xFF101010), ink = Color(0xFFEEEEEE))
        assertEquals(p.surface, p.surfaceVariant)
        assertEquals(p.ink, legibleInkOn(p.surfaceVariant, p.ink))
    }

    @Test fun `an import whose ink collides with its panel falls back rather than going invisible`() {
        val p = imported(surface = Color(0xFF303030), ink = Color(0xFF3A3A3A))
        val ink = legibleInkOn(p.surfaceVariant, p.ink)
        assertNotEquals(p.ink, ink)
        assertTrue(contrastRatio(ink, p.surfaceVariant) >= CONTRAST_AA)
    }

    @Test fun `the fallback fires on a light hostile import too, and picks the other side`() {
        val p = imported(surface = Color(0xFFF2F2F2), ink = Color(0xFFE8E8E8))
        assertEquals(Color.Black, legibleInkOn(p.surfaceVariant, p.ink))
    }

    @Test fun `no palette can defeat the panel — the ink it ends up with always clears AA`() {
        for (s in 0..255 step 17) for (i in 0..255 step 17) {
            val p = imported(Color(s / 255f, s / 255f, s / 255f), Color(i / 255f, i / 255f, i / 255f))
            val ratio = contrastRatio(legibleInkOn(p.surfaceVariant, p.ink), p.surfaceVariant)
            assertTrue("surface $s / ink $i resolved to $ratio", ratio >= CONTRAST_AA)
        }
    }

    // ── the TRANSLUCENT import: parseThemeJson accepts #AARRGGBB for every role it parses ────────

    @Test fun `a translucent panel is judged as painted, not as opaque`() {
        // 12.5 % white over a near-black page paints ~#252930. Read raw, the container looks like
        // white and a pale ink looks illegible on it, so the guard flips to black — the worse of the
        // two over what is actually drawn.
        val page = Color(0xFF060A12)
        val panel = Color(0x20FFFFFF)
        val ink = Color(0xFFDCEAF5)
        assertEquals("raw, the guard rejects a legible ink", Color.Black, legibleInkOn(panel, ink))
        assertEquals("composited, it keeps it", ink, legibleInkOver(page, panel, ink))
    }

    @Test fun `a translucent ink is measured over the panel it is drawn on`() {
        // Alpha thins the ink toward the panel. The same RGB clears AA at full strength and does not
        // at 20 %, and the guard has to reach opposite conclusions about the two.
        val page = Color(0xFF060A12)
        val panel = Color(0xFF152134)
        assertEquals(Color(0xFFDCEAF5), legibleInkOver(page, panel, Color(0xFFDCEAF5)))
        assertNotEquals(Color(0x33DCEAF5), legibleInkOver(page, panel, Color(0x33DCEAF5)))
    }

    @Test fun `the opaque form IS the alpha-safe one, so the two cannot disagree`() {
        for (s in 0..255 step 17) for (i in 0..255 step 17) {
            val on = Color(s / 255f, s / 255f, s / 255f)
            val ink = Color(i / 255f, i / 255f, i / 255f)
            assertEquals(legibleInkOver(on, on, ink), legibleInkOn(on, ink))
        }
    }

    @Test fun `whatever it returns, what is PAINTED clears AA — at any panel alpha`() {
        val page = Color(0xFF060A12)
        for (a in 0..255 step 51) for (s in 0..255 step 51) for (i in 0..255 step 85) {
            val panel = Color(s / 255f, s / 255f, s / 255f, a / 255f)
            val preferred = Color(i / 255f, i / 255f, i / 255f)
            val painted = panel.compositeOn(page)
            val ratio = contrastRatio(legibleInkOver(page, panel, preferred).compositeOn(painted), painted)
            assertTrue("alpha $a / panel $s / ink $i resolved to $ratio", ratio >= CONTRAST_AA)
        }
    }
}
