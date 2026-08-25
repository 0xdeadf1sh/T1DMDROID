package com.t1dm.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import timber.log.Timber

object WidgetPinner {

    enum class Widget(val label: String, val receiver: Class<*>) {
        GLUCOSE("glucose", GlucoseWidgetReceiver::class.java),
    }

    fun isSupported(context: Context): Boolean =
        runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }
            .getOrDefault(false)

    /** True iff the system accepted the request; false when unsupported or the call failed. */
    fun request(context: Context, widget: Widget): Boolean {
        val awm = AppWidgetManager.getInstance(context)
        if (!runCatching { awm.isRequestPinAppWidgetSupported }.getOrDefault(false)) return false
        val provider = ComponentName(context, widget.receiver)
        // Fired only on a confirmed pin; a swallowed request (HyperOS) never calls back.
        val callback = PendingIntent.getBroadcast(
            context,
            widget.ordinal,
            Intent(context, WidgetPinnedReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return runCatching { awm.requestPinAppWidget(provider, null, callback) }
            .onFailure { Timber.w(it, "requestPinAppWidget(%s) failed", widget.label) }
            .getOrDefault(false)
    }
}
