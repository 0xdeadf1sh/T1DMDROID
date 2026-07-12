package com.t1dm.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Drives the launcher's "pin this widget to the home screen" flow from inside the app (issue I8): the
 * Glance widget is otherwise only discoverable via the launcher's long-press → Widgets picker, which on
 * HyperOS buries (or omits) third-party widgets. On a launcher that supports it, [request] asks the
 * system to show its own pin-confirmation dialog for the provider; where unsupported, the caller falls
 * back to printing the manual long-press instructions.
 */
object WidgetPinner {

    /** The pinnable widget(s), each mapped to its [android.appwidget.AppWidgetProvider]. */
    enum class Widget(val label: String, val receiver: Class<*>) {
        GLUCOSE("glucose", GlucoseWidgetReceiver::class.java),
    }

    /** True iff the current launcher supports the in-app pin-request dialog. */
    fun isSupported(context: Context): Boolean =
        runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }
            .getOrDefault(false)

    /**
     * Ask the launcher to offer pinning [widget]. Returns true iff the request was accepted by the
     * system (a launcher dialog will appear); false when unsupported or the call failed, so the UI can
     * show the manual fallback instructions.
     */
    fun request(context: Context, widget: Widget): Boolean {
        val awm = AppWidgetManager.getInstance(context)
        if (!runCatching { awm.isRequestPinAppWidgetSupported }.getOrDefault(false)) return false
        val provider = ComponentName(context, widget.receiver)
        // The launcher fires this only on a confirmed pin; a swallowed request (HyperOS) never calls back.
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
