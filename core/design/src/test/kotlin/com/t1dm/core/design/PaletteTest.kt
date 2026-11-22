package com.t1dm.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {

    @Test fun `polyphemus is dark only`() {
        assertSame(PolyphemusPalette, resolvePalette(ThemeIds.POLYPHEMUS))
        assertTrue(PolyphemusPalette.dark)
    }

    @Test fun `a fixed theme resolves to itself`() {
        assertSame(HelloKittyPalette, resolvePalette(ThemeIds.HELLO_KITTY))
        assertSame(TronPalette, resolvePalette(ThemeIds.TRON))
    }

    @Test fun `an unknown id falls back to polyphemus`() {
        assertEquals(ThemeIds.POLYPHEMUS, normalizeThemeId("windows_xp"))
        assertSame(PolyphemusPalette, resolvePalette("windows_xp"))
    }

    @Test fun `the retired custom id falls back to polyphemus`() {
        assertEquals(ThemeIds.POLYPHEMUS, normalizeThemeId("custom"))
    }

    @Test fun `the picker lists polyphemus once, first`() {
        val ids = BundledPalettes.map { it.id }
        assertEquals(ThemeIds.POLYPHEMUS, ids.first())
        assertEquals(ids.size, ids.distinct().size)
    }
}
