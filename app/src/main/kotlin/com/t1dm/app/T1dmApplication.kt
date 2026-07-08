package com.t1dm.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.t1dm.app.di.AppContainer
import com.t1dm.app.service.CgmWatchdog
import com.t1dm.app.sync.SyncDrainWorker
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
        container.startInference()
        container.startBuilders() // seed the bundled glycemic dictionary + insulin presets (off-main, idempotent)
        CgmWatchdog.enqueue(this)
        SyncDrainWorker.enqueue(this) // deferrable outbox drain fallback (FGS drains opportunistically)
    }

    private fun installStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectCustomSlowCalls()
                .detectNetwork()
                .penaltyLog()
                .penaltyFlashScreen()
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
