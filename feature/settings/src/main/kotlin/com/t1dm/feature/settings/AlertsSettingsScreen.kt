package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Alert sound & vibration (Phase 7C item 14, over the Phase-7B actuators).
 * Per-tier tone on/off (over the system ALARM-usage sound so urgent alerts pierce DND), a K90
 * vibration preset per tier, the DND-bypass switch for the urgent tier, and the repeat cadence for a
 * persisting critical alarm. Vibration presets are passed as opaque name strings so this module stays
 * free of the `:alerts` dependency. Additive: none of these change WHEN an alarm fires (§3.6-A).
 *
 * Pure/stateless.
 */
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
    /** Issue 8 — play the tapped preset immediately so the user can feel it before committing. */
    onPreviewVibration: (String) -> Unit = {},
) {
    val opts = vibrationOptions.map { it to it.lowercase().replaceFirstChar(Char::uppercase) }

    SettingsScaffold("Alert sound & vibration") {
        SettingsSectionHeader("Warning tier (low / high / approaching)")
        ToggleRow("Play a sound", warningSoundOn, "Off = vibrate only") { onSetWarningSoundOn(it) }
        ChipPicker("Vibration", opts, warningVibration) { onPreviewVibration(it); onSetWarningVibration(it) }
        SettingsNote("Tap a preset to feel it now.")

        SettingsSectionHeader("Urgent tier (urgent-low / urgent-high / predicted)")
        ToggleRow("Play a sound", criticalSoundOn, "The alarm-usage tone, audible in silent mode") { onSetCriticalSoundOn(it) }
        ChipPicker("Vibration", opts, criticalVibration) { onPreviewVibration(it); onSetCriticalVibration(it) }
        ToggleRow("Bypass Do-Not-Disturb", bypassDnd, "Urgent alerts sound even under DND") { onSetBypassDnd(it) }

        SettingsSectionHeader("Repeat")
        SettingsNote("How often a still-active urgent alarm re-announces itself.")
        IntStepper("Repeat cadence", repeatCadenceMin, "min", step = 1, min = 1) { onSetRepeatCadence(it) }

        SettingsSectionHeader("Rate limit")
        SettingsNote(
            "The least time between an alarm's sound + vibration while it stays in the same band, so a " +
                "fast reading stream can't re-buzz every minute. A new or worsening band still alerts at " +
                "once. 0 = no limit.",
        )
        IntStepper("Minimum time between alerts", minActuationMin, "min", step = 1, min = 0) { onSetMinActuation(it) }

        SettingsSectionHeader("Snooze")
        SettingsNote(
            "How long the Snooze button silences a firing alarm before it auto-expires and re-alerts. " +
                "Snooze is always time-bounded — an urgent alarm can be snoozed but never dismissed for " +
                "good. A worse reading pierces the snooze immediately.",
        )
        IntStepper("Snooze duration", snoozeMin, "min", step = 1, min = 1, max = 60) { onSetSnoozeMin(it) }

        SettingsNote(
            "A changed sound or vibration migrates to a fresh notification channel — you may briefly " +
                "see the old channel disappear from the system notification settings.",
        )
    }
}
