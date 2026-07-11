package com.t1dm.alerts

import com.t1dm.core.model.AlertBand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmControllerTest {

    private val config = AlarmConfig.DEFAULT

    @Test
    fun `readings drive emit then a measured in-range clears`() = runTest {
        val notifier = FakeNotifier()
        var now = 0L
        val controller = AlarmController(AlarmEngine(config), notifier, config, clock = { now })
        val readings = MutableSharedFlow<com.t1dm.core.model.CgmReading>(extraBufferCapacity = 64)
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val job = controller.launchIn(this, readings, ticks)
        runCurrent()

        readings.emit(reading(50, rxWallMs = 0)) // urgent low
        runCurrent()
        assertEquals(AlertBand.URGENT_LOW, notifier.lastEmit?.threshold?.band)
        assertEquals(1, notifier.emitCount)

        val clearsBefore = notifier.clearCount
        readings.emit(reading(120, rxWallMs = 5 * MIN)) // measured in-range clears
        runCurrent()
        assertTrue(notifier.clearCount > clearsBefore)

        job.cancel()
    }

    @Test
    fun `loss-of-signal fires through the ticker`() = runTest {
        val notifier = FakeNotifier()
        var now = 0L
        val controller = AlarmController(AlarmEngine(config), notifier, config, clock = { now })
        val readings = MutableSharedFlow<com.t1dm.core.model.CgmReading>(extraBufferCapacity = 64)
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val job = controller.launchIn(this, readings, ticks)
        runCurrent()

        readings.emit(reading(120, rxWallMs = 0))
        runCurrent()

        now = 20 * MIN
        ticks.emit(Unit)
        runCurrent()
        assertNotNull(notifier.lastEmit?.signalLoss)

        job.cancel()
    }

    @Test
    fun `a persisting critical alarm re-alerts on the repeat cadence`() = runTest {
        val notifier = FakeNotifier()
        var now = 0L
        val controller = AlarmController(AlarmEngine(config), notifier, config, clock = { now })
        val readings = MutableSharedFlow<com.t1dm.core.model.CgmReading>(extraBufferCapacity = 64)
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val job = controller.launchIn(this, readings, ticks)
        runCurrent()

        readings.emit(reading(50, rxWallMs = 0)) // urgent low, CRITICAL
        runCurrent()
        assertEquals(0, notifier.reAlertCount)

        now = 6 * MIN // beyond the 5-min repeat cadence
        ticks.emit(Unit)
        runCurrent()
        assertTrue(notifier.reAlertCount >= 1)

        job.cancel()
    }

    @Test
    fun `temperature closure drives over-temp fire and clear through the ticker`() = runTest {
        val notifier = FakeNotifier()
        var now = 0L
        var temp: Double? = 30.0
        val controller = AlarmController(
            AlarmEngine(config), notifier, config, clock = { now }, temperatureC = { temp },
        )
        val readings = MutableSharedFlow<com.t1dm.core.model.CgmReading>(extraBufferCapacity = 64)
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val job = controller.launchIn(this, readings, ticks)
        runCurrent()

        temp = 45.0
        now = MIN
        ticks.emit(Unit)
        runCurrent()
        assertNotNull(notifier.lastEmit?.overTemperature)

        val clearsBefore = notifier.clearCount
        temp = 40.0
        now = 2 * MIN
        ticks.emit(Unit)
        runCurrent()
        assertNull(controller.state.value.overTemperature)
        assertTrue(notifier.clearCount > clearsBefore)

        job.cancel()
    }

    @Test
    fun `no active alarm keeps the sink clear`() = runTest {
        val notifier = FakeNotifier()
        var now = 0L
        val controller = AlarmController(AlarmEngine(config), notifier, config, clock = { now })
        val readings = MutableSharedFlow<com.t1dm.core.model.CgmReading>(extraBufferCapacity = 64)
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val job = controller.launchIn(this, readings, ticks)
        runCurrent()

        readings.emit(reading(120, rxWallMs = 0)) // in range
        runCurrent()
        assertNull(notifier.lastEmit)
        assertEquals(0, notifier.emitCount)

        job.cancel()
    }
}
