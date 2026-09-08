package com.t1dm.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.t1dm.app.service.CgmScanService
import timber.log.Timber

/** Forwards Snooze/Dismiss; presentation only (§3.6 C4), escalation still pierces (C1-C3). */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_ALARM_SNOOZE && action != ACTION_ALARM_DISMISS) return
        val kind = intent.getStringExtra(EXTRA_ALARM_KIND)
        Timber.tag("CgmScan").d("ALARM_ACTION %s kind=%s", action, kind)
        val forward = Intent(context, CgmScanService::class.java)
            .setAction(action)
            .putExtra(EXTRA_ALARM_KIND, kind)
        runCatching { context.startForegroundService(forward) }
            .onFailure { Timber.tag("CgmScan").w(it, "alarm-action forward failed") }
    }

    companion object {
        const val ACTION_ALARM_SNOOZE = "com.t1dm.app.ALARM_SNOOZE"
        const val ACTION_ALARM_DISMISS = "com.t1dm.app.ALARM_DISMISS"
        const val EXTRA_ALARM_KIND = "alarmKind"
    }
}
