package com.t1dm.alerts

import com.t1dm.core.model.CgmReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

class AlarmController(
    private val engine: AlarmEngine,
    private val notifier: AlarmNotifier,
    initialConfig: AlarmConfig = AlarmConfig.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Null when unreadable; the evaluator treats that as inert. */
    private val temperatureC: () -> Double? = { null },
) {
    val state: StateFlow<AlarmState> get() = engine.state

    @Volatile
    private var config: AlarmConfig = initialConfig

    /** Presentation timing only; never changes when the engine fires. */
    fun updateConfig(newConfig: AlarmConfig) {
        config = newConfig
    }

    fun launchIn(
        scope: CoroutineScope,
        readings: Flow<CgmReading>,
        ticks: Flow<Unit> = periodic(config.tickIntervalMs),
    ): Job = scope.launch {
        launch { readings.collect { engine.onReading(it, clock()) } }
        launch { ticks.collect { onTick() } }
        launch {
            engine.state.collect { state ->
                if (state.isActive) notifier.emit(state) else notifier.clear()
            }
        }
    }

    private fun onTick() {
        engine.onTick(clock(), temperatureC())
        // Re-present each tick: an expired snooze re-surfaces (§3.6 C1), and a persisting alarm
        // re-announces despite StateFlow deduplicating its equal state. The notifier throttles the
        // sound and vibration.
        val current = engine.state.value
        if (current.isActive) notifier.emit(current)
    }

    companion object {
        fun periodic(intervalMs: Long): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(intervalMs)
            }
        }
    }
}
