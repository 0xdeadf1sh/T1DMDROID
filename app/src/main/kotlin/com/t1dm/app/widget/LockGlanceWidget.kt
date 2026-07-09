package com.t1dm.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.t1dm.app.notify.BgFormat

/**
 * The compact lock-screen glance (ux-decisions.md). A single dense row: BG + arrow + a terse
 * forecast tail. On Android 12+ true lock-screen widgets are limited, so this is the same Glance
 * provider sized for a minimal footprint; place it wherever the OEM surfaces app-widgets on the
 * keyguard. Full lock-surface theming lands in 7D.
 */
class LockGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = currentWidgetSnapshot(context)
        provideContent {
            GlanceTheme(colors = T1dmGlanceColors) { Content(snap) }
        }
    }

    @Composable
    private fun Content(snap: WidgetSnapshot) {
        val g = snap.glance
        val tail = when {
            g.approaching != null -> BgFormat.crossingLine(g.approaching)
            g.forecastEligible && g.fcEndMgdl != null -> g.summary
            g.warmup -> "collecting"
            else -> BgFormat.age(g.readingAgeMs)
        }
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${BgFormat.value(g.bgMgdl, snap.unit)} ${BgFormat.arrow(g.trend)}",
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
            )
            Text(
                text = "  $tail",
                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

class LockGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LockGlanceWidget()
}
