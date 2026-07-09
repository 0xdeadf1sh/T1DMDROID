package com.t1dm.app.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.UnitSpace

/**
 * Builds the always-on ongoing notification (item 15): the live BG read-out that replaces the static
 * "monitoring active" text on the foreground-service notification. The title carries current BG +
 * trend arrow (in the active unit); the body carries the last-updated age and a §3.6-GATED
 * predictive line.
 *
 * The gate lives entirely in [BgGlance] (computed by [BgGlanceComputer]): a predictive countdown is
 * shown ONLY when [BgGlance.approaching] is non-null, which the computer produces only for an
 * eligible forecast. When ineligible the body degrades to BG + trend plus an honest
 * "collecting context" / "forecast unavailable" — never a fabricated ETA. This surface is purely
 * additive to the deterministic alarm and never gates it.
 */
class LiveNotificationPresenter(
    context: Context,
    private val channelId: String,
    private val contentIntent: PendingIntent?,
) {
    private val app = context.applicationContext

    fun build(glance: BgGlance, unit: UnitSpace): Notification {
        val title = titleLine(glance, unit)
        val body = bodyLine(glance)
        return Notification.Builder(app, channelId)
            .setSmallIcon(iconFor(glance))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true) // updates in place, never re-buzzes; the alarm path owns sound
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun titleLine(glance: BgGlance, unit: UnitSpace): String {
        if (!glance.hasReading) return "No CGM reading yet"
        val v = BgFormat.value(glance.bgMgdl, unit)
        val arrow = BgFormat.arrow(glance.trend)
        return "$v $arrow ${BgFormat.unitLabel(unit)}"
    }

    private fun bodyLine(glance: BgGlance): String {
        val age = when {
            glance.signalLoss -> "Signal lost — last reading ${BgFormat.age(glance.readingAgeMs)}"
            !glance.hasReading -> "Scanning for CGM"
            else -> "Updated ${BgFormat.age(glance.readingAgeMs)}"
        }
        val forecast = when {
            glance.approaching != null -> BgFormat.crossingLine(glance.approaching)
            glance.warmup -> "Collecting context"
            glance.forecastEligible && glance.fcEndMgdl != null -> "Forecast: ${glance.summary}"
            glance.forecastUnavailable -> "Forecast unavailable"
            else -> null
        }
        return if (forecast == null) age else "$age\n$forecast"
    }

    private fun iconFor(glance: BgGlance): Int = when {
        glance.signalLoss -> android.R.drawable.stat_notify_error
        glance.band == AlertBand.URGENT_LOW || glance.band == AlertBand.URGENT_HIGH ->
            android.R.drawable.stat_sys_warning
        glance.approaching?.severity == PredictiveCrossing.Severity.CRITICAL ->
            android.R.drawable.stat_sys_warning
        else -> android.R.drawable.stat_sys_data_bluetooth
    }
}
