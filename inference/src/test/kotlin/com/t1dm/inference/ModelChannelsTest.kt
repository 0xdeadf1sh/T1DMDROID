package com.t1dm.inference

import com.t1dm.core.model.ChannelStat
import com.t1dm.core.model.DoseChannel
import com.t1dm.core.model.DoseTransform
import com.t1dm.core.model.InputLayout
import com.t1dm.core.model.PointDose
import com.t1dm.core.model.PointKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ModelChannelsTest {

    private val names = listOf(
        "carb_g", "carb_gi", "bolus_u", "bolus_peak_min", "bolus_dur_h",
        "basal_u", "basal_peak_min", "basal_dur_h",
    )
    private val events = fixtureDescriptor().copy(
        doses = names.map { DoseChannel(it, ChannelStat(0.0, 1.0), DoseTransform.LOG1P, 0.0) },
        inputLayout = InputLayout.EVENTS,
        nInputFeatures = 10,
    )
    private val probe = ProbeDose(doubleArrayOf(0.25, 0.5, 0.25), PointDose(PointKind.BOLUS, 1.0, 0.0, 60.0, 4.0))

    @Test
    fun `the probe bolus merges dose-weighted into a bolus already at the zone's first step`() {
        val ev = List(8) { DoubleArray(4) }
        ev[2][0] = 1.0
        ev[3][0] = 40.0
        ev[4][0] = 3.0
        val probed = ModelChannels(DoubleArray(4), DoubleArray(4), ev).plusProbe(probe, events)

        val got = probed.series(events)
        assertEquals(2.0, got[2][0], 0.0)
        assertEquals(50.0, got[3][0], 1e-12)
        assertEquals(3.5, got[4][0], 1e-12)
        assertEquals("the input is not mutated", 1.0, ev[2][0], 0.0)
        assertArrayEquals(doubleArrayOf(0.25, 0.5, 0.25, 0.0), probed.insulin, 0.0)
    }

    @Test
    fun `a curves model takes the probe as action and leaves the events alone`() {
        val ev = List(8) { DoubleArray(4) }
        val probed = ModelChannels(DoubleArray(4), DoubleArray(4), ev).plusProbe(probe, fixtureDescriptor())

        assertSame(ev, probed.events)
        assertArrayEquals(doubleArrayOf(0.25, 0.5, 0.25, 0.0), probed.series(fixtureDescriptor())[1], 0.0)
    }

    @Test
    fun `an events model reads no dose where no events were built`() {
        val got = ModelChannels.zero(6).series(events)
        assertEquals(8, got.size)
        assertEquals(0.0, got.sumOf { it.sum() }, 0.0)
    }
}
