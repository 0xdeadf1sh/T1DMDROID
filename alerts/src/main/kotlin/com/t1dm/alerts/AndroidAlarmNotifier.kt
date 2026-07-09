package com.t1dm.alerts

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import com.t1dm.core.model.AlertBand

/**
 * Android emission for the deterministic alarm (PLAN.private.md §3.6-A + Phase-7 alert polish). Two
 * severity channels (shared with the model-driven predictive presenter via [AlertChannels]) separate
 * the urgent tier — heads-up, DND-bypass, a full-screen intent over the lock screen, a per-band
 * configurable alarm sound, and an insistent K90 vibration primitive — from the plain tier.
 *
 * This class only PRESENTS the [AlarmState] the pure engine produces; it consumes state and never
 * decides when an alarm fires (safety §3.6). The actuator config + the full-screen [PendingIntent]
 * are injected by `:app` (the module stays free of a settings / Activity dependency); both default to
 * the silent, no-full-screen Phase-1 behaviour so tests and headless contexts are unaffected.
 *
 * Notifications are addressed by fixed ids so a cleared sub-alarm cancels precisely.
 */
class AndroidAlarmNotifier(
    context: Context,
    private val actuatorConfig: AlertActuatorConfig = AlertActuatorConfig.SILENT,
    private val fullScreenIntent: () -> PendingIntent? = { null },
    private val contentIntent: () -> PendingIntent? = { null },
) : AlarmNotifier {

    private val app = context.applicationContext
    private val nm = app.getSystemService(android.app.NotificationManager::class.java)
    private val vibrations = VibrationActuator(app)
    private val channels = AlertChannels.ensure(app, actuatorConfig)

    override fun emit(state: AlarmState) {
        state.threshold?.let { post(ID_THRESHOLD, "glucose", it) } ?: nm.cancel("glucose", ID_THRESHOLD)
        state.signalLoss?.let { post(ID_LOSS, "signal", it) } ?: nm.cancel("signal", ID_LOSS)
        state.primary?.let { vibrate(it) }
    }

    override fun reAlert(state: AlarmState) {
        state.primary?.takeIf { it.severity == AlarmSeverity.CRITICAL }?.let { vibrate(it) }
    }

    override fun clear() {
        nm.cancel("glucose", ID_THRESHOLD)
        nm.cancel("signal", ID_LOSS)
    }

    private fun post(id: Int, tag: String, alarm: ActiveAlarm) {
        if (!nm.areNotificationsEnabled()) return
        val critical = alarm.severity == AlarmSeverity.CRITICAL
        val builder = Notification.Builder(app, if (critical) channels.critical else channels.warning)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(titleOf(alarm))
            .setContentText(alarm.message)
            .setStyle(Notification.BigTextStyle().bigText(alarm.message))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(critical)
            .setAutoCancel(false)
            .setContentIntent(contentIntent())
        if (critical) {
            // Full-screen over the lock screen for urgent tiers (item 2 / PLAN S11). Android falls
            // back to a heads-up banner when the screen is on or the special access is ungranted.
            fullScreenIntent()?.let { builder.setFullScreenIntent(it, true) }
        }
        nm.notify(tag, id, builder.build())
    }

    private fun vibrate(alarm: ActiveAlarm) {
        val preset = if (alarm.severity == AlarmSeverity.CRITICAL) {
            actuatorConfig.criticalVibration
        } else {
            actuatorConfig.warningVibration
        }
        vibrations.buzz(preset)
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
        const val ID_THRESHOLD = 4101
        const val ID_LOSS = 4102
    }
}
