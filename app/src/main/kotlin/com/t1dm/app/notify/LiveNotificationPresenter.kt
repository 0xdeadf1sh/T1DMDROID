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
 * The predictive line is gated in [BgGlanceComputer]: an ineligible forecast degrades to BG plus
 * trend, never a fabricated ETA. The circadian line is not gated — a phase belief, not a forecast.
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
            .setOnlyAlertOnce(true) // the alarm path owns sound
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun titleLine(glance: BgGlance, unit: UnitSpace): String {
        if (!glance.hasReading) return "No reading yet"
        val v = BgFormat.value(glance.bgMgdl, unit)
        val arrow = BgFormat.arrow(glance.trend).let { if (it.isEmpty()) "" else "$it " }
        return "${statusToken(glance)} · $v $arrow${BgFormat.unitLabel(unit)}"
    }

    /** Fail-closed: warmup or no eligible forecast reads VOID, never STABLE. Same derivation as
     *  Navigation.glycemicStatusOf and the lock widget. */
    private fun statusToken(glance: BgGlance): String = when {
        glance.warmup || glance.forecastUnavailable || !glance.forecastEligible -> "VOID"
        glance.approaching != null ->
            if (glance.approaching!!.kind == PredictiveCrossing.Kind.HYPO) "HYPO" else "HYPER"
        else -> "STABLE"
    }

    private fun bodyLine(glance: BgGlance, predictedTime: PredictedTime?): String {
        val age = when {
            glance.signalLoss -> "Signal lost ${BgFormat.ageShort(glance.readingAgeMs)}"
            !glance.hasReading -> "Scanning"
            else -> "Updated ${BgFormat.ageShort(glance.readingAgeMs)}"
        }
        val forecast = when {
            glance.approaching != null -> BgFormat.crossingLine(glance.approaching)
            glance.warmup -> "Collecting context"
            glance.forecastEligible && glance.fcEndMgdl != null -> "Forecast: ${glance.summary}"
            glance.forecastUnavailable -> "Forecast unavailable"
            else -> null
        }
        val circadian = predictedTime?.let { circadianLine(it) }
        return listOfNotNull(age, forecast, circadian, nextForecastLine()).joinToString("\n")
    }

    /** The model cycles on each 5-minute wall-clock boundary; this counts down to the next one. */
    private fun nextForecastLine(): String {
        val nowMs = System.currentTimeMillis()
        val nextMs = (nowMs / 300_000L + 1) * 300_000L
        val remSec = ((nextMs - nowMs).coerceAtLeast(0L) / 1000L).toInt()
        val m = remSec / 60
        val s = remSec % 60
        val rem = if (m >= 1) "${m}m ${s}s" else "${s}s"
        return "Next forecast in $rem"
    }

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
