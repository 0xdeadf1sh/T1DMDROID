package com.t1dm.app.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.t1dm.alerts.AlertActuatorConfig
import com.t1dm.alerts.AlertChannels
import com.t1dm.alerts.VibrationActuator

/**
 * Posts the model-PREDICTIVE urgent alert (item 2): when the SELECTED model forecasts an urgent-low
 * or urgent-high crossing on a §3.6-ELIGIBLE forecast ([BgGlance.urgent] non-null), it announces on
 * the SAME critical channel the deterministic alarm uses — DND-bypass, full-screen over the lock
 * screen, alarm sound, insistent vibration — so a predicted urgent crossing is as loud as a measured
 * one. It lives in `:app` (not `:alerts`) because it reads the inference-derived glance; `:alerts`
 * keeps no `:inference` dependency.
 *
 * SAFETY: strictly additive. It never touches the deterministic path and it SUPPRESSES itself while
 * a deterministic critical threshold breach is already firing (`alarmCriticalActive`), so it can only
 * ever add an EARLIER warning, never mute or replace the measured urgent alarm.
 */
class PredictiveAlertPresenter(
    context: Context,
    private val fullScreenIntent: () -> PendingIntent?,
    private val contentIntent: () -> PendingIntent?,
) {
    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val vibrations = VibrationActuator(app)

    /** The crossing signature last announced, so an unchanged prediction does not re-buzz each cycle. */
    private var lastKey: String? = null

    fun update(glance: BgGlance, config: AlertActuatorConfig, alarmCriticalActive: Boolean) {
        val urgent = glance.urgent
        if (urgent == null || alarmCriticalActive || !nm.areNotificationsEnabled()) {
            nm.cancel(TAG, ID)
            lastKey = null
            return
        }
        val key = "${urgent.kind}:${urgent.thresholdMgdl}"
        val channels = AlertChannels.ensure(app, config)
        val title = if (urgent.kind == PredictiveCrossing.Kind.HYPO) {
            "Predicted urgent low"
        } else {
            "Predicted urgent high"
        }
        val eta = if (urgent.etaMin <= 5) "~5 min" else "~${urgent.etaMin} min"
        val body = "${BgFormat.crossingLine(urgent)} — forecast ${urgent.projectedMgdl} mg/dL " +
            "(crossing ${urgent.thresholdMgdl}) in $eta. Model prediction; verify."
        val builder = Notification.Builder(app, channels.critical)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
        fullScreenIntent()?.let { builder.setFullScreenIntent(it, true) }
        nm.notify(TAG, ID, builder.build())

        // Buzz only when the crossing target changes, so a steady prediction is not a repeating jab.
        if (key != lastKey) {
            vibrations.buzz(config.criticalVibration)
            lastKey = key
        }
    }

    private companion object {
        const val TAG = "predict"
        const val ID = 4103
    }
}
