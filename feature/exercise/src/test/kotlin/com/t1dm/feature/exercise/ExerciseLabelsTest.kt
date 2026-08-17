package com.t1dm.feature.exercise

import com.t1dm.core.model.ActiveExercise
import com.t1dm.core.model.EXERCISE_MAX_BOUT_MS
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The panel's pure formatting, which the foreground-service notification also renders through. A
 * duration is the figure a bout is read by, and the failure mode is quiet: an hour rendered `60:00`
 * is legible, plausible and wrong, and a stopwatch that drops its leading zero (`5:7`) reads as
 * seven minutes.
 */
class ExerciseLabelsTest {

    @Test
    fun `below an hour it reads m colon ss`() {
        assertEquals("0:00", durationLabel(0))
        assertEquals("0:07", durationLabel(7))
        assertEquals("5:07", durationLabel(307))
        assertEquals("59:59", durationLabel(3599))
    }

    @Test
    fun `an hour promotes the field rather than overflowing the minutes`() {
        assertEquals("1:00:00", durationLabel(3600))
        assertEquals("2:03:04", durationLabel(2 * 3600 + 3 * 60 + 4))
    }

    @Test
    fun `a negative count is floored, never rendered with a sign`() {
        assertEquals("0:00", durationLabel(-1))
    }

    @Test
    fun `metres below a kilometre stay metres`() {
        assertEquals("1 m", distanceLabel(1.4))
        assertEquals("840 m", distanceLabel(839.6))
        assertEquals("999 m", distanceLabel(999.4))
    }

    @Test
    fun `a kilometre and past it reads two decimals, zero-padded`() {
        assertEquals("1.00 km", distanceLabel(1_000.0))
        assertEquals("2.14 km", distanceLabel(2_140.0))
        assertEquals("2.05 km", distanceLabel(2_050.0))
        assertEquals("12.35 km", distanceLabel(12_345.0))
    }

    @Test
    fun `no measured distance is no label, never a zero`() {
        // A bout indoors or with location denied did not travel 0 m — it did not measure at all.
        assertNull(distanceLabel(null))
        assertNull(distanceLabel(0.0))
        assertNull(distanceLabel(Double.NaN))
    }

    @Test
    fun `pace reads minutes per kilometre with padded seconds`() {
        assertEquals("5:31 /km", paceLabel(331.0))
        assertEquals("5:00 /km", paceLabel(300.0))
        assertEquals("10:05 /km", paceLabel(605.0))
    }

    @Test
    fun `no pace is no label`() {
        // The recorder withholds a pace below its own distance floor rather than dividing the
        // receiver's scatter by a small number of seconds; the label must not invent one back.
        assertNull(paceLabel(null))
        assertNull(paceLabel(0.0))
        assertNull(paceLabel(Double.NaN))
    }

    @Test
    fun `the live line omits what was not measured rather than showing it empty`() {
        val bout = ExerciseSession(
            id = 1, startMs = 0L, endMs = null, tzOffsetMin = 0,
            kind = ExerciseKind.WALK, activeSec = 0, distanceM = null, kcal = null,
            interrupted = false,
        )
        val bare = ActiveExercise(bout, 1_084_000L, 0.0, null, null, null, null)
        assertEquals("18:04", liveLine(bare))
        val full = bare.copy(distanceM = 2_140.0, paceSecPerKm = 331.0, kcal = 212)
        assertEquals("18:04 · 2.14 km · 5:31 /km · 212 kcal", liveLine(full))
    }

    @Test
    fun `a bout the user did not end says which way it was ended`() {
        val bout = ExerciseSession(
            id = 1, startMs = 1_000L, endMs = 1_000L + 600_000L, tzOffsetMin = 0,
            kind = ExerciseKind.WALK, activeSec = 600, distanceM = null, kcal = null,
            interrupted = false,
        )
        assertNull(interruptedNote(bout))
        // Cut at its last recorded fix after the process died.
        assertEquals("Ended early — app stopped", interruptedNote(bout.copy(interrupted = true)))
        // Ran the whole limit, so the limit is what to say — and the figure comes from the constant
        // the service enforces, not from a number typed into the string.
        val capped = bout.copy(endMs = bout.startMs + EXERCISE_MAX_BOUT_MS, interrupted = true)
        assertEquals("Ended at the 12 h limit", interruptedNote(capped))
    }

    @Test
    fun `a withheld kcal names its cause, and only a cause the user can act on`() {
        val bout = ExerciseSession(
            id = 1, startMs = 0L, endMs = null, tzOffsetMin = 0,
            kind = ExerciseKind.WALK, activeSec = 600, distanceM = 800.0, kcal = null,
            interrupted = false,
        )
        val walk = ActiveExercise(bout, 600_000L, 800.0, null, null, null, null)
        assertEquals("kcal needs body mass", kcalNote(walk, null))
        // The equations describe two gaits; OTHER is the label for everything they do not, and the
        // body mass would not rescue it — so that is what the line has to say.
        val other = walk.copy(session = bout.copy(kind = ExerciseKind.OTHER))
        assertEquals("kcal needs walk or run", kcalNote(other, 70.0))
        assertEquals("kcal needs walk or run", kcalNote(other, null))
        // A figure that exists needs no explanation, and neither does a walk that has a body mass and
        // has simply not covered any ground yet.
        assertNull(kcalNote(walk.copy(kcal = 212), null))
        assertNull(kcalNote(walk.copy(distanceM = 0.0), 70.0))
    }

    @Test
    fun `the summary line is the same shape for a bout already closed`() {
        val bout = ExerciseSession(
            id = 1, startMs = 0L, endMs = 1_084_000L, tzOffsetMin = 0,
            kind = ExerciseKind.RUN, activeSec = 1_084, distanceM = 2_140.0, kcal = 212,
            interrupted = false,
        )
        assertEquals("18:04 · 2.14 km · 212 kcal", summaryLine(bout))
        assertEquals("18:04", summaryLine(bout.copy(distanceM = null, kcal = null)))
    }
}
