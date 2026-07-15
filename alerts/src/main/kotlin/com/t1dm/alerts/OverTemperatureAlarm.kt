package com.t1dm.alerts

/**
 * The model-free device-over-temperature alarm (locked decisions D1/D4). It latches on the
 * battery-sensor °C with hysteresis: it fires once the reading reaches [AlarmConfig.overTempAlertC]
 * and holds until the reading falls back to [AlarmConfig.overTempClearC] — the band between the two
 * is deliberate so a temperature hovering at the limit does not chatter the notification on and off.
 *
 * A null reading (the sensor was momentarily unreadable) is INERT: it holds the prior state rather
 * than fabricating a clear, mirroring the loss-of-signal rule that only real data may change state.
 * A disabled config clears any standing episode.
 *
 * Deterministic and clock-free save for the stamp passed in via [evaluate]. Like [LossOfSignalAlarm]
 * it keeps the SAME [OverTemperature] object across ticks while the severity is unchanged, so the UI
 * and notifier see a stable episode rather than a fresh object every second.
 */
class OverTemperatureAlarm(private var config: AlarmConfig) {

    var state: OverTemperature? = null
        private set

    /** Live-swap the enable flag / hysteresis band / severity WITHOUT touching [state] (§3.6-A). The
     *  next [evaluate] re-decides against the new band; a config edit alone never fabricates a clear of
     *  a standing over-temperature latch. Disabling still clears on the next evaluate (as before). */
    fun updateConfig(config: AlarmConfig) {
        this.config = config
    }

    fun evaluate(tempC: Double?, nowMs: Long): OverTemperature? {
        if (!config.overTempEnabled) {
            state = null
            return null
        }
        // A missing reading cannot raise OR clear — hold whatever the last real reading decided.
        if (tempC == null) return state

        val firing = when {
            tempC >= config.overTempAlertC -> true
            tempC <= config.overTempClearC -> false
            else -> state != null // inside the hysteresis band: hold the prior latch
        }
        state = if (!firing) {
            null
        } else {
            val severity = config.overTempSeverity
            // Reuse the existing episode object while the severity is unchanged (stable identity).
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
