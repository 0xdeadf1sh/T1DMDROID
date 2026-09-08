package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/** mg/dL, unbounded; out-of-order warns not blocks. Raised threshold can't silence low (§3.6-A). */
@Composable
fun AlarmThresholdsScreen(
    urgentLow: Int,
    low: Int,
    high: Int,
    urgentHigh: Int,
    onChange: (urgentLow: Int, low: Int, high: Int, urgentHigh: Int) -> Unit,
) {
    SettingsScaffold(SettingsScreenKey.ALARM_THRESHOLDS) {
        SettingsNote("mg/dL · urgent bands bypass DND")

        IntStepper(alarmUrgentLow, urgentLow, "mg/dL", step = 5, min = 0) { onChange(it, low, high, urgentHigh) }
        IntStepper(alarmLow, low, "mg/dL", step = 5, min = 0) { onChange(urgentLow, it, high, urgentHigh) }
        IntStepper(alarmHigh, high, "mg/dL", step = 5, min = 0) { onChange(urgentLow, low, it, urgentHigh) }
        IntStepper(alarmUrgentHigh, urgentHigh, "mg/dL", step = 5, min = 0) { onChange(urgentLow, low, high, it) }

        val ordered = urgentLow <= low && low <= high && high <= urgentHigh
        if (!ordered) {
            DangerBanner(
                "Out of order — bands will classify oddly",
            )
        }
    }
}

private const val ALARM_SECTION = "Alarm thresholds"

private val alarmUrgentLow = SettingsKnob(
    id = "alarm.urgent_low",
    screen = SettingsScreenKey.ALARM_THRESHOLDS,
    section = ALARM_SECTION,
    label = "Urgent low",
    subtitle = "The critical hypo band, in mg/dL — bypasses Do-Not-Disturb",
    synonyms = listOf(
        "urgent low", "critical low", "severe low", "hypo", "hypoglycemia", "hypoglycaemia",
        "alarm", "threshold", "band", "danger", "55", "emergency",
    ),
)

private val alarmLow = SettingsKnob(
    id = "alarm.low",
    screen = SettingsScreenKey.ALARM_THRESHOLDS,
    section = ALARM_SECTION,
    label = "Low",
    subtitle = "The warning-tier low band, in mg/dL",
    synonyms = listOf(
        "low", "low alarm", "hypo", "hypoglycemia", "hypoglycaemia", "warning", "alarm",
        "threshold", "band", "70",
    ),
)

private val alarmHigh = SettingsKnob(
    id = "alarm.high",
    screen = SettingsScreenKey.ALARM_THRESHOLDS,
    section = ALARM_SECTION,
    label = "High",
    subtitle = "The warning-tier high band, in mg/dL",
    synonyms = listOf(
        "high", "high alarm", "hyper", "hyperglycemia", "hyperglycaemia", "warning", "alarm",
        "threshold", "band", "180",
    ),
)

private val alarmUrgentHigh = SettingsKnob(
    id = "alarm.urgent_high",
    screen = SettingsScreenKey.ALARM_THRESHOLDS,
    section = ALARM_SECTION,
    label = "Urgent high",
    subtitle = "The critical hyper band, in mg/dL — bypasses Do-Not-Disturb",
    synonyms = listOf(
        "urgent high", "critical high", "severe high", "hyper", "hyperglycemia", "hyperglycaemia",
        "alarm", "threshold", "band", "danger", "250", "dka",
    ),
)

internal val settingsAlarmThresholdKnobs = listOf(alarmUrgentLow, alarmLow, alarmHigh, alarmUrgentHigh)
