package com.t1dm.alerts

import com.t1dm.core.model.CgmReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AlarmEngine(config: AlarmConfig = AlarmConfig.DEFAULT) {

    private val threshold = ThresholdAlarm(config.thresholds)
    private val lossOfSignal = LossOfSignalAlarm(config)
    private val weakSignal = WeakSignalAlarm(config)
    private val overTemp = OverTemperatureAlarm(config)

    private val _state = MutableStateFlow(AlarmState.CLEAR)
    val state: StateFlow<AlarmState> = _state.asStateFlow()

    @Synchronized
    fun onReading(reading: CgmReading, nowMs: Long = reading.rxWallMs) {
        threshold.onReading(reading)
        lossOfSignal.onReading(reading)
        lossOfSignal.evaluate(nowMs)
        weakSignal.onReading(reading)
        weakSignal.evaluate(nowMs)
        publish()
    }

    /** Forgets link state only. [LossOfSignalAlarm] restarts its clock rather than forgetting, so a
     *  silent new sensor still trips. [ThresholdAlarm] is deliberately not reset: a standing low is a
     *  fact about the patient, and clearing it here would go quiet mid-excursion. */
    @Synchronized
    fun onSourceChanged(nowMs: Long) {
        lossOfSignal.onSourceChanged(nowMs)
        weakSignal.onSourceChanged()
        publish()
    }

    /** Swaps the sub-evaluators' params. Deliberately clears no active breach and does not publish:
     *  a raised threshold must not silence a standing low; the next MEASURED reading re-classifies. */
    @Synchronized
    fun updateConfig(config: AlarmConfig) {
        threshold.updateThresholds(config.thresholds)
        lossOfSignal.updateConfig(config)
        weakSignal.updateConfig(config)
        overTemp.updateConfig(config)
    }

    /** [tempC] is the battery sensor, null when unreadable. */
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
