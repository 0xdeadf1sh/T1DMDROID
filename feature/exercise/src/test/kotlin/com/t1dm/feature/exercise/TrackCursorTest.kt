package com.t1dm.feature.exercise

import com.t1dm.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the slider says the patient was, and — more importantly — when it refuses to say. */
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

    /**
     * Longitude interpolates too, and against a number.
     *
     * Every fix in the shared track sits on one meridian, so a mapper that returned the bracketing
     * fix's longitude, or the wrong endpoint's, or latitude twice, would satisfy every other case
     * here. One diagonal leg is what separates a real interpolation from a coincidence.
     */
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

    /** Outside the track's own span there is nothing recorded, and the dot is withheld rather than
     *  pinned to an end — the review window reaches two hours past the bout. */
    @Test
    fun outside_the_track_span_it_withholds() {
        assertNull(trackPositionAt(track, t0 - 1, grid))
        assertNull(trackPositionAt(track, t0 + 120_001, grid))
    }

    /**
     * The bound is the distance to the NEARER fix, not the width of the bracket.
     *
     * This is the case the bracket-width rule got wrong: a 5 min 10 s stop is an ordinary bracket
     * wider than one grid slot, and a cursor five seconds from a recorded fix would have had the
     * dot vanish while the BG row beneath it printed a measurement for the same instant.
     */
    @Test
    fun a_cursor_seconds_from_a_recorded_fix_resolves_however_wide_the_bracket() {
        val stop = listOf(fix(0, 51.0, -0.1), fix(310_000, 51.5, -0.2))
        assertNotNull(
            "five seconds from the far fix",
            trackPositionAt(stop, t0 + 305_000, grid),
        )
        assertNotNull("five seconds from the near fix", trackPositionAt(stop, t0 + 5_000, grid))
        // …and the middle of a long rest, where nothing was recorded near, still withholds.
        assertNull(trackPositionAt(stop, t0 + 155_000, grid))
    }

    /** A cursor sitting exactly on a fix resolves to it, whatever its neighbour is doing. */
    @Test
    fun a_cursor_on_a_fix_resolves_to_that_fix() {
        val sparse = listOf(fix(0, 51.0, -0.1), fix(3_600_000, 52.0, -0.2))
        val p = trackPositionAt(sparse, t0, grid)
        assertNotNull(p)
        assertEquals(51.0, p!!.lat, 1e-9)
    }
}
