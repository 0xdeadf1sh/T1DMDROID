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
 * The deferrable watchdog (§2.3 — "WorkManager watchdog"). It periodically nudges
 * [CgmScanService] back up should HyperOS/Doze have killed it out from under `START_STICKY` and
 * `onTaskRemoved`. Starting a foreground service from a background Worker can be refused on 14+, so
 * the restart is best-effort and swallowed — the model-free loss-of-signal alarm still covers any
 * kill window (§3.6-A).
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
