package com.t1dm.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.t1dm.app.service.CgmScanService
import timber.log.Timber

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
