package com.t1dm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both entries fail silently: off-wheel is unreachable, crumbsFor falls to a raw-route crumb. */
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
        // A null route on the ancestor crumb renders as dead text.
        assertEquals("exercise", review.first().route)
        assertEquals(null, review.last().route)
    }
}
