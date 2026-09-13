package com.t1dm.feature.exercise

import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewWindowTest {

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    private fun bout(endMs: Long?) = ExerciseSession(
        id = 1, startMs = t0, endMs = endMs, tzOffsetMin = 0, kind = ExerciseKind.RUN,
        activeSec = 0, distanceM = null, kcal = null, interrupted = false,
    )

    @Test
    fun a_bout_longer_than_the_floor_is_the_whole_window() {
        assertEquals(t0..(t0 + 45 * min), reviewWindow(bout(t0 + 45 * min), nowMs = t0 + 600 * min))
    }

    @Test
    fun a_short_bout_is_padded_evenly_to_the_floor() {
        assertEquals((t0 - 10 * min)..(t0 + 20 * min), reviewWindow(bout(t0 + 10 * min), nowMs = 0))
        val odd = reviewWindow(bout(t0 + 7 * min + 1), nowMs = 0)
        assertEquals(30 * min, odd.last - odd.first)
        assertEquals(t0 - odd.first, odd.last - (t0 + 7 * min + 1) - 1)
    }

    @Test
    fun an_unfinished_bout_runs_to_now() {
        assertEquals(t0..(t0 + 40 * min), reviewWindow(bout(null), nowMs = t0 + 40 * min))
        val fresh = reviewWindow(bout(null), nowMs = t0 + 2 * min)
        assertEquals(30 * min, fresh.last - fresh.first)
    }

    /** A wall clock stepped back past the start must not invert the window. */
    @Test
    fun a_now_before_the_start_pads_around_the_start() {
        assertEquals((t0 - 15 * min)..(t0 + 15 * min), reviewWindow(bout(null), nowMs = t0 - 5 * min))
    }
}
