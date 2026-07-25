package com.t1dm.alerts

import com.t1dm.core.model.CgmReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Combines the four deterministic evaluators ([ThresholdAlarm], [LossOfSignalAlarm], [WeakSignalAlarm],
 * [OverTemperatureAlarm]) and publishes the merged [AlarmState] as a Flow (SPEC.private.md §3.6-A).
 * This is the testable heart of the
 * model-free path: it has no `:inference` dependency, no Android dependency, and reads no clock —
 * time enters only through [onReading]/[onTick].
 *
 * Mutation is synchronized so the CGM-reading collector and the wall-clock ticker may drive it from
 * different coroutines without racing the evaluators' internal state.
 */
class AlarmEngine(config: AlarmConfig = AlarmConfig.DEFAULT) {

    private val threshold = ThresholdAlarm(config.thresholds)
    private val lossOfSignal = LossOfSignalAlarm(config)
    private val weakSignal = WeakSignalAlarm(config)
    private val overTemp = OverTemperatureAlarm(config)

    private val _state = MutableStateFlow(AlarmState.CLEAR)
    val state: StateFlow<AlarmState> = _state.asStateFlow()

    /** Feed one grid-stamped reading. [nowMs] defaults to the reading's own receive time. */
    @Synchronized
    fun onReading(reading: CgmReading, nowMs: Long = reading.rxWallMs) {
        threshold.onReading(reading)
        lossOfSignal.onReading(reading)
        lossOfSignal.evaluate(nowMs)
        weakSignal.onReading(reading)
        weakSignal.evaluate(nowMs)
        publish()
    }

    /**
     * Live-apply a new [AlarmConfig] to the EXISTING sub-evaluators (a Settings edit reaching the
     * already-running engine, SPEC.private.md §3.6-A). Swaps thresholds / loss windows / over-temp
     * params only; it deliberately does NOT clear any active breach or latch and does NOT re-publish.
     *
     * SAFETY: a raised threshold must NOT instantly clear a genuine active low. The current breach is
     * retained and the NEXT eligible MEASURED reading re-classifies it against the new thresholds — the
     * engine never silences a standing excursion on a config edit alone. Synchronized with
     * [onReading]/[onTick] so it cannot interleave an evaluator's internal state.
     */
    @Synchronized
    fun updateConfig(config: AlarmConfig) {
        threshold.updateThresholds(config.thresholds)
        lossOfSignal.updateConfig(config)
        weakSignal.updateConfig(config)
        overTemp.updateConfig(config)
    }

    /** Wall-clock tick: re-evaluates the time-dependent loss-of-signal window and, given the current
     *  battery-sensor [tempC] (null when unreadable), the latching over-temperature alarm. */
    @Synchronized
    fun onTick(nowMs: Long, tempC: Double? = null) {
        lossOfSignal.evaluate(nowMs)
        weakSignal.evaluate(nowMs)
        overTemp.evaluate(tempC, nowMs)
        publish()
    }

    private fun publish() {
        _state.value = AlarmState(
            threshold = threshold.breach,
            signalLoss = lossOfSignal.loss,
            overTemperature = overTemp.state,
            weakSignal = weakSignal.weak,
        )
    }
}
