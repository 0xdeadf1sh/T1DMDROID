package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/** Vibration presets are opaque strings, no `:alerts` dep; none change when alarm fires (§3.6-A) */
@Composable
fun AlertsSettingsScreen(
    vibrationOptions: List<String>,
    warningVibration: String,
    criticalVibration: String,
    warningSoundOn: Boolean,
    criticalSoundOn: Boolean,
    bypassDnd: Boolean,
    repeatCadenceMin: Int,
    minActuationMin: Int,
    snoozeMin: Int,
    onSetWarningVibration: (String) -> Unit,
    onSetCriticalVibration: (String) -> Unit,
    onSetWarningSoundOn: (Boolean) -> Unit,
    onSetCriticalSoundOn: (Boolean) -> Unit,
    onSetBypassDnd: (Boolean) -> Unit,
    onSetRepeatCadence: (Int) -> Unit,
    onSetMinActuation: (Int) -> Unit,
    onSetSnoozeMin: (Int) -> Unit,
    onPreviewVibration: (String) -> Unit = {},
) {
    val opts = vibrationOptions.map { it to it.lowercase().replaceFirstChar(Char::uppercase) }

    SettingsScaffold(SettingsScreenKey.ALERTS) {
        SettingsSectionHeader("Warning tier (low / high / approaching)")
        ToggleRow(alertsWarningSound, warningSoundOn) { onSetWarningSoundOn(it) }
        // The preset plays on select; no UI detent stacked under it.
        ChipPicker(alertsWarningVibration, opts, warningVibration, tickOnSelect = false) {
            onPreviewVibration(it)
            onSetWarningVibration(it)
        }

        SettingsSectionHeader("Urgent tier (urgent-low / urgent-high / predicted)")
        ToggleRow(alertsCriticalSound, criticalSoundOn) { onSetCriticalSoundOn(it) }
        ChipPicker(alertsCriticalVibration, opts, criticalVibration, tickOnSelect = false) {
            onPreviewVibration(it)
            onSetCriticalVibration(it)
        }
        ToggleRow(alertsBypassDnd, bypassDnd) { onSetBypassDnd(it) }

        SettingsSectionHeader("Repeat")
        IntStepper(alertsRepeatCadence, repeatCadenceMin, "min", step = 1, min = 1) { onSetRepeatCadence(it) }

        SettingsSectionHeader("Rate limit")
        SettingsNote("Least time between buzzes in a band; 0 = no limit")
        IntStepper(alertsRateLimit, minActuationMin, "min", step = 1, min = 0) { onSetMinActuation(it) }

        SettingsSectionHeader("Snooze")
        SettingsNote("Snooze length; a worse reading pierces it")
        IntStepper(alertsSnooze, snoozeMin, "min", step = 1, min = 1, max = 60) { onSetSnoozeMin(it) }

        SettingsNote("A changed sound or vibration migrates to a fresh notification channel.")
    }
}

private const val WARNING_TIER = "Warning tier (low / high / approaching)"
private const val URGENT_TIER = "Urgent tier (urgent-low / urgent-high / predicted)"

private val alertsWarningSound = SettingsKnob(
    id = "alerts.warning_sound",
    screen = SettingsScreenKey.ALERTS,
    section = WARNING_TIER,
    label = "Play a sound",
    subtitle = "Off = vibrate only",
    synonyms = listOf(
        "sound", "tone", "audio", "noise", "beep", "ring", "warning sound", "mute", "silent",
        "alert sound", "low", "high",
    ),
)

private val alertsWarningVibration = SettingsKnob(
    id = "alerts.warning_vibration",
    screen = SettingsScreenKey.ALERTS,
    section = WARNING_TIER,
    label = "Vibration",
    subtitle = "The vibration pattern for warning-tier alerts",
    synonyms = listOf(
        "vibration", "vibrate", "buzz", "rumble", "pattern", "preset", "haptic", "warning buzz",
        "shake", "silent",
    ),
)

private val alertsCriticalSound = SettingsKnob(
    id = "alerts.critical_sound",
    screen = SettingsScreenKey.ALERTS,
    section = URGENT_TIER,
    label = "Play a sound",
    subtitle = "Alarm-usage tone, audible in silent mode",
    synonyms = listOf(
        "sound", "tone", "audio", "alarm", "urgent sound", "critical", "siren", "loud",
        "silent mode", "ringer",
    ),
)

private val alertsCriticalVibration = SettingsKnob(
    id = "alerts.critical_vibration",
    screen = SettingsScreenKey.ALERTS,
    section = URGENT_TIER,
    label = "Vibration",
    subtitle = "The vibration pattern for urgent-tier alarms",
    synonyms = listOf(
        "vibration", "vibrate", "buzz", "rumble", "pattern", "preset", "urgent buzz", "critical",
        "haptic", "shake",
    ),
)

private val alertsBypassDnd = SettingsKnob(
    id = "alerts.bypass_dnd",
    screen = SettingsScreenKey.ALERTS,
    section = URGENT_TIER,
    label = "Bypass Do-Not-Disturb",
    subtitle = "Urgent alerts sound even under DND",
    synonyms = listOf(
        "dnd", "do not disturb", "do-not-disturb", "bypass", "override", "silent mode", "night",
        "focus", "priority", "interruption", "pierce",
    ),
)

private val alertsRepeatCadence = SettingsKnob(
    id = "alerts.repeat_cadence",
    screen = SettingsScreenKey.ALERTS,
    section = "Repeat",
    label = "Repeat cadence",
    subtitle = "How often a still-active urgent alarm re-announces itself",
    synonyms = listOf("repeat", "again", "re-alert", "reannounce", "nag", "persist", "cadence", "interval", "every"),
)

private val alertsRateLimit = SettingsKnob(
    id = "alerts.rate_limit",
    screen = SettingsScreenKey.ALERTS,
    section = "Rate limit",
    label = "Minimum time between alerts",
    subtitle = "Throttle for an alarm holding the same band; 0 = no limit",
    synonyms = listOf(
        "rate limit", "throttle", "cooldown", "minimum", "spam", "too often", "quiet",
        "debounce", "between alerts", "frequency",
    ),
)

private val alertsSnooze = SettingsKnob(
    id = "alerts.snooze",
    screen = SettingsScreenKey.ALERTS,
    section = "Snooze",
    label = "Snooze duration",
    subtitle = "How long Snooze silences a firing alarm before it re-alerts (always time-bounded)",
    synonyms = listOf("snooze", "silence", "postpone", "defer", "shush", "quiet", "dismiss", "duration", "minutes"),
)

internal val settingsAlertKnobs = listOf(
    alertsWarningSound,
    alertsWarningVibration,
    alertsCriticalSound,
    alertsCriticalVibration,
    alertsBypassDnd,
    alertsRepeatCadence,
    alertsRateLimit,
    alertsSnooze,
)
