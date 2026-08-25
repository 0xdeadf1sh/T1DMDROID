package com.t1dm.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import timber.log.Timber

/** Re-presents an already-active deterministic alarm; it never decides one. */
class AlertRepeatScheduler(context: Context) {
    private val app = context.applicationContext
    private val am = app.getSystemService(AlarmManager::class.java)

    fun schedule(cadenceMin: Int) {
        val triggerAt = SystemClock.elapsedRealtime() + cadenceMin.coerceAtLeast(1) * 60_000L
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent())
        }.onFailure { Timber.w(it, "AlertRepeatScheduler: exact alarm schedule failed") }
    }

    fun cancel() {
        runCatching { am.cancel(pendingIntent()) }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(app, AlertRepeatReceiver::class.java).setAction(ACTION_ALERT_REPEAT)
        return PendingIntent.getBroadcast(
            app, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_ALERT_REPEAT = "com.t1dm.app.ALERT_REPEAT"
    }
}
