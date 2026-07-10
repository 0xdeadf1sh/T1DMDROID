package com.t1dm.app.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import com.t1dm.core.design.IconStyle
import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.UnitSpace
import kotlin.math.roundToInt

/**
 * Builds the always-on ongoing notification (item 15): the live BG read-out that replaces the static
 * "monitoring active" text on the foreground-service notification. The title carries current BG +
 * trend arrow (in the active unit); the body carries the last-updated age, a §3.6-GATED predictive
 * line, and — when the model build carries a time head — the model's predicted CIRCADIAN time (I7).
 *
 * The predictive gate lives entirely in [BgGlance] (computed by [BgGlanceComputer]): a predictive
 * countdown is shown ONLY when [BgGlance.approaching] is non-null, which the computer produces only
 * for an eligible forecast. When ineligible the body degrades to BG + trend plus an honest
 * "collecting context" / "forecast unavailable" — never a fabricated ETA. The circadian line is
 * INDEPENDENT of that gate (it is a phase belief, not a glucose forecast, and drives no alert); it is
 * shown only when [InferenceState.selectedPredictedTime] is non-null, and omitted silently otherwise.
 *
 * The small icon geometry + accent swatch follow the active theme (issue I1): the caller passes the
 * resolved [IconStyle] and accent ARGB, since this runs outside Compose.
 */
class LiveNotificationPresenter(
    context: Context,
    private val channelId: String,
    private val contentIntent: PendingIntent?,
) {
    private val app = context.applicationContext

    fun build(
        glance: BgGlance,
        unit: UnitSpace,
        style: IconStyle,
        accentArgb: Int,
        predictedTime: PredictedTime?,
    ): Notification {
        val title = titleLine(glance, unit)
        val body = bodyLine(glance, predictedTime)
        return Notification.Builder(app, channelId)
            .setSmallIcon(NotificationIcons.res(iconGlyphFor(glance), style))
            .setColor(accentArgb)
            .setColorized(false)
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

    private fun bodyLine(glance: BgGlance, predictedTime: PredictedTime?): String {
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
        val circadian = predictedTime?.let { circadianLine(it) }
        return listOfNotNull(age, forecast, circadian).joinToString("\n")
    }

    /** The model's circadian-phase belief as a clock read-out. Not gated — a phase belief, not a
     *  glucose forecast; shown only when the model build actually carries a decoded time head. */
    private fun circadianLine(t: PredictedTime): String {
        val hf = ((t.predictedHour % 24.0) + 24.0) % 24.0
        val h = hf.toInt()
        val m = ((hf - h) * 60.0).toInt().coerceIn(0, 59)
        val conf = (t.resultantR.coerceIn(0.0, 1.0) * 100.0).roundToInt()
        return "Model clock ~%02d:%02d (%d%%)".format(h, m, conf)
    }

    private fun iconGlyphFor(glance: BgGlance): NotificationIcons.Glyph = when {
        glance.signalLoss -> NotificationIcons.Glyph.SIGNAL_LOSS
        glance.band == AlertBand.URGENT_LOW || glance.band == AlertBand.URGENT_HIGH ->
            NotificationIcons.Glyph.WARNING
        glance.approaching?.severity == PredictiveCrossing.Severity.CRITICAL ->
            NotificationIcons.Glyph.WARNING
        else -> NotificationIcons.Glyph.MONITOR
    }
}
