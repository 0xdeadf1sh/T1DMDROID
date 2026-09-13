package com.t1dm.feature.exercise

import com.t1dm.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackCursorTest {

    private val t0 = 1_700_000_000_000L
    private val limit = 60_000L

    private fun fix(offsetMs: Long, lat: Double, lon: Double) =
        TrackPoint(tsMs = t0 + offsetMs, lat = lat, lon = lon, accuracyM = 5f, speedMps = null)

    private val track = listOf(
        fix(0, 51.0, -0.1),
        fix(60_000, 51.001, -0.1),
        fix(120_000, 51.002, -0.1),
    )

    @Test
    fun a_cursor_between_two_fixes_interpolates() {
        val p = trackPositionAt(track, t0 + 30_000, limit)
        assertNotNull(p)
        assertEquals(51.0005, p!!.lat, 1e-9)
        assertEquals(-0.1, p.lon, 1e-9)
    }

    /** Every fix sits on one meridian, so only a diagonal leg separates real interpolation. */
    @Test
    fun longitude_interpolates_between_two_fixes() {
        val diagonal = listOf(fix(0, 51.0, -0.10), fix(60_000, 51.002, -0.06))
        val quarter = trackPositionAt(diagonal, t0 + 15_000, limit)
        assertNotNull(quarter)
        assertEquals(51.0005, quarter!!.lat, 1e-9)
        assertEquals(-0.09, quarter.lon, 1e-9)

        val threeQuarters = trackPositionAt(diagonal, t0 + 45_000, limit)!!
        assertEquals(51.0015, threeQuarters.lat, 1e-9)
        assertEquals(-0.07, threeQuarters.lon, 1e-9)
    }

    /** Standing still emits no fixes, so the two bracketing ones are the same place. */
    @Test
    fun a_stationary_stretch_still_resolves() {
        val still = listOf(fix(0, 51.0, -0.1), fix(240_000, 51.0, -0.1))
        val p = trackPositionAt(still, t0 + 120_000, limit)
        assertNotNull(p)
        assertEquals(51.0, p!!.lat, 1e-9)
    }

    @Test
    fun outside_the_track_span_it_withholds() {
        assertNull(trackPositionAt(track, t0 - 1, limit))
        assertNull(trackPositionAt(track, t0 + 120_001, limit))
    }

    /** A straight line across a 5 min 10 s gap would place the puck where it never was. */
    @Test
    fun a_gap_wider_than_the_limit_holds_the_earlier_fix() {
        val stop = listOf(fix(0, 51.0, -0.1), fix(310_000, 51.5, -0.2))
        for (offset in listOf(5_000L, 155_000L, 305_000L)) {
            val p = trackPositionAt(stop, t0 + offset, limit)
            assertNotNull("at $offset ms", p)
            assertEquals(51.0, p!!.lat, 1e-9)
            assertEquals(-0.1, p.lon, 1e-9)
        }
    }

    @Test
    fun a_cursor_on_a_fix_resolves_to_that_fix() {
        val sparse = listOf(fix(0, 51.0, -0.1), fix(3_600_000, 52.0, -0.2))
        assertEquals(51.0, trackPositionAt(sparse, t0, limit)!!.lat, 1e-9)
        assertEquals(52.0, trackPositionAt(sparse, t0 + 3_600_000, limit)!!.lat, 1e-9)
        assertEquals(51.002, trackPositionAt(track, t0 + 120_000, limit)!!.lat, 1e-9)
    }
}
