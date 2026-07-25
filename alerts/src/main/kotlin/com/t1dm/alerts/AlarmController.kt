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
 * state changes are pushed to an [AlarmNotifier]. Each tick ALSO re-presents the current active state
 * to the notifier, so an expired snooze re-surfaces (§3.6 C1) and a persisting alarm re-announces even
 * when its structurally-equal [AlarmState] was deduplicated by the StateFlow (so the state collector
 * never re-fired). The notifier's own (episode, minActuationInterval) throttle bounds how often that
 * re-actuates sound + vibration — presentation only; nothing here changes WHEN the pure engine fires.
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

    /** Snapshot of the current tunables, live-applied via [updateConfig]; used here for the default
     *  ticker cadence (the engine's thresholds/windows go live via [AlarmEngine.updateConfig]). */
    @Volatile
    private var config: AlarmConfig = initialConfig

    /** Live-apply a new cadence/tunables to the running controller. Additive to
     *  [AlarmEngine.updateConfig]; presentation timing only, never changes WHEN the engine fires. */
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
        // Re-present the current active state each tick (presentation only — this never changes WHEN
        // the pure engine fires, §3.6-A). The notifier re-applies its visibleAfterGates gate, so an
        // expired snooze re-surfaces (§3.6 C1); and a persisting alarm re-announces even though its
        // structurally-equal AlarmState was deduplicated by the StateFlow (so the state collector never
        // re-fired). The notifier throttles sound + vibration by (episode, minActuationInterval), so
        // re-emitting every tick actuates AT MOST once per interval — and the app-level CRITICAL
        // exact-alarm repeat, which also calls emit, is deduped by that same throttle.
        val current = engine.state.value
        if (current.isActive) notifier.emit(current)
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
