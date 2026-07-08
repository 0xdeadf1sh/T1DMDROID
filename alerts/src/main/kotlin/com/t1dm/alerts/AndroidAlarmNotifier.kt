package com.t1dm.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.t1dm.core.model.AlertBand

/**
 * Phase-1 emission for the deterministic alarm: a basic notification per active alarm plus
 * vibration (PLAN.private.md §3.6-A — rich per-band sound is Phase 7). Two channels separate the
 * urgent tier (heads-up, DND-bypass) from the plain tier; both are silent by design and speak only
 * through the K90 actuators for now.
 *
 * Notifications are addressed by fixed ids so a cleared sub-alarm cancels precisely.
 */
class AndroidAlarmNotifier(context: Context) : AlarmNotifier {

    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val vibrator: Vibrator =
        (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    init {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_WARNING, "Glucose alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Low / high glucose and lost-signal warnings."
                    enableVibration(true)
                    vibrationPattern = WARN_PATTERN
                    setSound(null, null)
                },
                NotificationChannel(CH_CRITICAL, "Urgent glucose alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Urgent-low / urgent-high and escalated lost-signal alarms."
                    enableVibration(true)
                    vibrationPattern = CRIT_PATTERN
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                },
            ),
        )
    }

    override fun emit(state: AlarmState) {
        state.threshold?.let { post(ID_THRESHOLD, "glucose", it) } ?: nm.cancel(ID_THRESHOLD)
        state.signalLoss?.let { post(ID_LOSS, "signal", it) } ?: nm.cancel(ID_LOSS)
        state.primary?.let { vibrate(it) }
    }

    override fun reAlert(state: AlarmState) {
        state.primary?.takeIf { it.severity == AlarmSeverity.CRITICAL }?.let { vibrate(it) }
    }

    override fun clear() {
        nm.cancel(ID_THRESHOLD)
        nm.cancel(ID_LOSS)
    }

    private fun post(id: Int, tag: String, alarm: ActiveAlarm) {
        if (!nm.areNotificationsEnabled()) return
        val channel = if (alarm.severity == AlarmSeverity.CRITICAL) CH_CRITICAL else CH_WARNING
        val notification = Notification.Builder(app, channel)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(titleOf(alarm))
            .setContentText(alarm.message)
            .setStyle(Notification.BigTextStyle().bigText(alarm.message))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(alarm.severity == AlarmSeverity.CRITICAL)
            .setAutoCancel(false)
            .build()
        nm.notify(tag, id, notification)
    }

    private fun vibrate(alarm: ActiveAlarm) {
        val pattern = if (alarm.severity == AlarmSeverity.CRITICAL) CRIT_PATTERN else WARN_PATTERN
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun titleOf(alarm: ActiveAlarm): String = when (alarm) {
        is ThresholdBreach -> when (alarm.band) {
            AlertBand.URGENT_LOW -> "Urgent low glucose"
            AlertBand.LOW -> "Low glucose"
            AlertBand.HIGH -> "High glucose"
            AlertBand.URGENT_HIGH -> "Urgent high glucose"
            AlertBand.IN_RANGE -> "Glucose in range"
        }
        is SignalLoss -> "Sensor signal lost"
    }

    private companion object {
        const val CH_WARNING = "t1dm.alerts.glucose"
        const val CH_CRITICAL = "t1dm.alerts.glucose.urgent"
        const val ID_THRESHOLD = 4101
        const val ID_LOSS = 4102
        val WARN_PATTERN = longArrayOf(0, 400, 250, 400)
        val CRIT_PATTERN = longArrayOf(0, 600, 200, 600, 200, 600)
    }
}
