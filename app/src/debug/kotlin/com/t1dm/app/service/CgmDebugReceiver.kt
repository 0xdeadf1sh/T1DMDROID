package com.t1dm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Exported, unlike [CgmScanService]: HyperOS refuses `am start-foreground-service` against a
 * non-exported service, so an `adb shell am broadcast` is forwarded here instead.
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
