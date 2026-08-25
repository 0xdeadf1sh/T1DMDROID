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
 * The provider declares `updatePeriodMillis="0"` and every live render is driven by the foreground
 * service, so a reaped service freezes the tile with no path back; this re-pushes at WorkManager's
 * 15-minute floor. Doze defers it too, so it is a second path, not a guarantee. A throw yields
 * `success` because a periodic worker that fails is cancelled outright.
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

        /** KEEP means a later change to the request shape needs UPDATE to reach installs that hold one. */
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
