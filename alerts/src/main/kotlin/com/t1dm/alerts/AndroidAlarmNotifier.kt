package com.t1dm.alerts

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import com.t1dm.core.model.AlertBand

/**
 * Android emission for the deterministic alarm (SPEC.private.md §3.6-A + Phase-7 alert polish). Two
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
    /**
     * The per-theme small icon (issue I1), supplied by `:app` (this module cannot reach `:core:design`
     * or `:app`'s `R`). `critical` is true for the URGENT tier, so `:app` can hand back a DISTINCT,
     * unmistakable alarm glyph for urgent alarms vs the plain-tier warning. Presentation only — this
     * never changes WHEN the pure engine fires (§3.6-A). Defaults to null → the platform warning icon.
     */
    private val smallIcon: (critical: Boolean) -> android.graphics.drawable.Icon? = { null },
    /** The active theme accent (ARGB) for `setColor`; null ⇒ leave unset. */
    private val accentColor: () -> Int? = { null },
    /**
     * Advisory suppression gate (DEATH mode): when true this notifier PRESENTS nothing — the pure
     * [AlarmEngine] still fires (§3.6-A computes), we just don't announce it. Presentation only; this
     * never changes WHEN the engine fires. Defaults to never-suppressed for tests/headless contexts.
     */
    private val suppressed: () -> Boolean = { false },
    /**
     * Minimum interval (ms) between an alarm's SOUND+VIBRATION actuations while it stays in the same
     * band — so a once-a-minute reading stream that keeps re-emitting the same alarm re-announces at
     * most this often. A NEW band/severity always actuates at once; the notification text still updates
     * silently in between. Read live so a Settings change takes effect without rebuilding the notifier.
     * Defaults to 0 (no throttle) for tests / headless contexts.
     */
    private val minActuationIntervalMs: () -> Long = { 0L },
    private val clock: () -> Long = System::currentTimeMillis,
) : AlarmNotifier {

    private val app = context.applicationContext
    private val nm = app.getSystemService(android.app.NotificationManager::class.java)
    private val vibrations = VibrationActuator(app)
    private val channels = AlertChannels.ensure(app, actuatorConfig)

    // Throttle state: when the primary alarm last actuated (sound+buzz) and for which episode.
    @Volatile private var lastActuateMs = Long.MIN_VALUE
    @Volatile private var lastEpisodeKey: String? = null

    override fun emit(state: AlarmState) {
        // D4: DEATH mode suppresses the glucose/signal alarms but the over-temperature alert is
        // EXEMPT — a phone cooking itself must still say so. So when suppressed we present ONLY the
        // over-temp alarm (and cancel the glucose/signal ids), and drive the throttle off it alone.
        val sup = suppressed()
        val presentable = if (sup) state.overTemperature else state.primary
        val key = presentable?.let { episodeKey(it) }
        val now = clock()
        // A new band/severity actuates immediately; otherwise honour the min-interval throttle so an
        // every-minute reading stream does not re-sound/re-buzz each tick.
        val actuate = presentable != null &&
            (key != lastEpisodeKey || now - lastActuateMs >= minActuationIntervalMs().coerceAtLeast(0L))
        // `alertOnce = !actuate` turns a throttled re-post into a SILENT content update (no sound/heads-up).
        if (sup) {
            nm.cancel("glucose", ID_THRESHOLD)
            nm.cancel("signal", ID_LOSS)
        } else {
            state.threshold?.let { post(ID_THRESHOLD, "glucose", it, !actuate) } ?: nm.cancel("glucose", ID_THRESHOLD)
            state.signalLoss?.let { post(ID_LOSS, "signal", it, !actuate) } ?: nm.cancel("signal", ID_LOSS)
        }
        state.overTemperature?.let { post(ID_OVERTEMP, "device", it, !actuate) } ?: nm.cancel("device", ID_OVERTEMP)
        if (actuate) {
            presentable?.let { vibrate(it) }
            lastActuateMs = now
        }
        lastEpisodeKey = key
    }

    override fun reAlert(state: AlarmState) {
        // Same D4 exemption: in DEATH only the over-temp alarm may re-announce.
        val target = if (suppressed()) state.overTemperature else state.primary
        target?.takeIf { it.severity == AlarmSeverity.CRITICAL }?.let {
            vibrate(it)
            lastActuateMs = clock()
        }
    }

    override fun clear() {
        nm.cancel("glucose", ID_THRESHOLD)
        nm.cancel("signal", ID_LOSS)
        nm.cancel("device", ID_OVERTEMP)
        lastEpisodeKey = null
    }

    /** A stable identity for the current alarm episode — a change (band or severity) actuates at once. */
    private fun episodeKey(alarm: ActiveAlarm): String = when (alarm) {
        is ThresholdBreach -> "t:${alarm.band}:${alarm.severity}"
        is SignalLoss -> "s:${alarm.severity}"
        is OverTemperature -> "d:${alarm.severity}"
    }

    private fun post(id: Int, tag: String, alarm: ActiveAlarm, alertOnce: Boolean) {
        if (!nm.areNotificationsEnabled()) return
        val critical = alarm.severity == AlarmSeverity.CRITICAL
        // Over-temp rides its own device channels (never DND-bypass); glucose/signal share the two
        // glucose-tier channels.
        val channel = when (alarm) {
            is OverTemperature -> if (critical) channels.deviceCritical else channels.device
            else -> if (critical) channels.critical else channels.warning
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
        if (critical) {
            // Full-screen over the lock screen for urgent tiers (item 2 / risk S11). Android falls
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
        is OverTemperature -> "Device temperature high"
    }

    private companion object {
        const val ID_THRESHOLD = 4101
        const val ID_LOSS = 4102
        const val ID_OVERTEMP = 4103
    }
}
