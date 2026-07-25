package com.t1dm.feature.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vertical camera is a RESCUE, not a follow-cam: it must sit perfectly still while the car is
 * inside the view — otherwise drive mode would scroll the BG axis away from where the user left it —
 * and it must move the moment the car would otherwise leave the panel, which is what happened on a
 * hyper excursion when the view was pinned to the configured range.
 */
class GameCameraTest {

    private val viewH = 100f

    @Test
    fun `a car inside the band does not move the view at all`() {
        val c = GameCamera()
        for (carY in listOf(20f, 50f, 80f)) {
            assertEquals("carY=$carY", 0f, c.targetBottomFrom(0f, carY, viewH), 0.0001f)
        }
    }

    @Test
    fun `a car above the top band lifts the view by exactly enough to reach the band edge`() {
        val c = GameCamera()
        // Band is 18 %, so the top edge sits at bottom + 82.
        val bottom = c.targetBottomFrom(0f, 95f, viewH)
        assertTrue("the view must move", bottom > 0f)
        assertEquals("car lands on the band edge", 95f, bottom + viewH * (1f - 0.18f), 0.001f)
    }

    @Test
    fun `a car below the bottom band drops the view, but never past the floor margin`() {
        val c = GameCamera()
        val bottom = c.targetBottomFrom(50f, 52f, viewH)
        assertTrue("the view must move down", bottom < 50f)
        // Far below: clamped rather than scrolling the ground off the panel entirely.
        assertTrue("floor clamp holds", c.targetBottomFrom(0f, -900f, viewH) >= -2.5f)
    }

    @Test
    fun `the rule is stable — re-applying it to its own result changes nothing`() {
        val c = GameCamera()
        val once = c.targetBottomFrom(0f, 95f, viewH)
        assertEquals(once, c.targetBottomFrom(once, 95f, viewH), 0.0001f)
    }
}
