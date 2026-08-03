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

    /** The manual composition root; everything long-lived hangs off this. */
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
        // Before any UI: a build can retire a theme, and both the persisted id and the launcher alias
        // it selected outlive that. Every wake-up path lands here, which is the point — the launcher
        // repair cannot presuppose the user could still launch us.
        RetiredThemeMigration.run(this)
        container.startInference()
        container.startBuilders() // seed the bundled glycemic dictionary + insulin presets (off-main, idempotent)
        CgmWatchdog.enqueue(this)
        SyncDrainWorker.enqueue(this) // deferrable outbox drain fallback (FGS drains opportunistically)
        WidgetRefreshWorker.enqueue(this) // widget repaint fallback (the FGS is the only live driver)
        // The automatic-backup schedule is reconciled with its setting on every start, not only when
        // the setting is edited: an app upgrade or a "force stop" cancels pending work, and a backup
        // schedule that has quietly stopped is precisely the silence this feature must not have.
        // Off-main — reading the cadence is a kv hit.
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
                // No penaltyFlashScreen: its full-window red border (fired on a main-thread disk
                // read/write, e.g. a theme/font/unit KV touch) was the "red press-flash". Violations
                // still surface via penaltyLog — the diagnostic stays, the red frame goes.
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
