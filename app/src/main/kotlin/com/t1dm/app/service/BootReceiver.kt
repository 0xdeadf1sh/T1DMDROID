package com.t1dm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Resumes the passive monitor after a reboot (Phase 1 exit criteria — "a forced
 * reboot leaves a bounded gap and the service auto-restarts"). The service is typed
 * `connectedDevice`, which IS permitted to start from `BOOT_COMPLETED` — unlike `dataSync`, which
 * Android 15+ forbids here (a further reason the service dropped the `dataSync` type).
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
