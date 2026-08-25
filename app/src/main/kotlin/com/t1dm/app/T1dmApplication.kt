package com.t1dm.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.t1dm.app.backup.AutoBackupWorker
import com.t1dm.app.di.AppContainer
import com.t1dm.app.service.CgmWatchdog
import com.t1dm.app.sync.SyncDrainWorker
import com.t1dm.app.widget.WidgetRefreshWorker
import kotlinx.coroutines.launch
import timber.log.Timber

class T1dmApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) {
            Timber.plant(Timber.DebugTree())
            installStrictMode()
        }

        container = AppContainer(this)
        // Before any UI: the launcher repair cannot presuppose the user could still launch us.
        RetiredThemeMigration.run(this)
        container.startInference()
        container.startBuilders() // off-main, idempotent
        CgmWatchdog.enqueue(this)
        SyncDrainWorker.enqueue(this) // fallback; the FGS drains opportunistically
        WidgetRefreshWorker.enqueue(this) // fallback; the FGS is the only live driver
        // Reconciled every start, not only on edit: an upgrade or a force stop cancels pending work.
        container.appScope.launch {
            AutoBackupWorker.sync(this@T1dmApplication, container.settingsStore.currentBackupCadenceHours())
        }
    }

    private fun installStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectCustomSlowCalls()
                .detectNetwork()
                // No penaltyFlashScreen: its red border on a main-thread kv touch was the press-flash.
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build(),
        )
    }
}
