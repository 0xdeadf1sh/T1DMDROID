package com.t1dm.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** [WidgetPinner.request] carries no failure signal; this is the only positive acknowledgement. */
class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Toast.makeText(context, "Widget added", Toast.LENGTH_SHORT).show()
    }
}
