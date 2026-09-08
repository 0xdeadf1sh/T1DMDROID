package com.t1dm.alerts

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import com.t1dm.core.model.AlertBand

/** Presents the [AlarmState] the engine produces; never decides when an alarm fires (§3.6). */
class AndroidAlarmNotifier(
    context: Context,
    private val actuatorConfig: () -> AlertActuatorConfig = { AlertActuatorConfig.SILENT },
    private val fullScreenIntent: () -> PendingIntent? = { null },
    private val contentIntent: () -> PendingIntent? = { null },
    /** Supplied by `:app`; this module cannot reach its `R`. Null ⇒ the platform warning icon. */
    private val smallIcon: (critical: Boolean) -> android.graphics.drawable.Icon? = { null },
    /** ARGB; null ⇒ leave unset. */
    private val accentColor: () -> Int? = { null },
    /** Presentation gate only; the engine still fires. */
    private val suppressed: () -> Boolean = { false },
    /** Presentation gate only; time-bounded, escalation-pierced, never silences over-temp. */
    private val snoozeState: () -> SnoozeState = { SnoozeState.NONE },
    /** Null ⇒ no snooze button. */
    private val snoozeIntent: (ActiveAlarm) -> PendingIntent? = { null },
    /** Null ⇒ no dismiss button. */
    private val dismissIntent: (ActiveAlarm) -> PendingIntent? = { null },
    private val snoozeMinutes: () -> Int = { 15 },
    /** Bounds sound and vibration only; the text still updates in between. 0 ⇒ no throttle. */
    private val minActuationIntervalMs: () -> Long = { 0L },
    private val clock: () -> Long = System::currentTimeMillis,
) : AlarmNotifier {

    private val app = context.applicationContext
    private val nm = app.getSystemService(android.app.NotificationManager::class.java)
    private val vibrations = VibrationActuator(app)
    // Channel sound/importance/DND-bypass freeze at creation; a config change needs a new id.
    @Volatile private var channelsFor: Pair<String, AlertChannels.Ids>? = null

    private fun channels(): AlertChannels.Ids {
        val cfg = actuatorConfig()
        val version = cfg.version()
        channelsFor?.let { (cached, ids) -> if (cached == version) return ids }
        return AlertChannels.ensure(app, cfg).also { channelsFor = version to it }
    }

    @Volatile private var lastActuateMs = Long.MIN_VALUE
    @Volatile private var lastEpisodeKey: String? = null

    override fun emit(state: AlarmState) {
        val now = clock()
        val visible = state.visibleAfterGates(suppressed(), snoozeState(), now)
        val presentable = visible.primary
        val key = presentable?.let { episodeKey(it) }
        val actuate = presentable != null &&
            (key != lastEpisodeKey || now - lastActuateMs >= minActuationIntervalMs().coerceAtLeast(0L))
        // `alertOnce = !actuate`: a throttled re-post updates the text silently.
        visible.threshold?.let { post(ID_THRESHOLD, "glucose", it, !actuate) } ?: nm.cancel("glucose", ID_THRESHOLD)
        visible.signalLoss?.let { post(ID_LOSS, "signal", it, !actuate) } ?: nm.cancel("signal", ID_LOSS)
        visible.weakSignal?.let { post(ID_WEAK, "weaksignal", it, !actuate) } ?: nm.cancel("weaksignal", ID_WEAK)
        visible.overTemperature?.let { post(ID_OVERTEMP, "device", it, !actuate) } ?: nm.cancel("device", ID_OVERTEMP)
        if (actuate) {
            presentable?.let { vibrate(it) }
            lastActuateMs = now
        }
        lastEpisodeKey = key
    }

    override fun reAlert(state: AlarmState) {
        val visible = state.visibleAfterGates(suppressed(), snoozeState(), clock())
        visible.primary?.takeIf { it.severity == AlarmSeverity.CRITICAL }?.let {
            vibrate(it)
            lastActuateMs = clock()
        }
    }

    override fun clear() {
        nm.cancel("glucose", ID_THRESHOLD)
        nm.cancel("signal", ID_LOSS)
        nm.cancel("weaksignal", ID_WEAK)
        nm.cancel("device", ID_OVERTEMP)
        lastEpisodeKey = null
    }

    private fun episodeKey(alarm: ActiveAlarm): String = when (alarm) {
        is ThresholdBreach -> "t:${alarm.band}:${alarm.severity}"
        is SignalLoss -> "s:${alarm.severity}"
        is WeakSignal -> "w:${alarm.severity}"
        is OverTemperature -> "d:${alarm.severity}"
    }

    private fun post(id: Int, tag: String, alarm: ActiveAlarm, alertOnce: Boolean) {
        if (!nm.areNotificationsEnabled()) return
        val critical = alarm.severity == AlarmSeverity.CRITICAL
        // Over-temp rides its own device channels, never DND-bypass.
        val channel = when (alarm) {
            is OverTemperature -> channels().let { if (critical) it.deviceCritical else it.device }
            else -> channels().let { if (critical) it.critical else it.warning }
        }
        val builder = Notification.Builder(app, channel)
            .setContentTitle(titleOf(alarm))
            .setContentText(alarm.message)
            .setStyle(Notification.BigTextStyle().bigText(alarm.message))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(alertOnce)
            .setOngoing(critical)
            .setAutoCancel(false)
            .setContentIntent(contentIntent())
        val icon = smallIcon(critical)
        if (icon != null) builder.setSmallIcon(icon) else builder.setSmallIcon(android.R.drawable.stat_sys_warning)
        accentColor()?.let { builder.setColor(it) }
        // Snooze every tier; dismiss WARNING only (critical audible); over-temp neither (§3.6 C5).
        if (alarm !is OverTemperature) {
            snoozeIntent(alarm)?.let { pi ->
                builder.addAction(
                    Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Snooze ${snoozeMinutes()}m", pi).build(),
                )
            }
            if (alarm.isDismissable()) {
                dismissIntent(alarm)?.let { pi ->
                    builder.addAction(
                        Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Dismiss", pi).build(),
                    )
                }
            }
        }
        if (critical) {
            // Falls back to a heads-up banner when the screen is on or access is ungranted.
            fullScreenIntent()?.let { builder.setFullScreenIntent(it, true) }
        }
        nm.notify(tag, id, builder.build())
    }

    private fun vibrate(alarm: ActiveAlarm) {
        val cfg = actuatorConfig()
        val preset = if (alarm.severity == AlarmSeverity.CRITICAL) {
            cfg.criticalVibration
        } else {
            cfg.warningVibration
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
        is SignalLoss -> "Signal lost"
        is WeakSignal -> "Weak signal"
        is OverTemperature -> "Device too hot"
    }

    private companion object {
        const val ID_THRESHOLD = 4101
        const val ID_LOSS = 4102
        const val ID_OVERTEMP = 4103
        const val ID_WEAK = 4104
    }
}
