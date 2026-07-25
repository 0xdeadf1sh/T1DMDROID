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

/**
 * Resumes the passive monitor after a reboot (Phase 1 exit criteria — "a forced
 * reboot leaves a bounded gap and the service auto-restarts"). The service is typed
 * `connectedDevice`, which IS permitted to start from `BOOT_COMPLETED` — unlike `dataSync`, which
 * Android 15+ forbids here (a further reason the service dropped the `dataSync` type).
 *
 * It also repaints the widget, which needs its own push for a reason the service does not: the
 * AppWidget host does not persist RemoteViews across a reboot and the provider declares
 * `updatePeriodMillis="0"`, so no `APPWIDGET_UPDATE` is ever broadcast — the tile sits on its
 * `initialLayout` spinner until this app pushes. Leaving that to the service alone couples the widget
 * to the BLE-permission gate in [CgmScanService.onCreate], which `stopSelf()`s before the pipeline
 * starts and would strand the tile on the spinner indefinitely.
 *
 * This receiver is deliberately NOT `android:directBootAware`, so it is inert until the first unlock
 * and only ever sees `BOOT_COMPLETED` today. That is intentional: `AppContainer` touches
 * credential-encrypted and external storage during construction (Room, `modelsDir`), so a process
 * started inside the direct-boot window would fail before any of this ran. The
 * `LOCKED_BOOT_COMPLETED` branch is therefore a correctness guard for the day something in the app
 * does become direct-boot aware — it touches nothing encrypted and simply hands off to the unlock.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Credential-encrypted storage is readable by here (see the class KDoc on why
            // BOOT_COMPLETED, not LOCKED_BOOT_COMPLETED, is our first delivery).
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED -> resumeMonitoring(context)
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> armUnlockResume(context)
            else -> return
        }
    }
}

/**
 * Restart the ingest path and repaint the tile. An extension on [BroadcastReceiver] rather than a
 * member because [BootReceiver] is not the only caller — the unlock hand-off below runs it from its
 * own receiver, and `goAsync` is only legal on the receiver currently inside `onReceive`.
 */
private fun BroadcastReceiver.resumeMonitoring(context: Context) {
    Timber.tag("CgmScan").i("boot/unlock — restarting CgmScanService")
    runCatching { CgmScanService.start(context) }
        .onFailure { Timber.tag("CgmScan").w(it, "boot restart failed") }
    CgmWatchdog.enqueue(context)
    WidgetRefreshWorker.enqueue(context)

    // `updateAll` only opens the Glance session (the composition itself runs later, in the session
    // worker), so this finishes well inside the receiver's budget — but it is still suspending IO,
    // hence goAsync rather than a fire-and-forget launch the system may kill mid-flight.
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

/**
 * Direct boot: every store this app owns is credential-encrypted, so there is nothing safe to do yet.
 * Register for the unlock instead — `ACTION_USER_UNLOCKED` is delivered to registered receivers only,
 * never to manifest ones, so it has to be armed from a process that is already running. Registration
 * itself touches no storage.
 */
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
