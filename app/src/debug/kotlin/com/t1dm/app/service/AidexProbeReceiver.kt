package com.t1dm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** HyperOS refuses am start-foreground-service on components; adb broadcast starts the FGS. */
class AidexProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AidexProbeService.ACTION_PROBE_START,
            AidexProbeService.ACTION_PROBE_BOND,
            AidexProbeService.ACTION_PROBE_HANDSHAKE,
            AidexProbeService.ACTION_PROBE_BRINGUP,
            AidexProbeService.ACTION_PROBE_ACTIVATE,
            AidexProbeService.ACTION_PROBE_REALTIME,
            AidexProbeService.ACTION_PROBE_HISTORY,
            AidexProbeService.ACTION_PROBE_STOP -> {
                val forward = Intent(context, AidexProbeService::class.java)
                    .setAction(intent.action)
                    .putExtras(intent.extras ?: Bundle())
                context.startForegroundService(forward)
            }
        }
    }
}
