package com.t1dm.feature.exercise

import com.t1dm.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackCursorTest {

    private val t0 = 1_700_000_000_000L
    private val grid = 300_000L

    private fun fix(offsetMs: Long, lat: Double, lon: Double) =
        TrackPoint(tsMs = t0 + offsetMs, lat = lat, lon = lon, accuracyM = 5f, speedMps = null)

    private val track = listOf(
        fix(0, 51.0, -0.1),
        fix(60_000, 51.001, -0.1),
        fix(120_000, 51.002, -0.1),
    )

    @Test
    fun a_cursor_between_two_fixes_interpolates() {
        val p = trackPositionAt(track, t0 + 30_000, grid)
        assertNotNull(p)
        assertEquals(51.0005, p!!.lat, 1e-9)
        assertEquals(-0.1, p.lon, 1e-9)
    }

    /** Every fix sits on one meridian, so only a diagonal leg separates real interpolation. */
    @Test
    fun longitude_interpolates_between_two_fixes() {
        val diagonal = listOf(fix(0, 51.0, -0.10), fix(60_000, 51.002, -0.06))
        val quarter = trackPositionAt(diagonal, t0 + 15_000, grid)
        assertNotNull(quarter)
        assertEquals(51.0005, quarter!!.lat, 1e-9)
        assertEquals(-0.09, quarter.lon, 1e-9)

        val threeQuarters = trackPositionAt(diagonal, t0 + 45_000, grid)!!
        assertEquals(51.0015, threeQuarters.lat, 1e-9)
        assertEquals(-0.07, threeQuarters.lon, 1e-9)
    }

    /** Standing still emits no fixes, so the two bracketing ones are the same place. */
    @Test
    fun a_stationary_stretch_still_resolves() {
        val still = listOf(fix(0, 51.0, -0.1), fix(240_000, 51.0, -0.1))
        val p = trackPositionAt(still, t0 + 120_000, grid)
        assertNotNull(p)
        assertEquals(51.0, p!!.lat, 1e-9)
    }

    @Test
    fun outside_the_track_span_it_withholds() {
        assertNull(trackPositionAt(track, t0 - 1, grid))
        assertNull(trackPositionAt(track, t0 + 120_001, grid))
    }

    /** A 5 min 10 s stop is an ordinary bracket wider than one grid slot. */
    @Test
    fun a_cursor_seconds_from_a_recorded_fix_resolves_however_wide_the_bracket() {
        val stop = listOf(fix(0, 51.0, -0.1), fix(310_000, 51.5, -0.2))
        assertNotNull(
            "five seconds from the far fix",
            trackPositionAt(stop, t0 + 305_000, grid),
        )
        assertNotNull("five seconds from the near fix", trackPositionAt(stop, t0 + 5_000, grid))
        assertNull(trackPositionAt(stop, t0 + 155_000, grid))
    }

    @Test
    fun a_cursor_on_a_fix_resolves_to_that_fix() {
        val sparse = listOf(fix(0, 51.0, -0.1), fix(3_600_000, 52.0, -0.2))
        val p = trackPositionAt(sparse, t0, grid)
        assertNotNull(p)
        assertEquals(51.0, p!!.lat, 1e-9)
    }
}
