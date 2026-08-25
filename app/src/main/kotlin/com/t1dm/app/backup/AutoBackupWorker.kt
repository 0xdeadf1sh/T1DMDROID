package com.t1dm.app.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.t1dm.app.T1dmApplication
import com.t1dm.app.settings.SettingsStore
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** A failed pass returns `retry`, not `failure`: the usual causes are transient, and [BackupManager]
 *  has recorded the reason before the throwable arrives here. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as T1dmApplication).container
        // A run can outlive the setting that scheduled it: declining is not a failure, so not a retry.
        if (container.settingsStore.currentBackupCadenceHours() == SettingsStore.BACKUP_CADENCE_OFF) {
            cancel(applicationContext)
            return Result.success()
        }
        if (container.settingsStore.currentBackupFolder() == null) return Result.success()

        return runCatching {
            val run = container.backupManager.runBackup()
            Timber.tag(TAG).i("wrote %s (%d rows, %d pruned)", run.file.name, run.counts.total, run.pruned)
            Result.success()
        }.getOrElse {
            Timber.tag(TAG).w(it, "automatic backup failed")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Backup"
        private const val NAME = "t1dm.backup.auto"

        /** `UPDATE`, not `KEEP`: the period is the thing being configured, and `KEEP` would leave a
         *  cadence change with no effect until the existing schedule was cancelled. */
        fun sync(context: Context, cadenceHours: Int) {
            val wm = WorkManager.getInstance(context)
            if (cadenceHours == SettingsStore.BACKUP_CADENCE_OFF) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                cadenceHours.toLong(),
                TimeUnit.HOURS,
            ).setConstraints(
                Constraints.Builder().setRequiresBatteryNotLow(true).build(),
            ).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
