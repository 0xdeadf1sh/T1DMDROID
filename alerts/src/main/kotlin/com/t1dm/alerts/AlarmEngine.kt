package com.t1dm.alerts

import com.t1dm.core.model.CgmReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Combines the two deterministic evaluators ([ThresholdAlarm], [LossOfSignalAlarm]) and publishes
 * the merged [AlarmState] as a Flow (PLAN.private.md §3.6-A). This is the testable heart of the
 * model-free path: it has no `:inference` dependency, no Android dependency, and reads no clock —
 * time enters only through [onReading]/[onTick].
 *
 * Mutation is synchronized so the CGM-reading collector and the wall-clock ticker may drive it from
 * different coroutines without racing the evaluators' internal state.
 */
class AlarmEngine(config: AlarmConfig = AlarmConfig.DEFAULT) {

    private val threshold = ThresholdAlarm(config.thresholds)
    private val lossOfSignal = LossOfSignalAlarm(config)

    private val _state = MutableStateFlow(AlarmState.CLEAR)
    val state: StateFlow<AlarmState> = _state.asStateFlow()

    /** Feed one grid-stamped reading. [nowMs] defaults to the reading's own receive time. */
    @Synchronized
    fun onReading(reading: CgmReading, nowMs: Long = reading.rxWallMs) {
        threshold.onReading(reading)
        lossOfSignal.onReading(reading)
        lossOfSignal.evaluate(nowMs)
        publish()
    }

    /** Wall-clock tick: only the loss-of-signal window is time-dependent. */
    @Synchronized
    fun onTick(nowMs: Long) {
        lossOfSignal.evaluate(nowMs)
        publish()
    }

    private fun publish() {
        _state.value = AlarmState(threshold.breach, lossOfSignal.loss)
    }
}
