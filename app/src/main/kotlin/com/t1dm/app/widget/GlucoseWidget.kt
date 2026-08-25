package com.t1dm.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
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
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
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
import androidx.glance.state.PreferencesGlanceStateDefinition
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
import com.t1dm.core.design.applyWidgetPalette
import com.t1dm.core.design.resolvePalette
import com.t1dm.core.model.AlertBand
import timber.log.Timber
import kotlin.math.roundToInt

class GlucoseWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Compact, Medium, Large, XLarge))

    /**
     * Three tiers and never blank: the live pull, else the persisted snapshot, else the unknown floor.
     * The pull runs under a wall clock because a read that never resumes is not an exception and would
     * park this short of `provideContent`, leaving the host's loading spinner up for good.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val nowMs = System.currentTimeMillis()
        val live = boundedWidgetPull(
            SNAPSHOT_BUDGET_MS,
            onTimeout = { Timber.tag(TAG).w("live snapshot exceeded ${SNAPSHOT_BUDGET_MS}ms — falling back to last known") },
            onError = { Timber.tag(TAG).w(it, "live snapshot pull threw — falling back to last known") },
        ) { currentWidgetSnapshot(context) }
        val cached = if (live != null) null else runCatching { lastKnown(context, id) }.getOrNull()

        // Before composing: the palette holders are read at composition time. Nothing known ⇒ nothing
        // written, so a render that cannot establish the theme leaves whatever authority last did.
        themeOf(live, cached)?.let { (themeId, customThemeJson) ->
            runCatching { applyWidgetPalette(resolvePalette(themeId, customThemeJson)) }
                .onFailure { Timber.tag(TAG).w(it, "widget palette seed failed — keeping the current palette") }
        }

        if (live != null) {
            runCatching { updateAppWidgetState(context, id) { WidgetStateStore.write(it, live, nowMs) } }
                .onFailure { Timber.tag(TAG).w(it, "last-known snapshot write failed") }
        }

        val snap = live ?: cached?.let { WidgetStateStore.read(it, nowMs) } ?: WidgetStateStore.unknown(nowMs)
        provideContent {
            GlanceTheme(colors = T1dmGlanceColors) { WidgetSurface(snap) }
        }
    }

    /** The default replaces the tile with Glance's "Can't show content" layout; a stale number is
     *  better than an error card. */
    override fun onCompositionError(context: Context, glanceId: GlanceId, appWidgetId: Int, throwable: Throwable) {
        Timber.tag(TAG).w(throwable, "widget composition failed — leaving the previous tile in place")
    }

    private suspend fun lastKnown(context: Context, id: GlanceId): Preferences =
        getAppWidgetState(context, PreferencesGlanceStateDefinition, id)

    private fun themeOf(live: WidgetSnapshot?, cached: Preferences?): Pair<String, String?>? = when {
        live != null -> live.themeId to live.customThemeJson
        cached != null -> WidgetStateStore.themeOf(cached)
        else -> null
    }

    companion object {
        private const val TAG = "GlucoseWidget"

        /** A reading younger than this renders the "fresh" accent; the FGS schedules one re-render past it. */
        const val FRESH_WINDOW_MS = 2000L

        /** A healthy pull is tens of milliseconds, so this only bounds a cold or still-locked process. */
        const val SNAPSHOT_BUDGET_MS = 2500L
    }
}

class GlucoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.enqueue(context)
    }
}

// The minimum size at which each layout applies.
private val Compact = DpSize(120.dp, 48.dp)
private val Medium = DpSize(170.dp, 108.dp)
private val Large = DpSize(250.dp, 118.dp)
private val XLarge = DpSize(210.dp, 168.dp)

private val NormalBlue = Color(0xFF2E7DFF)

/** Matches Navigation's stableGreen. */
private val StableGreen = Color(0xFF3DD68C)

@Composable
private fun WidgetSurface(snap: WidgetSnapshot) {
    val p = T1dmActivePalette
    val size = LocalSize.current
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
        Text(ageText(g), style = TextStyle(fontSize = 11.sp, color = ColorProvider(p.inkMuted)))
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
        Text(ageText(g), style = TextStyle(fontSize = 11.sp, color = ColorProvider(p.inkMuted)))
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
        Text(ageText(g), style = TextStyle(fontSize = 11.sp, color = ColorProvider(p.inkMuted)))
    }
}

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

@Composable
private fun StatusIcon(death: Boolean) {
    if (death) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_skull),
            contentDescription = "Death mode",
            modifier = GlanceModifier.size(18.dp),
        )
    } else {
        Box(modifier = GlanceModifier.size(14.dp).cornerRadius(7.dp).background(ColorProvider(NormalBlue))) {}
    }
}

@Composable
private fun BgRow(snap: WidgetSnapshot, p: T1dmPalette, numberSp: Int, arrowSp: Int) {
    val g = snap.glance
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            BgFormat.value(g.bgMgdl, snap.unit),
            style = TextStyle(fontSize = numberSp.sp, fontWeight = FontWeight.Bold, color = ColorProvider(bandColor(g.band, p))),
        )
        val arrow = BgFormat.arrow(g.trend)
        if (arrow.isNotEmpty()) {
            Text(
                " $arrow",
                style = TextStyle(fontSize = arrowSp.sp, color = ColorProvider(if (isFresh(snap)) p.primary else p.ink)),
            )
        }
    }
}

/** Mirrors Navigation's top-bar status badge (U1). */
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
        Spacer(GlanceModifier.defaultWeight())
        SignalMetric(snap.rssi, p)
    }
}

@Composable
private fun SecondaryMetrics(snap: WidgetSnapshot, p: T1dmPalette) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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

/** [BgGlance.readingAgeMs] is 0 with no reading, which `BgFormat.age` would render as a confident "now". */
private fun ageText(g: BgGlance): String = if (g.hasReading) BgFormat.age(g.readingAgeMs) else "no reading"

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

private fun onColor(c: Color): Color {
    val l = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    return if (l > 0.6f) Color.Black else Color.White
}
