package com.t1dm.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.t1dm.app.MainActivity
import com.t1dm.app.R
import com.t1dm.app.notify.BgFormat
import com.t1dm.app.notify.BgGlance
import com.t1dm.core.design.T1dmActivePalette
import com.t1dm.core.design.T1dmPalette
import com.t1dm.core.model.AlertBand
import kotlin.math.roundToInt

/**
 * The single, adaptive glucose widget (replaces the three fixed Glance tiles). [SizeMode.Responsive]
 * lets ONE provider paint four densities — the launcher picks the largest that fits:
 *   Compact  — number + trend arrow;
 *   Medium   — + DEATH/NORMAL status, zone pill, forecast, age;
 *   Large    — + a metrics row (IOB · COB · GMI · steps);
 *   XLarge   — + a second metrics row (signal · circadian clock).
 * It reads the live [T1dmActivePalette] so every element follows the active theme across all five
 * palettes + a custom one. Motion is state-driven and free (no polling loop): a per-reading
 * "freshness" accent the FGS settles with ONE delayed re-render ([FRESH_WINDOW_MS]); gated by the
 * global animations toggle via [WidgetSnapshot.animationsEnabled].
 */
class GlucoseWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Compact, Medium, Large, XLarge))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = currentWidgetSnapshot(context)
        provideContent {
            GlanceTheme(colors = T1dmGlanceColors) { WidgetSurface(snap) }
        }
    }

    companion object {
        /** A reading younger than this renders the "fresh" accent; the FGS schedules one delayed
         *  re-render just past it so a new value reads as a brief pulse rather than a static jump. */
        const val FRESH_WINDOW_MS = 2000L
    }
}

class GlucoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()
}

// Responsive breakpoints (min size at which each layout applies); the launcher renders the largest fit.
private val Compact = DpSize(120.dp, 48.dp)
private val Medium = DpSize(170.dp, 108.dp)
private val Large = DpSize(250.dp, 118.dp)
private val XLarge = DpSize(210.dp, 168.dp)

/** The NORMAL-mode indicator: a plain blue filled dot (DEATH shows a red skull instead). */
private val NormalBlue = Color(0xFF2E7DFF)

/** The STABLE badge green (matches Navigation's stableGreen — a positive, non-theme claim colour). */
private val StableGreen = Color(0xFF3DD68C)

@Composable
private fun WidgetSurface(snap: WidgetSnapshot) {
    val p = T1dmActivePalette
    val size = LocalSize.current
    // The same per-theme motif the app paints, rasterised to a bitmap (Glance/RemoteViews can't run the
    // live Canvas painter) at the user's Background opacity; a flat p.background base when it's off.
    val density = LocalContext.current.resources.displayMetrics.density
    val backdrop = widgetBackdropBitmap(
        p,
        widthPx = (size.width.value * density).roundToInt(),
        heightPx = (size.height.value * density).roundToInt(),
        alphaPct = snap.bgAlphaPct,
    )
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .let {
                if (backdrop != null) it.background(ImageProvider(backdrop), contentScale = ContentScale.FillBounds)
                else it.background(ColorProvider(p.background))
            }
            .cornerRadius(24.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clickable(actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))),
        contentAlignment = Alignment.Center,
    ) {
        when {
            size.height >= XLarge.height && size.width >= XLarge.width -> XLargeContent(snap, p)
            size.width >= Large.width -> LargeContent(snap, p)
            size.width >= Medium.width -> MediumContent(snap, p)
            else -> CompactContent(snap, p)
        }
    }
}

@Composable
private fun CompactContent(snap: WidgetSnapshot, p: T1dmPalette) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BgRow(snap, p, numberSp = 34, arrowSp = 24)
        Text(BgFormat.unitLabel(snap.unit), style = TextStyle(fontSize = 11.sp, color = ColorProvider(p.inkMuted)))
    }
}

@Composable
private fun MediumContent(snap: WidgetSnapshot, p: T1dmPalette) {
    val g = snap.glance
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Header(snap, p, numberSp = 30, arrowSp = 22)
        Spacer(GlanceModifier.height(6.dp))
        Text(forecastText(g), maxLines = 1, style = TextStyle(fontSize = 13.sp, color = ColorProvider(p.ink)))
        Text(BgFormat.age(g.readingAgeMs), style = TextStyle(fontSize = 11.sp, color = ColorProvider(p.inkMuted)))
    }
}

@Composable
private fun LargeContent(snap: WidgetSnapshot, p: T1dmPalette) {
    val g = snap.glance
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Header(snap, p, numberSp = 38, arrowSp = 26)
        Spacer(GlanceModifier.height(8.dp))
        PrimaryMetrics(snap, p)
        Spacer(GlanceModifier.height(8.dp))
        Text(forecastText(g), maxLines = 1, style = TextStyle(fontSize = 13.sp, color = ColorProvider(p.ink)))
        Text(BgFormat.age(g.readingAgeMs), style = TextStyle(fontSize = 11.sp, color = ColorProvider(p.inkMuted)))
    }
}

@Composable
private fun XLargeContent(snap: WidgetSnapshot, p: T1dmPalette) {
    val g = snap.glance
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Header(snap, p, numberSp = 40, arrowSp = 28)
        Spacer(GlanceModifier.height(10.dp))
        PrimaryMetrics(snap, p)
        Spacer(GlanceModifier.height(10.dp))
        SecondaryMetrics(snap, p)
        Spacer(GlanceModifier.height(10.dp))
        Text(forecastText(g), maxLines = 1, style = TextStyle(fontSize = 14.sp, color = ColorProvider(p.ink)))
        Text(BgFormat.age(g.readingAgeMs), style = TextStyle(fontSize = 11.sp, color = ColorProvider(p.inkMuted)))
    }
}

/** The value + arrow (+ unit) on the left; the DEATH/NORMAL status icon and glycemic-zone pill on the right. */
@Composable
private fun Header(snap: WidgetSnapshot, p: T1dmPalette, numberSp: Int, arrowSp: Int) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BgRow(snap, p, numberSp = numberSp, arrowSp = arrowSp)
        Text("  ${BgFormat.unitLabel(snap.unit)}", style = TextStyle(fontSize = 12.sp, color = ColorProvider(p.inkMuted)))
        Spacer(GlanceModifier.defaultWeight())
        StatusIcon(snap.death)
        Spacer(GlanceModifier.width(8.dp))
        GlyBadge(snap, p)
    }
}

/** DEATH → red skull; NORMAL (DEATH off) → a blue filled circle. */
@Composable
private fun StatusIcon(death: Boolean) {
    if (death) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_skull),
            contentDescription = "DEATH mode",
            modifier = GlanceModifier.size(18.dp),
        )
    } else {
        Box(modifier = GlanceModifier.size(14.dp).cornerRadius(7.dp).background(ColorProvider(NormalBlue))) {}
    }
}

/** The BG value + trend arrow, value tinted by glycemic band, arrow flipped to the accent while fresh.
 *  Warmup / signal loss are carried by the forecast line, not a spinner. */
@Composable
private fun BgRow(snap: WidgetSnapshot, p: T1dmPalette, numberSp: Int, arrowSp: Int) {
    val g = snap.glance
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            BgFormat.value(g.bgMgdl, snap.unit),
            style = TextStyle(fontSize = numberSp.sp, fontWeight = FontWeight.Bold, color = ColorProvider(bandColor(g.band, p))),
        )
        Text(
            " ${BgFormat.arrow(g.trend)}",
            style = TextStyle(fontSize = arrowSp.sp, color = ColorProvider(if (isFresh(snap)) p.primary else p.ink)),
        )
    }
}

/** The app-wide glycemic status badge (matches Navigation's top-bar U1): green STABLE, red HYPO/HYPER
 *  in N, muted VOID. Fail-closed — the number's own band tint still shows the measured band. */
@Composable
private fun GlyBadge(snap: WidgetSnapshot, p: T1dmPalette) {
    val bg = when (snap.glyKind) {
        GlyKind.STABLE -> StableGreen
        GlyKind.EXCURSION -> p.urgentLow
        GlyKind.VOID -> p.surfaceVariant
    }
    val fg = if (snap.glyKind == GlyKind.VOID) p.inkMuted else onColor(bg)
    Box(
        modifier = GlanceModifier.background(ColorProvider(bg)).cornerRadius(10.dp).padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(snap.glyText, maxLines = 1, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorProvider(fg)))
    }
}

/** IOB · COB · GMI · steps, spread across the width. Cells with no data read "—". */
@Composable
private fun PrimaryMetrics(snap: WidgetSnapshot, p: T1dmPalette) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Metric("IOB", snap.iobU?.let { "${oneDp(it)}U" } ?: "—", p)
        Spacer(GlanceModifier.defaultWeight())
        Metric("COB", snap.cobG?.let { "${it.roundToInt()}g" } ?: "—", p)
        Spacer(GlanceModifier.defaultWeight())
        Metric("GMI", snap.gmi?.let { "${oneDp(it)}%" } ?: "—", p)
        Spacer(GlanceModifier.defaultWeight())
        Metric("STEPS", snap.steps?.let { humanSteps(it) } ?: "—", p)
    }
}

/** CGM signal · circadian clock (XLarge only). */
@Composable
private fun SecondaryMetrics(snap: WidgetSnapshot, p: T1dmPalette) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SignalMetric(snap.rssi, p)
        Spacer(GlanceModifier.defaultWeight())
        Metric("CLOCK", snap.clockHour?.let { formatClock(it) } ?: "—", p)
    }
}

@Composable
private fun Metric(label: String, value: String, p: T1dmPalette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ColorProvider(p.ink)))
        Text(label, style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.inkMuted)))
    }
}

@Composable
private fun SignalMetric(rssi: Int?, p: T1dmPalette) {
    val level = signalLevel(rssi)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            for (i in 1..4) {
                Spacer(
                    GlanceModifier
                        .width(3.dp)
                        .height((3 + i * 2).dp)
                        .cornerRadius(1.dp)
                        .background(ColorProvider(if (i <= level) p.primary else p.grid)),
                )
                Spacer(GlanceModifier.width(2.dp))
            }
        }
        Text("SIG", style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.inkMuted)))
    }
}

private fun bandColor(band: AlertBand?, p: T1dmPalette): Color = when (band) {
    AlertBand.URGENT_LOW -> p.urgentLow
    AlertBand.LOW -> p.low
    AlertBand.IN_RANGE -> p.inRange
    AlertBand.HIGH -> p.high
    AlertBand.URGENT_HIGH -> p.urgentHigh
    null -> p.inkMuted
}

private fun forecastText(g: BgGlance): String = when {
    g.approaching != null -> BgFormat.crossingLine(g.approaching)
    g.warmup -> "Collecting context…"
    g.signalLoss -> "Signal lost"
    g.forecastEligible && g.fcEndMgdl != null -> g.summary
    else -> "Forecast unavailable"
}

private fun signalLevel(rssi: Int?): Int = when {
    rssi == null -> 0
    rssi >= -60 -> 4
    rssi >= -70 -> 3
    rssi >= -80 -> 2
    rssi >= -90 -> 1
    else -> 0
}

private fun formatClock(hour: Double): String {
    val hf = ((hour % 24.0) + 24.0) % 24.0
    val h = hf.toInt()
    val m = ((hf - h) * 60.0).toInt().coerceIn(0, 59)
    return "%02d:%02d".format(h, m)
}

/** Human-readable step count: as-is below 1000, else "K" (e.g. 400, 5K, 5.4K, 12K). */
private fun humanSteps(n: Int): String {
    if (n < 1000) return n.toString()
    val k = n / 1000.0
    if (k >= 10) return "${k.roundToInt()}K"
    val s = "%.1f".format(k)
    return (if (s.endsWith(".0")) s.dropLast(2) else s) + "K"
}

private fun oneDp(v: Double): String = "%.1f".format(v)

private fun isFresh(snap: WidgetSnapshot): Boolean =
    snap.animationsEnabled && snap.glance.hasReading &&
        snap.glance.readingAgeMs in 0 until GlucoseWidget.FRESH_WINDOW_MS

/** Readable ink for text laid over a saturated band colour, by perceived luminance. */
private fun onColor(c: Color): Color {
    val l = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    return if (l > 0.6f) Color.Black else Color.White
}
