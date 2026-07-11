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
import com.t1dm.app.notify.PredictiveCrossing

/**
 * The compact lock-screen glance (ux-decisions.md). A single dense row: the current BG + trend
 * arrow + a §3.6-GATED glycemic status (VOID / STABLE / HYPO / HYPER), matching the app's top-bar
 * indicator. On Android 12+ true lock-screen widgets are limited, so this is the same Glance
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
        // Fail-closed like the top-bar indicator (Navigation.glycemicStatusOf): warmup, no eligible
        // forecast, or an ineligible one is VOID — never a positive STABLE claim. Only an eligible
        // forecast yields STABLE, or HYPO/HYPER off its earliest gated crossing.
        val void = g.warmup || g.forecastUnavailable || !g.forecastEligible
        val hypo = g.approaching?.kind == PredictiveCrossing.Kind.HYPO
        val status = when {
            void -> "VOID"
            g.approaching != null -> (if (hypo) "HYPO" else "HYPER") + " in ${g.approaching!!.etaMin}M"
            else -> "STABLE"
        }
        val statusColor = when {
            void -> GlanceTheme.colors.onSurfaceVariant
            g.approaching != null -> GlanceTheme.colors.error
            else -> GlanceTheme.colors.onSurface
        }
        // For an excursion the status line already carries the ETA; otherwise keep the terse tail.
        val tail = when {
            g.approaching != null -> null
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
                text = "  $status",
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                ),
            )
            if (tail != null) {
                Text(
                    text = "  $tail",
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }
    }
}

class LockGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LockGlanceWidget()
}
