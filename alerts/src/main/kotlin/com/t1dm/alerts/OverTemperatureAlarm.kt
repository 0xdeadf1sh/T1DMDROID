package com.t1dm.alerts

/** Battery-sensor °C, latched with hysteresis so a temperature at the limit does not chatter. */
class OverTemperatureAlarm(private var config: AlarmConfig) {

    var state: OverTemperature? = null
        private set

    /** Never fabricates a clear of a standing latch; the next [evaluate] re-decides. */
    fun updateConfig(config: AlarmConfig) {
        this.config = config
    }

    fun evaluate(tempC: Double?, nowMs: Long): OverTemperature? {
        if (!config.overTempEnabled) {
            state = null
            return null
        }
        // A missing reading cannot raise or clear.
        if (tempC == null) return state

        val firing = when {
            tempC >= config.overTempAlertC -> true
            tempC <= config.overTempClearC -> false
            else -> state != null // inside the band: hold the latch
        }
        state = if (!firing) {
            null
        } else {
            val severity = config.overTempSeverity
            state?.takeIf { it.severity == severity } ?: OverTemperature(
                tempC = tempC,
                atMs = nowMs,
                alertC = config.overTempAlertC,
                clearC = config.overTempClearC,
                severity = severity,
                escalated = severity == AlarmSeverity.CRITICAL,
                message = overTempMessage(tempC, config.overTempClearC),
            )
        }
        return state
    }
}
