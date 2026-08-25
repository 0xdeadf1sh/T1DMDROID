package com.t1dm.data.exercise

import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.TrackPoint
import com.t1dm.data.db.ExerciseFixEntity
import com.t1dm.data.db.ExerciseSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseMappingTest {

    private fun row(kind: String) = ExerciseSessionEntity(
        id = 4L, clientId = "c", startMs = 1_000L, endMs = 2_000L, tzOffsetMin = 60,
        kind = kind, activeSec = 900, distanceM = 1_500.0, kcal = 120,
        interrupted = false, note = null, updatedAt = 2_000L,
    )

    @Test
    fun `every kind this build knows survives the round trip`() {
        for (k in ExerciseKind.values()) assertEquals(k, row(k.name).toModel().kind)
    }

    @Test
    fun `a kind a later build recorded reads as OTHER instead of throwing`() {
        // The column is unconverted TEXT so a newer build's bout still opens here.
        assertEquals(ExerciseKind.OTHER, row("SWIM").toModel().kind)
        assertEquals(ExerciseKind.OTHER, row("").toModel().kind)
        assertEquals(ExerciseKind.OTHER, row("walk").toModel().kind)
    }

    @Test
    fun `a bout keeps the figures it was stored with`() {
        val m = row("WALK").toModel()
        assertEquals(4L, m.id)
        assertEquals(900, m.activeSec)
        assertEquals(1_500.0, m.distanceM!!, 0.0)
        assertEquals(120, m.kcal)
    }

    @Test
    fun `a bout with no energy figure keeps none`() {
        val m = row("WALK").copy(distanceM = null, kcal = null).toModel()
        assertNull(m.distanceM)
        assertNull(m.kcal)
    }

    @Test
    fun `a fix round-trips through the model and back`() {
        val e = ExerciseFixEntity(
            id = 9L, sessionId = 4L, tsMs = 1_500L, lat = 41.015137, lon = 28.979530,
            accuracyM = 6.5f, speedMps = 3.1f,
        )
        val back = e.toModel().toEntity(4L)
        assertEquals(e.copy(id = 0L), back)
    }

    @Test
    fun `a fix with no reported speed keeps none`() {
        val p = TrackPoint(tsMs = 1L, lat = 1.0, lon = 2.0, accuracyM = 5f, speedMps = null)
        assertNull(p.toEntity(1L).speedMps)
    }

    @Test
    fun `a bout with a track closes at its newest fix`() {
        assertEquals(9_000L, interruptedEndMs(startMs = 1_000L, newestFixTsMs = 9_000L))
    }

    @Test
    fun `a bout with no track closes at its own start`() {
        // Nothing was recorded past the start, so nothing past it may be claimed.
        assertEquals(1_000L, interruptedEndMs(startMs = 1_000L, newestFixTsMs = null))
    }

    @Test
    fun `a fix older than the start cannot end a bout before it began`() {
        assertEquals(1_000L, interruptedEndMs(startMs = 1_000L, newestFixTsMs = 500L))
    }
}
