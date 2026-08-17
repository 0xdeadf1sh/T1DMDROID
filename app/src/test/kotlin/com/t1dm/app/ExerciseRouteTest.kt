package com.t1dm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exercise panel's two registry entries, both of which fail SILENTLY when missed.
 *
 * [destinations] IS the nav wheel's ring, so a panel absent from it is unreachable while its route
 * still resolves — the screen exists and nothing points at it. And [crumbsFor] falls through to a
 * single crumb whose label is the raw route string, so an unregistered sub-route renders
 * `exercise/{sessionId}` as its own breadcrumb rather than failing.
 */
class ExerciseRouteTest {

    @Test
    fun `the exercise panel is on the wheel`() {
        assertTrue(
            "exercise is not in the nav wheel's ring",
            destinations.any { it.route == "exercise" },
        )
    }

    @Test
    fun `the panel and its session review both have a trail`() {
        assertEquals(listOf(Crumb("Exercise", null)), crumbsFor("exercise", null))

        val review = crumbsFor("exercise/{sessionId}", null)
        assertNotEquals(
            "the session review has no breadcrumb entry",
            listOf(Crumb("exercise/{sessionId}", null)),
            review,
        )
        assertEquals(2, review.size)
        // The ancestor crumb must ascend to the hub; a null route there renders as dead text.
        assertEquals("exercise", review.first().route)
        assertEquals(null, review.last().route)
    }
}
