package com.t1dm.alerts

import com.t1dm.core.model.CgmReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Wires the deterministic alarm path together for the foreground service (SPEC.private.md §2.3,
 * §3.6-A): a stream of grid-stamped readings and a wall-clock ticker drive the [AlarmEngine], whose
 * state changes are pushed to an [AlarmNotifier]. A persisting CRITICAL alarm is re-vibrated on the
 * repeat cadence even when its state has not changed.
 *
 * The engine's [state] is re-exposed for the UI to observe. Nothing here touches `:inference`.
 *
 * The clock and the tick source are injectable so the whole controller is deterministic under test.
 */
class AlarmController(
    private val engine: AlarmEngine,
    private val notifier: AlarmNotifier,
    initialConfig: AlarmConfig = AlarmConfig.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The current battery-sensor °C (locked decision D1), read live on each tick so the
     *  over-temperature alarm tracks the device without a settings/service dependency here. Null when
     *  unreadable — the evaluator treats that as inert. Defaults to never-hot for tests/headless. */
    private val temperatureC: () -> Double? = { null },
) {
    val state: StateFlow<AlarmState> get() = engine.state

    /** Read live in [onTick] so a Settings edit to the repeat cadence reaches a currently-firing alarm's
     *  in-process re-alert without a restart (the engine's thresholds go live via [AlarmEngine.updateConfig]). */
    @Volatile
    private var config: AlarmConfig = initialConfig

    /** Live-apply a new cadence/tunables to the running controller. Additive to
     *  [AlarmEngine.updateConfig]; presentation timing only, never changes WHEN the engine fires. */
    fun updateConfig(newConfig: AlarmConfig) {
        config = newConfig
    }

    @Volatile
    private var lastReAlertMs = Long.MIN_VALUE

    fun launchIn(
        scope: CoroutineScope,
        readings: Flow<CgmReading>,
        ticks: Flow<Unit> = periodic(config.tickIntervalMs),
    ): Job = scope.launch {
        launch { readings.collect { engine.onReading(it, clock()) } }
        launch { ticks.collect { onTick() } }
        launch {
            engine.state.collect { state ->
                if (state.isActive) {
                    notifier.emit(state)
                    lastReAlertMs = clock()
                } else {
                    notifier.clear()
                }
            }
        }
    }

    private fun onTick() {
        engine.onTick(clock(), temperatureC())
        val current = engine.state.value
        val primary = current.primary ?: return
        val now = clock()
        if (primary.severity == AlarmSeverity.CRITICAL &&
            now - lastReAlertMs >= config.repeatCadenceMin * 60_000L
        ) {
            notifier.reAlert(current)
            lastReAlertMs = now
        }
    }

    companion object {
        /** A never-ending tick source; the FGS's real driver in production. */
        fun periodic(intervalMs: Long): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(intervalMs)
            }
        }
    }
}
