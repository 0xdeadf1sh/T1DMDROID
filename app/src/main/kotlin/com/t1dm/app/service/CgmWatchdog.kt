package com.t1dm.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Periodic nudge to restart [CgmScanService] after a Doze/HyperOS kill (§2.3). A background
 * foreground-service start can be refused on 14+, so the restart is best-effort; §3.6-A's
 * loss-of-signal alarm covers the kill window.
 */
class CgmWatchdog(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { CgmScanService.start(applicationContext) }
            .onFailure { Timber.tag("CgmScan").w(it, "watchdog restart refused (background FGS start)") }
        return Result.success()
    }

    companion object {
        private const val NAME = "t1dm.cgm.watchdog"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<CgmWatchdog>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
