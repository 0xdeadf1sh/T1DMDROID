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
 * The current-BG + trend home tile (ux-decisions.md widgets). Big number in the active unit, a trend
 * chevron, and the last-updated age. Refreshes without an Activity — the foreground service calls
 * [BgTileWidget.updateAll] on each reading + 5-min cycle. Renders under the app's snapshotted
 * palette ([T1dmGlanceColors]) so the tile matches the in-app theme, not the launcher default.
 */
class BgTileWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = currentWidgetSnapshot(context)
        provideContent {
            GlanceTheme(colors = T1dmGlanceColors) {
                TileContent(snap)
            }
        }
    }

    @Composable
    private fun TileContent(snap: WidgetSnapshot) {
        val g = snap.glance
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp)
                .clickable(actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = BgFormat.value(g.bgMgdl, snap.unit),
                    style = TextStyle(
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
                )
                Text(
                    text = "  ${BgFormat.arrow(g.trend)}",
                    style = TextStyle(fontSize = 28.sp, color = GlanceTheme.colors.onSurface),
                )
            }
            Text(
                text = BgFormat.unitLabel(snap.unit),
                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            Text(
                text = if (g.signalLoss) "signal lost" else BgFormat.age(g.readingAgeMs),
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

class BgTileWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BgTileWidget()
}
