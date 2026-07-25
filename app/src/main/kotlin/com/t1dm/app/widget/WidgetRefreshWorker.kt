package com.t1dm.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The belt to the foreground service's braces. Every live widget render is driven by
 * [com.t1dm.app.service.CgmScanService]'s glance pipeline, and the provider declares
 * `updatePeriodMillis="0"` — so nothing in the platform solicits a repaint. If HyperOS reaps the
 * service, or its BLE-permission gate `stopSelf()`s it before the pipeline starts, the tile freezes on
 * whatever it last showed (or, straight after a reboot, on the host's loading spinner) with no path
 * back. This job re-pushes at WorkManager's 15-minute floor so the worst case is a quarter-hour of
 * staleness rather than an indefinite one.
 *
 * It is NOT a substitute for the service: the same Doze/standby throttling that stops a reaped service
 * from being restarted also defers this, and every Glance composition already runs inside a WorkManager
 * session worker anyway. It is the cheapest available second path, not a guarantee.
 *
 * Battery cost is deliberately near-zero: no constraints (so it never holds a wakeup waiting for one),
 * no network, no wakelock, and the work itself is one `updateAll` — which merely starts the Glance
 * session. A throw yields `success` rather than `failure`, because a periodic worker that fails is
 * cancelled outright and would take the fallback down with it.
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { GlucoseWidget().updateAll(applicationContext) }
            .onFailure { Timber.tag(TAG).w(it, "periodic widget refresh failed") }
        return Result.success()
    }

    companion object {
        private const val TAG = "GlucoseWidget"
        private const val NAME = "t1dm.widget.refresh"

        /**
         * Idempotent; called from [com.t1dm.app.T1dmApplication], [com.t1dm.app.service.BootReceiver]
         * and [GlucoseWidgetReceiver.onEnabled]. KEEP matches the two existing unique periodic jobs
         * (CgmWatchdog, SyncDrainWorker) — note it also means a later change to the request shape needs
         * UPDATE to reach installs that already hold one.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
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
