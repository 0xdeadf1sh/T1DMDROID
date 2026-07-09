package com.t1dm.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.t1dm.app.service.CgmScanService
import timber.log.Timber

/**
 * Receives the exact-alarm repeat tick ([AlertRepeatScheduler]) and hands it to the foreground
 * service, which re-announces a still-active CRITICAL alarm and re-arms the next tick. Explicit,
 * app-internal, non-exported — the AlarmManager PendingIntent targets it directly.
 */
class AlertRepeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlertRepeatScheduler.ACTION_ALERT_REPEAT) return
        Timber.tag("CgmScan").d("ALERT_REPEAT tick")
        val forward = Intent(context, CgmScanService::class.java)
            .setAction(CgmScanService.ACTION_ALERT_REPEAT)
        runCatching { context.startForegroundService(forward) }
            .onFailure { Timber.tag("CgmScan").w(it, "alert-repeat forward failed") }
    }
}
