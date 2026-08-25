package com.t1dm.app.service

import com.t1dm.core.model.ActiveExercise
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A [android.app.Service] cannot be built on the host; each rule is a pure function or object. */
class ExerciseServiceTest {

    @Test
    fun `a bucket of 4-second fixes rebuilds the notification at most once a minute`() {
        val bucket = (0..75).map { i -> active(elapsedMs = i * 4_000L, distanceM = i * 5.6) }

        assertEquals(76, bucket.size)
        assertEquals(76, bucket.map(::progressText).distinct().size)
        // "0 min", then one key per minute of the bucket.
        assertEquals(7, bucket.map(::progressKey).distinct().size)
    }

    @Test
    fun `the key holds still across metres and moves on the minute`() {
        assertEquals(progressKey(active(30_000L, 100.0)), progressKey(active(59_999L, 180.0)))
        assertNotEquals(progressKey(active(59_999L, 180.0)), progressKey(active(60_000L, 180.0)))
    }

    @Test
    fun `the first metres and a new bout repaint at once`() {
        assertNotEquals(progressKey(active(1_000L, 0.0)), progressKey(active(1_000L, 12.0)))
        assertNotEquals(progressKey(active(1_000L, 12.0)), progressKey(active(1_000L, 12.0, id = 2L)))
    }

    @Test
    fun `a reason the line does not carry does not rebuild it`() {
        val a = active(30_000L, 100.0)
        assertEquals(progressKey(a), progressKey(a.copy(degraded = "Location off — no track")))
    }

    @Test
    fun `the line the user reads keeps its metres`() {
        assertEquals("3 min · 412 m", progressText(active(200_000L, 411.6)))
        assertEquals("0 min", progressText(active(0L, 0.0)))
    }

    @Test
    fun `only the first stop of a bout runs`() {
        val gate = BoutGate()
        gate.start()

        assertTrue(gate.beginStop())
        assertFalse("a second Stop would cut the first one's flush short", gate.beginStop())
        assertFalse(gate.beginStop())
    }

    @Test
    fun `a new bout re-arms the stop`() {
        val gate = BoutGate()
        gate.start()
        assertTrue(gate.beginStop())

        gate.start()
        assertTrue("the bout that replaced it can never be stopped otherwise", gate.beginStop())
    }

    @Test
    fun `the close-out is visible to a start that failed alongside it`() {
        val gate = BoutGate()
        gate.start()
        assertFalse(gate.stopping)

        gate.beginStop()
        assertTrue(gate.stopping)

        gate.start()
        assertFalse(gate.stopping)
    }

    @Test
    fun `a flush that outlived its bout is no longer the current one`() {
        val gate = BoutGate()
        val first = gate.start()
        gate.beginStop()
        assertTrue(gate.isCurrent(first))

        val second = gate.start()
        assertNotEquals(first, second)
        assertFalse("the old flush would tear down the bout that replaced it", gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    /** The service is up, with a notification, and no bout will ever close it. */
    @Test
    fun `a stop before any bout started still runs`() {
        val gate = BoutGate()

        assertTrue(gate.beginStop())
        assertTrue(gate.isCurrent(gate.generation))
    }

    @Test
    fun `a start that failed names the fault`() {
        assertEquals("Didn't start — disk full", startFailureText(IllegalStateException("disk full")))
        assertEquals("Didn't start — IllegalStateException", startFailureText(IllegalStateException()))
    }

    private fun active(elapsedMs: Long, distanceM: Double, id: Long = 1L) = ActiveExercise(
        session = ExerciseSession(
            id = id,
            startMs = 0L,
            endMs = null,
            tzOffsetMin = 0,
            kind = ExerciseKind.WALK,
            activeSec = 0,
            distanceM = null,
            kcal = null,
            interrupted = false,
        ),
        elapsedMs = elapsedMs,
        distanceM = distanceM,
        paceSecPerKm = null,
        kcal = null,
        lastFixAgeMs = null,
        degraded = null,
    )
}
