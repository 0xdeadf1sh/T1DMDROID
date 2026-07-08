package com.t1dm.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.t1dm.app.T1dmApplication
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The deferrable outbox drain (PLAN.private.md Phase 3 deliverable 7). It complements the
 * foreground-service opportunistic drain: when the FGS is dead or Doze has parked the app, this
 * WorkManager job still flushes the durable queue on the next network-available window. Requires
 * connectivity so it never wakes to a guaranteed transport failure; a thrown pass yields `retry` so
 * WorkManager reschedules with its own backoff.
 */
class SyncDrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as T1dmApplication).container
        return runCatching { container.syncManager.drainNow(); Result.success() }
            .getOrElse { Timber.tag(TAG).w(it, "worker drain failed"); Result.retry() }
    }

    companion object {
        private const val TAG = "QueueDrainer"
        private const val NAME = "t1dm.sync.drain"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncDrainWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
