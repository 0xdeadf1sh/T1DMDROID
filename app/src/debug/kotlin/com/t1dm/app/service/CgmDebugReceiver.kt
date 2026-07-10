package com.t1dm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Debug-only bridge for the sensor-free exit-criteria checks (Phase 1 verify).
 * HyperOS refuses `am start-foreground-service` against a non-exported service, so this exported
 * receiver — present ONLY in the debug build (see `src/debug/AndroidManifest.xml`) — takes an
 * `adb shell am broadcast` and forwards its action/extras to [CgmScanService], which stays
 * non-exported in every build. It runs in the app's own UID, so starting its own service is allowed.
 *
 * Never shipped: the whole receiver lives in the debug source set.
 */
class CgmDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val forward = Intent(context, CgmScanService::class.java)
            .setAction(action)
            .putExtras(intent.extras ?: Bundle())
        context.startForegroundService(forward)
    }
}
