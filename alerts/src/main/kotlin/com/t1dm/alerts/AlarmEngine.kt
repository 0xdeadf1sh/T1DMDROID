package com.t1dm.alerts

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingProvenance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AlarmEngine(private var config: AlarmConfig = AlarmConfig.DEFAULT) {

    private val threshold = ThresholdAlarm(config)
    private val lossOfSignal = LossOfSignalAlarm(config)
    private val weakSignal = WeakSignalAlarm(config)
    private val overTemp = OverTemperatureAlarm(config)

    /** rxWallMs of the newest MEASURED reading taken; anything older is replayed history. */
    private var newestMeasuredMs: Long? = null

    private val _state = MutableStateFlow(AlarmState.CLEAR)
    val state: StateFlow<AlarmState> = _state.asStateFlow()

    @Synchronized
    fun onReading(reading: CgmReading, nowMs: Long = reading.rxWallMs) {
        if (take(reading, nowMs)) {
            if (isFresh(reading, nowMs)) threshold.onReading(reading)
            lossOfSignal.onReading(reading)
            weakSignal.onReading(reading)
        }
        lossOfSignal.evaluate(nowMs)
        weakSignal.evaluate(nowMs)
        publish()
    }

    /** Stored last reading, on start. Not weak signal: one RSSI shows no sustained weak link. */
    @Synchronized
    fun seed(last: CgmReading, nowMs: Long) {
        if (take(last, nowMs)) {
            if (isFresh(last, nowMs)) threshold.onReading(last)
            lossOfSignal.onReading(last)
        }
        lossOfSignal.evaluate(nowMs)
        weakSignal.evaluate(nowMs)
        publish()
    }

    /** Forgets link state only; [ThresholdAlarm] stays so a standing low doesn't go quiet. */
    @Synchronized
    fun onSourceChanged(nowMs: Long) {
        lossOfSignal.onSourceChanged(nowMs)
        weakSignal.onSourceChanged()
        publish()
    }

    /** No clear, no publish: a raised threshold must not silence a standing low. */
    @Synchronized
    fun updateConfig(config: AlarmConfig) {
        this.config = config
        threshold.updateConfig(config)
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

    /** A mark ahead of [nowMs] means the wall clock stepped back; it no longer orders readings. */
    private fun take(reading: CgmReading, nowMs: Long): Boolean {
        if (newestMeasuredMs?.let { it > nowMs } == true) newestMeasuredMs = null
        val newest = newestMeasuredMs
        if (newest != null && reading.rxWallMs < newest) return false
        if (reading.provenance == ReadingProvenance.MEASURED) newestMeasuredMs = reading.rxWallMs
        return true
    }

    /** Past the loss window a reading cannot stand for the current BG. */
    private fun isFresh(reading: CgmReading, nowMs: Long): Boolean =
        nowMs - reading.rxWallMs < config.lossMin * 60_000L

    private fun publish() {
        _state.value = AlarmState(
            threshold = threshold.breach,
            signalLoss = lossOfSignal.loss,
            overTemperature = overTemp.state,
            weakSignal = weakSignal.weak,
        )
    }
}
