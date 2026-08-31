package com.t1dm.app.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.t1dm.alerts.AlertActuatorConfig
import com.t1dm.alerts.AlertChannels
import com.t1dm.alerts.VibrationActuator

/**
 * Suppresses itself while the deterministic critical alarm is firing, so it can only ever add an
 * earlier warning, never mute one. In `:app` rather than `:alerts`, which keeps no `:inference`
 * dependency.
 */
class PredictiveAlertPresenter(
    context: Context,
    private val fullScreenIntent: () -> PendingIntent?,
    private val contentIntent: () -> PendingIntent?,
) {
    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val vibrations = VibrationActuator(app)

    /** The crossing last announced, so a steady prediction does not re-buzz each cycle. */
    private var lastKey: String? = null

    /** Returns true while the alert is showing. */
    fun update(
        glance: BgGlance,
        config: AlertActuatorConfig,
        alarmCriticalActive: Boolean,
        accentArgb: Int,
    ): Boolean {
        val urgent = glance.urgent
        if (urgent == null || alarmCriticalActive || !nm.areNotificationsEnabled()) {
            nm.cancel(TAG, ID)
            lastKey = null
            return false
        }
        val key = "${urgent.kind}:${urgent.thresholdMgdl}"
        val channels = AlertChannels.ensure(app, config)
        val title = if (urgent.kind == PredictiveCrossing.Kind.HYPO) {
            "Predicted urgent low"
        } else {
            "Predicted urgent high"
        }
        val eta = if (urgent.etaMin <= 5) "~5 min" else "~${urgent.etaMin} min"
        val body = "${urgent.projectedMgdl} mg/dL in $eta (crosses ${urgent.thresholdMgdl}). Predicted — verify."
        val builder = Notification.Builder(app, channels.critical)
            .setSmallIcon(NotificationIcons.res())
            .setColor(accentArgb)
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

        if (key != lastKey) {
            vibrations.buzz(config.criticalVibration)
            lastKey = key
        }
        return true
    }

    fun clear() {
        nm.cancel(TAG, ID)
        lastKey = null
    }

    private companion object {
        const val TAG = "predict"
        const val ID = 4103
    }
}
