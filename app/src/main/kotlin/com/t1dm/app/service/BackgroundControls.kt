package com.t1dm.app.service

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.t1dm.feature.settings.BackgroundAccess
import com.t1dm.feature.settings.BackgroundFix

/** The OS and phone-maker switches that can stop the CGM service, and the page for each. */
object BackgroundControls {

    fun read(context: Context): BackgroundAccess = BackgroundAccess(
        batteryUnrestricted = batteryUnrestricted(context),
        // From 33 the app holds USE_EXACT_ALARM, which the user cannot revoke.
        exactAlarms = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else {
            null
        },
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        fullScreenAlerts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            null
        },
        vendorPage = vendorPage()?.label,
    )

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun open(context: Context, fix: BackgroundFix) {
        val pkg = Uri.fromParts("package", context.packageName, null)
        val pages = when (fix) {
            BackgroundFix.BATTERY -> listOf(
                if (batteryUnrestricted(context)) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
                },
            )
            BackgroundFix.EXACT_ALARMS -> listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            BackgroundFix.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            BackgroundFix.FULL_SCREEN_ALERTS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
                } else {
                    emptyList()
                }
            BackgroundFix.VENDOR -> vendorPage()?.components.orEmpty().map { Intent().setComponent(it) }
        }
        // Maker pages move between OS releases; the app's details page is always there.
        for (intent in pages + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    private class VendorPage(val label: String, val components: List<ComponentName>)

    private fun vendorPage(): VendorPage? = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> VendorPage(
            "Autostart",
            listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        )
        "samsung" -> VendorPage(
            "Background usage limits",
            listOf(
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ),
        )
        "oneplus", "oppo", "realme" -> VendorPage(
            "Auto launch",
            listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
                ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                ),
            ),
        )
        "vivo" -> VendorPage(
            "Background startup",
            listOf(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ),
        )
        "huawei", "honor" -> VendorPage(
            "App launch",
            listOf(
                ComponentName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            ),
        )
        else -> null
    }
}
