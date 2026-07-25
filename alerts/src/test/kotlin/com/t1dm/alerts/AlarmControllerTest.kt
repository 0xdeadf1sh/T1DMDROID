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
    fun `a persisting critical alarm re-emits on each tick without a second reAlert path`() = runTest {
        val notifier = FakeNotifier()
        var now = 0L
        val controller = AlarmController(AlarmEngine(config), notifier, config, clock = { now })
        val readings = MutableSharedFlow<com.t1dm.core.model.CgmReading>(extraBufferCapacity = 64)
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val job = controller.launchIn(this, readings, ticks)
        runCurrent()

        readings.emit(reading(50, rxWallMs = 0)) // urgent low, CRITICAL
        runCurrent()
        assertEquals(AlertBand.URGENT_LOW, notifier.lastEmit?.threshold?.band)
        val emitsAfterFire = notifier.emitCount

        // A later tick with the breach still standing re-presents it — the controller drives ONE emit
        // path (the notifier's own throttle bounds re-actuation), never a second reAlert that would
        // double-buzz the CRITICAL primary.
        now = 6 * MIN
        ticks.emit(Unit)
        runCurrent()
        assertTrue(notifier.emitCount > emitsAfterFire)
        assertEquals(0, notifier.reAlertCount)

        job.cancel()
    }

    @Test
    fun `a stable warning alarm re-emits on each tick`() = runTest {
        val notifier = FakeNotifier()
        var now = 0L
        val controller = AlarmController(AlarmEngine(config), notifier, config, clock = { now })
        val readings = MutableSharedFlow<com.t1dm.core.model.CgmReading>(extraBufferCapacity = 64)
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val job = controller.launchIn(this, readings, ticks)
        runCurrent()

        readings.emit(reading(200, rxWallMs = 0)) // HIGH band → WARNING threshold breach
        runCurrent()
        assertEquals(AlertBand.HIGH, notifier.lastEmit?.threshold?.band)
        val emitsAfterFire = notifier.emitCount

        // Regression (§3.6 C1): a stable WARNING breach reuses the same object, so the engine's
        // StateFlow deduplicates and the state collector never re-fires. onTick must still re-present it
        // so the notifier can re-apply its gate (an expired snooze re-surfaces) and re-announce on its
        // own throttle — otherwise a snoozed WARNING would be silenced permanently once snoozed.
        now = MIN
        ticks.emit(Unit)
        runCurrent()
        assertTrue(notifier.emitCount > emitsAfterFire)
        assertEquals(AlertBand.HIGH, notifier.lastEmit?.threshold?.band)
        assertEquals(0, notifier.reAlertCount)

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
