package com.t1dm.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.VibrationEffect

/** A null sound means silent — vibration only. */
data class AlertActuatorConfig(
    val warningSound: Uri?,
    val criticalSound: Uri?,
    val warningVibration: VibrationPreset = VibrationPreset.DOUBLE,
    val criticalVibration: VibrationPreset = VibrationPreset.INSISTENT,
    val bypassDnd: Boolean = true,
) {
    /** Channel ids embed this: a channel's sound, importance and DND bit are frozen at creation. */
    fun version(): String {
        var h = 7
        h = 31 * h + (warningSound?.hashCode() ?: 0)
        h = 31 * h + (criticalSound?.hashCode() ?: 0)
        h = 31 * h + warningVibration.ordinal
        h = 31 * h + criticalVibration.ordinal
        h = 31 * h + if (bypassDnd) 1 else 0
        return Integer.toHexString(h)
    }

    companion object {
        val SILENT = AlertActuatorConfig(warningSound = null, criticalSound = null)
    }
}

enum class VibrationPreset {
    NONE,
    SOFT,
    DOUBLE,
    INSISTENT,
    ESCALATING;

    /** Fallback where the device exposes no vibration primitives. */
    fun waveform(): LongArray = when (this) {
        NONE -> longArrayOf(0)
        SOFT -> longArrayOf(0, 200)
        DOUBLE -> longArrayOf(0, 400, 250, 400)
        INSISTENT -> longArrayOf(0, 600, 200, 600, 200, 600)
        ESCALATING -> longArrayOf(0, 150, 120, 300, 120, 600)
    }
}

object AlertChannels {

    fun ids(config: AlertActuatorConfig): Ids {
        val v = config.version()
        return Ids(
            warning = "t1dm.alerts.glucose.v$v",
            critical = "t1dm.alerts.glucose.urgent.v$v",
            device = "t1dm.alerts.device.v$v",
            deviceCritical = "t1dm.alerts.device.urgent.v$v",
        )
    }

    data class Ids(
        val warning: String,
        val critical: String,
        val device: String,
        val deviceCritical: String,
    )

    fun ensure(context: Context, config: AlertActuatorConfig): Ids {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ids = ids(config)

        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val warning = NotificationChannel(ids.warning, "Glucose alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Low/high, approaching, and lost-signal warnings"
            configureVibration(config.warningVibration)
            setSound(config.warningSound, if (config.warningSound != null) alarmAttrs else null)
        }
        val critical = NotificationChannel(ids.critical, "Urgent glucose alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Urgent and predicted-urgent alarms"
            configureVibration(config.criticalVibration)
            setBypassDnd(config.bypassDnd)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(config.criticalSound, if (config.criticalSound != null) alarmAttrs else null)
        }
        // Device temperature never bypasses DND, unlike the urgent glucose tier.
        val device = NotificationChannel(ids.device, "Device temperature", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Temperature high — forecasting paused"
            configureVibration(config.warningVibration)
            setSound(config.warningSound, if (config.warningSound != null) alarmAttrs else null)
        }
        val deviceCritical = NotificationChannel(ids.deviceCritical, "Urgent device temperature", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Temperature critically high"
            configureVibration(config.criticalVibration)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(config.criticalSound, if (config.criticalSound != null) alarmAttrs else null)
        }
        nm.createNotificationChannels(listOf(warning, critical, device, deviceCritical))

        val keep = setOf(ids.warning, ids.critical, ids.device, ids.deviceCritical)
        nm.notificationChannels
            .filter {
                (it.id.startsWith("t1dm.alerts.glucose") || it.id.startsWith("t1dm.alerts.device")) &&
                    it.id !in keep
            }
            .forEach { nm.deleteNotificationChannel(it.id) }
        return ids
    }

    private fun NotificationChannel.configureVibration(preset: VibrationPreset) {
        if (preset == VibrationPreset.NONE) {
            enableVibration(false)
        } else {
            enableVibration(true)
            vibrationPattern = preset.waveform()
        }
    }
}
