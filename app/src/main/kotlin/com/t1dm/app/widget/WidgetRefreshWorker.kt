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

/** Backstop for a reaped FGS (provider has no periodic update); re-pushes at WM's 15-min floor. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { GlucoseWidget().updateAll(applicationContext) }
            .onFailure { Timber.tag(TAG).w(it, "periodic widget refresh failed") }
        return Result.success()
    }

    companion object {
        private const val TAG = "GlucoseWidget"
        private const val NAME = "t1dm.widget.refresh"

        /** KEEP: a later shape change needs UPDATE to reach installs already holding one. */
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
