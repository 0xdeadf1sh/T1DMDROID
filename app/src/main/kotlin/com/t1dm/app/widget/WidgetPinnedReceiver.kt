package com.t1dm.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Success callback for [WidgetPinner.request]: the launcher fires this once the user confirms the pin
 * dialog. On HyperOS the pin request is otherwise a black box — no failure signal — so this is the one
 * positive acknowledgement we get, surfaced as a toast so the user knows the tap actually landed.
 */
class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Toast.makeText(context, "Widget added to your home screen", Toast.LENGTH_SHORT).show()
    }
}
