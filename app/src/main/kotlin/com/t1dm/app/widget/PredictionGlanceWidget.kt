package com.t1dm.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.t1dm.app.MainActivity
import com.t1dm.app.notify.BgFormat

/**
 * The forecast-glance widget (ux-decisions.md — "a prediction glance"). Shows the current BG line
 * plus a §3.6-GATED forecast read-out: the "approaching …" countdown when the selected forecast is
 * eligible, an honest "collecting context" / "forecast unavailable" otherwise — never a fabricated
 * ETA. Refreshes from the ingest cycle without an Activity.
 */
class PredictionGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = currentWidgetSnapshot(context)
        provideContent {
            GlanceTheme(colors = T1dmGlanceColors) { Content(snap) }
        }
    }

    @Composable
    private fun Content(snap: WidgetSnapshot) {
        val g = snap.glance
        val forecast = when {
            g.approaching != null -> BgFormat.crossingLine(g.approaching)
            g.warmup -> "Collecting context"
            g.forecastEligible && g.fcEndMgdl != null -> g.summary
            else -> "Forecast unavailable"
        }
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp)
                .clickable(actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${BgFormat.value(g.bgMgdl, snap.unit)} ${BgFormat.arrow(g.trend)}",
                    style = TextStyle(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
                )
                Text(
                    text = "  ${BgFormat.unitLabel(snap.unit)}",
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
            Text(
                text = forecast,
                style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
            )
            Text(
                text = if (g.signalLoss) "signal lost" else BgFormat.age(g.readingAgeMs),
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

class PredictionGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PredictionGlanceWidget()
}
