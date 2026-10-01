package com.t1dm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.t1dm.app.widget.GlucoseWidget
import com.t1dm.app.widget.WidgetRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/** No RemoteViews persist across reboot; updatePeriodMillis=0 means widget needs a manual push. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when {
            resumesMonitoring(intent.action) -> resumeMonitoring(context)
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED -> armUnlockResume(context)
        }
    }
}

/** MY_PACKAGE_REPLACED: an update kills the process and restarts no sticky service. */
internal fun resumesMonitoring(action: String?): Boolean = action != null && action in RESUME_ACTIONS

private val RESUME_ACTIONS = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_USER_UNLOCKED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
)

/** An extension: `goAsync` is only legal on the receiver currently inside `onReceive`. */
private fun BroadcastReceiver.resumeMonitoring(context: Context) {
    Timber.tag("CgmScan").i("boot/unlock/update — restarting CgmScanService")
    runCatching { CgmScanService.start(context) }
        .onFailure { Timber.tag("CgmScan").w(it, "boot restart failed") }
    CgmWatchdog.enqueue(context)
    WidgetRefreshWorker.enqueue(context)

    // Suspending IO, so goAsync rather than a launch the system may kill mid-flight.
    val pending = goAsync()
    val app = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            GlucoseWidget().updateAll(app)
        } catch (t: Throwable) {
            Timber.tag("GlucoseWidget").w(t, "boot widget refresh failed")
        } finally {
            pending.finish()
        }
    }
}

// Unreached: LOCKED_BOOT_COMPLETED never fires here, this receiver is not directBootAware.
private fun armUnlockResume(context: Context) {
    val app = context.applicationContext
    val onUnlock = object : BroadcastReceiver() {
        override fun onReceive(unlockedContext: Context, unlockedIntent: Intent) {
            runCatching { app.unregisterReceiver(this) }
            resumeMonitoring(app)
        }
    }
    runCatching {
        ContextCompat.registerReceiver(
            app,
            onUnlock,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }.onFailure { Timber.tag("CgmScan").w(it, "could not arm the unlock resume") }
}
