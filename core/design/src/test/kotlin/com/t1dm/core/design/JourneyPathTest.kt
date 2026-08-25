package com.t1dm.core.design

import com.t1dm.core.model.DkaTimeline
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyPathTest {

    private val default = DkaTimeline.DEFAULT // 2 + 29 + 59 h = 90 h
    private val hour = 3_600_000L
    private val anchor = 1_000_000_000_000L

    private fun at(hours: Double, tl: DkaTimeline = default) =
        journeyProgress(anchor + (hours * hour).toLong(), anchor, tl)

    @Test
    fun `landmarks are evenly spaced regardless of the hours behind them`() {
        assertEquals(1f / 3f, JourneyMarks.EVEN.dka, 1e-6f)
        assertEquals(2f / 3f, JourneyMarks.EVEN.coma, 1e-6f)
        assertEquals(1f, JourneyMarks.EVEN.death, 1e-6f)
    }

    @Test
    fun `the arrow meets each landmark at the instant it is projected`() {
        assertEquals(0f, at(0.0), 1e-6f)
        assertEquals(JourneyMarks.EVEN.dka, at(2.0), 1e-6f)
        assertEquals(JourneyMarks.EVEN.coma, at(31.0), 1e-6f)
        assertEquals(JourneyMarks.EVEN.death, at(90.0), 1e-6f)
    }

    @Test
    fun `within a leg the arrow moves at that leg's own constant rate`() {
        assertEquals(1f / 6f, at(1.0), 1e-6f) // half of the 2 h run-up
        assertEquals(0.5f, at(16.5), 1e-6f) // half of the 29 h middle leg
        assertEquals(5f / 6f, at(60.5), 1e-6f) // half of the 59 h final leg
    }

    @Test
    fun `a legs hours change its speed, never where its landmark sits`() {
        val stretched = DkaTimeline(1.0, 98.0, 1.0)
        assertEquals(JourneyMarks.EVEN.dka, at(1.0, stretched), 1e-6f)
        assertEquals(JourneyMarks.EVEN.coma, at(99.0, stretched), 1e-6f)
        assertEquals(0.5f, at(50.0, stretched), 1e-6f) // half of the middle leg
    }

    @Test
    fun `a zero-hour leg is crossed instantly, awarding its road but consuming no time`() {
        val noRunUp = DkaTimeline(0.0, 5.0, 5.0)
        assertEquals(JourneyMarks.EVEN.dka, at(0.001, noRunUp), 1e-3f)
        assertEquals(JourneyMarks.EVEN.coma, at(5.0, noRunUp), 1e-6f)
        assertEquals(1f, at(10.0, noRunUp), 1e-6f)

        val instantDeath = DkaTimeline(5.0, 5.0, 0.0)
        assertEquals(JourneyMarks.EVEN.coma, at(10.0, instantDeath), 1e-6f)
        assertEquals(1f, at(10.001, instantDeath), 1e-3f)
    }

    @Test
    fun `negative offsets are floored rather than trusted`() {
        val negative = DkaTimeline(-4.0, 3.0, 1.0)
        assertEquals(JourneyMarks.EVEN.dka, at(0.001, negative), 1e-3f)
        assertEquals(JourneyMarks.EVEN.coma, at(3.0, negative), 1e-6f)
    }

    @Test
    fun `an anchor still ahead pins the arrow at the departure point`() {
        assertEquals(0f, journeyProgress(anchor - hour, anchor, default), 1e-6f)
        assertEquals(0f, journeyProgress(anchor - 10_000 * hour, anchor, default), 1e-6f)
    }

    @Test
    fun `an anchor long past pins the arrow at the grave`() {
        assertEquals(1f, journeyProgress(anchor + 91 * hour, anchor, default), 1e-6f)
        assertEquals(1f, journeyProgress(anchor + 10_000 * hour, anchor, default), 1e-6f)
    }

    @Test
    fun `a zero-length timeline is over the instant it begins`() {
        val none = DkaTimeline(0.0, 0.0, 0.0)
        assertEquals(0f, journeyProgress(anchor - 1, anchor, none), 1e-6f)
        assertEquals(1f, journeyProgress(anchor, anchor, none), 1e-6f)
        assertEquals(1f, journeyProgress(anchor + hour, anchor, none), 1e-6f)
    }
}
