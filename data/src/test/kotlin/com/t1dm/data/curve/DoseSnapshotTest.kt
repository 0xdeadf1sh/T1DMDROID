package com.t1dm.data.curve

import com.t1dm.core.model.BasalDoseSpec
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DoseSnapshotTest {

    @Test
    fun `a row logged after the instant is absent, one logged at it is present`() = runTest {
        val early = logged(ts = 0, loggedAt = 10, grams = 30.0)
        val late = logged(ts = 0, loggedAt = 20, grams = 60.0)
        val snapshot = DoseSnapshot(listOf(early, late), emptyList(), emptyList(), null)

        assertEquals(listOf(30.0), snapshot.at(10).carbEvents(0, 100).map { it.total })
        assertEquals(listOf(30.0, 60.0), snapshot.at(20).carbEvents(0, 100).map { it.total })
    }

    @Test
    fun `a row from before the logged-at column is known at any epoch instant`() = runTest {
        val snapshot = DoseSnapshot(emptyList(), listOf(logged(ts = 50, loggedAt = 0, grams = 2.0)), emptyList(), null)

        assertEquals(1, snapshot.at(1).insulinEvents(0, 100).size)
    }

    @Test
    fun `the window is inclusive at both ends, as the DAO's BETWEEN`() = runTest {
        val rows = listOf(logged(-1, 0, 1.0), logged(0, 0, 2.0), logged(100, 0, 3.0), logged(101, 0, 4.0))
        val snapshot = DoseSnapshot(emptyList(), emptyList(), rows, null)

        assertEquals(listOf(2.0, 3.0), snapshot.at(0).basalInjectionEvents(0, 100).map { it.total })
    }

    @Test
    fun `the basal schedule passes through`() = runTest {
        val schedule = BasalSchedule(tzOffsetMin = 0, doses = listOf(BasalDoseSpec(480, 10.0, 1440.0, 0.3, 0.07)))
        assertSame(schedule, DoseSnapshot(emptyList(), emptyList(), emptyList(), schedule).at(0).activeBasalSchedule())
    }

    private fun logged(ts: Long, loggedAt: Long, grams: Double) =
        DoseSnapshot.Logged(ts, loggedAt, CurveEvent(ts, CurveEngine.STEP_MS, CurveKind.CARB, grams, listOf(grams)))
}
