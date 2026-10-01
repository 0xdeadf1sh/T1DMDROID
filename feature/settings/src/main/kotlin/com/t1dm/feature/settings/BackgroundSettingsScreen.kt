package com.t1dm.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics

/** Read live from the OS; null where this phone or Android version lacks the control. */
data class BackgroundAccess(
    val batteryUnrestricted: Boolean,
    val exactAlarms: Boolean?,
    val notifications: Boolean,
    val fullScreenAlerts: Boolean?,
    /** The maker's own background page, by the maker's name for it. */
    val vendorPage: String?,
)

enum class BackgroundFix { BATTERY, EXACT_ALARMS, NOTIFICATIONS, FULL_SCREEN_ALERTS, VENDOR }

@Composable
fun BackgroundSettingsScreen(access: BackgroundAccess, onFix: (BackgroundFix) -> Unit) {
    SettingsScaffold(SettingsScreenKey.BACKGROUND) {
        SettingsAnchor(bgBattery) {
            val ok = access.batteryUnrestricted
            AccessRow(bgBattery.label, if (ok) "Unrestricted" else "Restricted — may stop CGM", ok) {
                onFix(BackgroundFix.BATTERY)
            }
        }
        access.exactAlarms?.let { ok ->
            SettingsAnchor(bgExactAlarms) {
                AccessRow(bgExactAlarms.label, if (ok) "Allowed" else "Denied — alert repeats run late", ok) {
                    onFix(BackgroundFix.EXACT_ALARMS)
                }
            }
        }
        SettingsAnchor(bgNotifications) {
            val ok = access.notifications
            AccessRow(bgNotifications.label, if (ok) "Allowed" else "Blocked — no alarms show", ok) {
                onFix(BackgroundFix.NOTIFICATIONS)
            }
        }
        access.fullScreenAlerts?.let { ok ->
            SettingsAnchor(bgFullScreen) {
                AccessRow(bgFullScreen.label, if (ok) "Allowed" else "Denied — urgent alarms stay in the shade", ok) {
                    onFix(BackgroundFix.FULL_SCREEN_ALERTS)
                }
            }
        }
        access.vendorPage?.let { page ->
            SettingsAnchor(bgVendor) {
                AccessRow(page, "Allow — state not readable", ok = true) { onFix(BackgroundFix.VENDOR) }
            }
        }
    }
}

@Composable
private fun AccessRow(label: String, status: String, ok: Boolean, onClick: () -> Unit) {
    val haptics = rememberT1dmHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { haptics.perform(HapticEvent.NavSwitch); onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val BACKGROUND_SECTION = "Background"

private val bgBattery = SettingsKnob(
    id = "background.battery",
    screen = SettingsScreenKey.BACKGROUND,
    section = BACKGROUND_SECTION,
    label = "Battery optimization",
    subtitle = "Restricted can stop the CGM service",
    synonyms = listOf(
        "battery", "optimisation", "doze", "unrestricted", "restricted", "exempt", "whitelist", "killed",
        "background",
    ),
)

private val bgExactAlarms = SettingsKnob(
    id = "background.exact_alarms",
    screen = SettingsScreenKey.BACKGROUND,
    section = BACKGROUND_SECTION,
    label = "Exact alarms",
    subtitle = "Alert repeat timing",
    synonyms = listOf("alarm", "exact", "schedule", "repeat", "timing", "late"),
)

private val bgNotifications = SettingsKnob(
    id = "background.notifications",
    screen = SettingsScreenKey.BACKGROUND,
    section = BACKGROUND_SECTION,
    label = "Notifications",
    subtitle = "Alarms and the CGM service notice",
    synonyms = listOf("notification", "blocked", "permission", "alerts", "shade"),
)

private val bgFullScreen = SettingsKnob(
    id = "background.full_screen",
    screen = SettingsScreenKey.BACKGROUND,
    section = BACKGROUND_SECTION,
    label = "Full-screen alerts",
    subtitle = "Urgent alarms over the lock screen",
    synonyms = listOf("full screen", "fullscreen", "lock screen", "urgent", "intent", "wake"),
)

private val bgVendor = SettingsKnob(
    id = "background.vendor",
    screen = SettingsScreenKey.BACKGROUND,
    section = BACKGROUND_SECTION,
    label = "Autostart",
    subtitle = "The phone maker's own background kill switch",
    synonyms = listOf(
        "autostart", "auto start", "auto launch", "app launch", "sleeping apps", "background startup",
        "xiaomi", "miui", "hyperos", "samsung", "oneplus", "oppo", "realme", "vivo", "huawei", "honor",
    ),
)

internal val settingsBackgroundKnobs = listOf(bgBattery, bgExactAlarms, bgNotifications, bgFullScreen, bgVendor)
