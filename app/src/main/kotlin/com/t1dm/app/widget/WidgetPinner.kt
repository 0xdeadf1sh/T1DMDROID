package com.t1dm.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import timber.log.Timber

/**
 * Drives the launcher's "pin this widget to the home screen" flow from inside the app (issue I8): the
 * three Glance widgets are otherwise only discoverable via the launcher's long-press → Widgets picker,
 * which users could not find. On a launcher that supports it, [request] asks the system to show its
 * own pin-confirmation dialog for a specific provider; where unsupported, the caller falls back to
 * printing the manual long-press instructions.
 */
object WidgetPinner {

    /** The three pinnable Glance widgets, each mapped to its [android.appwidget.AppWidgetProvider]. */
    enum class Widget(val label: String, val receiver: Class<*>) {
        BG_TILE("BG tile", BgTileWidgetReceiver::class.java),
        PREDICTION("Prediction glance", PredictionGlanceWidgetReceiver::class.java),
        LOCK("Lock-screen glance", LockGlanceWidgetReceiver::class.java),
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
        return runCatching { awm.requestPinAppWidget(provider, null, null) }
            .onFailure { Timber.w(it, "requestPinAppWidget(%s) failed", widget.label) }
            .getOrDefault(false)
    }
}
