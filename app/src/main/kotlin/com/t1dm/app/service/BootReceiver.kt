package com.t1dm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Resumes the passive monitor after a reboot (PLAN.private.md Phase 1 exit criteria — "a forced
 * reboot leaves a bounded gap and the service auto-restarts"). Starting a
 * `connectedDevice|dataSync` foreground service from `BOOT_COMPLETED` is permitted on 14+.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        Timber.tag("CgmScan").i("BOOT_COMPLETED — restarting CgmScanService")
        runCatching { CgmScanService.start(context) }
            .onFailure { Timber.tag("CgmScan").w(it, "boot restart failed") }
        CgmWatchdog.enqueue(context)
    }
}
