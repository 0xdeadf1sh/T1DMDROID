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

/**
 * The unattended backup: one archive to the granted folder on the configured cadence, then the
 * retention sweep.
 *
 * No network constraint — the destination is local storage — but it does require the battery not to
 * be low, since walking the whole keep-forever store and deflating it is the most expensive thing
 * this app does outside inference, and it is never urgent.
 *
 * A failed pass returns `retry` rather than `failure` so WorkManager reschedules with its own
 * backoff: the usual causes (a folder the user has moved, a provider that is momentarily
 * unavailable) are transient or, if they are not, are already on the panel — [BackupManager] has
 * recorded the reason before this ever sees the throwable.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as T1dmApplication).container
        // The schedule outlives any single setting change, so a run that arrives after the user
        // turned automatic backup off — or revoked the folder — must decline rather than write. It
        // is not a failure and must not be retried.
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

        /**
         * Bring the schedule into line with the cadence setting. Idempotent, and safe to call on
         * every app start as well as on every edit.
         *
         * `UPDATE` rather than `KEEP`: the period is the thing being configured, and `KEEP` would
         * leave a cadence change with no effect until the existing schedule happened to be
         * cancelled — a setting that appears to take and silently does not.
         */
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
