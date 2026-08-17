package com.t1dm.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [navIcon] resolves an unregistered route to the settings gear, silently — nothing throws, nothing
 * logs, and the wheel simply shows a second gear where a glyph should be. A route added to the ring
 * without a branch here therefore looks like a working panel until somebody notices the icon, which
 * is exactly the failure this holds shut.
 *
 * The fallback itself is asserted too: without it the first assertion would pass for a reason it does
 * not mean to (a fallback that stopped being the gear would make every route "distinct" from it).
 */
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
