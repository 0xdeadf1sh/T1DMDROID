package com.t1dm.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NavIconsTest {

    @Test
    fun `the exercise route has its own glyph in every geometry`() {
        IconStyle.entries.forEach { style ->
            assertNotEquals(
                "exercise falls through to the settings gear in $style",
                navIcon("settings", style).name,
                navIcon("exercise", style).name,
            )
        }
    }

    @Test
    fun `an unregistered route still falls back to the gear`() {
        IconStyle.entries.forEach { style ->
            assertEquals(
                navIcon("settings", style).name,
                navIcon("no-such-route", style).name,
            )
        }
    }
}
