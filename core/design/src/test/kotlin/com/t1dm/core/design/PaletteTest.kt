package com.t1dm.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PaletteTest {

    @Test fun `polyphemus follows the system night mode`() {
        assertSame(PolyphemusDark, resolvePalette(ThemeIds.POLYPHEMUS, null, systemDark = true))
        assertSame(PolyphemusLight, resolvePalette(ThemeIds.POLYPHEMUS, null, systemDark = false))
    }

    @Test fun `a fixed theme ignores the system night mode`() {
        assertSame(HelloKittyPalette, resolvePalette(ThemeIds.HELLO_KITTY, null, systemDark = true))
        assertSame(TronPalette, resolvePalette(ThemeIds.TRON, null, systemDark = false))
    }

    @Test fun `an unknown id falls back to polyphemus`() {
        assertEquals(ThemeIds.POLYPHEMUS, normalizeThemeId("windows_xp"))
        assertSame(PolyphemusLight, resolvePalette("windows_xp", null, systemDark = false))
    }

    @Test fun `the picker lists polyphemus once, first`() {
        val ids = BundledPalettes.distinctBy { it.id }.map { it.id }
        assertEquals(ThemeIds.POLYPHEMUS, ids.first())
        assertEquals(1, ids.count { it == ThemeIds.POLYPHEMUS })
    }
}
