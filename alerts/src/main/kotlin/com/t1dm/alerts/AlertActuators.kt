package com.t1dm.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.VibrationEffect

/**
 * User-configurable per-severity actuators for the alert notifications (Phase 7 "Alert polish"):
 * a sound Uri (null ⇒ silent — vibrate only) and a K90 vibration preset per tier, plus the DND-bypass
 * bit for the urgent tier. Deliberately a plain value type; `:app` reads the kv-backed choices and
 * supplies it (the module stays free of a settings dependency). Additive to the deterministic path —
 * changing a sound can never alter WHEN an alarm fires, only how it is announced.
 */
data class AlertActuatorConfig(
    val warningSound: Uri?,
    val criticalSound: Uri?,
    val warningVibration: VibrationPreset = VibrationPreset.DOUBLE,
    val criticalVibration: VibrationPreset = VibrationPreset.INSISTENT,
    val bypassDnd: Boolean = true,
) {
    /** A stable version token so a config change yields fresh channel ids (channels are immutable). */
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
        /** The Phase-1 behaviour: silent channels, speak through the K90 actuators only. */
        val SILENT = AlertActuatorConfig(warningSound = null, criticalSound = null)
    }
}

/**
 * K90 vibration presets ("many presets, no editor, rich use of K90 actuators").
 * Rendered with hardware primitives ([VibrationEffect.Composition]) when the device exposes them
 * (the Dimensity-9500 K90 does), degrading to a plain waveform otherwise.
 */
enum class VibrationPreset {
    NONE,
    SOFT,
    DOUBLE,
    INSISTENT,
    ESCALATING;

    /** A one-shot waveform fallback for [VibrationEffect.createWaveform] (no primitive support). */
    fun waveform(): LongArray = when (this) {
        NONE -> longArrayOf(0)
        SOFT -> longArrayOf(0, 200)
        DOUBLE -> longArrayOf(0, 400, 250, 400)
        INSISTENT -> longArrayOf(0, 600, 200, 600, 200, 600)
        ESCALATING -> longArrayOf(0, 150, 120, 300, 120, 600)
    }
}

/**
 * The two severity channels, shared by the deterministic [AndroidAlarmNotifier] and the model-driven
 * predictive-alert presenter in `:app`, so both tiers announce identically. Channel ids carry the
 * config [AlertActuatorConfig.version] because a channel's sound / importance / DND-bypass are frozen
 * at creation — a changed config simply migrates to a new channel id.
 */
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

    /** Idempotently (re)create both channels for [config] and return their ids. Prunes stale ones. */
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
        // Device-over-temperature (D4) rides its OWN channels so it is manageable separately in system
        // settings and — unlike the urgent glucose tier — NEVER bypasses Do-Not-Disturb: a thermal
        // notice is device-health, not a glucose emergency, and must not punch through DND.
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

        // Prune older-versioned channels so the settings list stays clean.
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
