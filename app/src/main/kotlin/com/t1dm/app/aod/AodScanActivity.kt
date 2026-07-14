package com.t1dm.app.aod

import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.t1dm.app.T1dmApplication
import com.t1dm.app.di.AppContainer
import com.t1dm.app.notify.BgFormat
import com.t1dm.app.widget.currentWidgetSnapshot
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.TempUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The keep-screen-on "always-on display" scan surface (build-gotchas: HyperOS/powerkeeper suspends a
 * backgrounded BLE scan the moment the screen turns off, gating on the physical `is_screen_on`). By
 * holding a genuinely-ON display over the keyguard — `setShowWhenLocked` + `setTurnScreenOn` +
 * `FLAG_KEEP_SCREEN_ON` at near-zero brightness — the device stays interactive (`isInteractive()==true`),
 * so both AOSP and powerkeeper see `is_screen_on=1` and the real-time scan is never suspended. It is
 * raised by [com.t1dm.app.service.CgmScanService] via a full-screen-intent on `ACTION_SCREEN_OFF` while
 * the aggressive mode is engaged, and finishes itself the moment it is no longer visible ([onStop]) so
 * the user's next wake lands on their normal lock screen rather than this surface.
 *
 * When `aggressiveShowGlucose` is on it renders a dim monochrome dashboard (a DIY AOD, drifting slowly
 * to avoid OLED burn-in): an analog clock, the model's circadian circular-histogram, the BG hero, mode
 * (NORMAL/DEATH) and glycemic status (STABLE/VOID/…), IOB/COB/GMI/steps/temperature/battery, the CGM
 * signal + reading age, and the current forecast. Every value is fetched from the SAME container the
 * app UI reads (via [currentWidgetSnapshot] + [AppContainer]), so the AOD agrees with the app by
 * construction. Otherwise it is pitch-black (pure scan-keepalive, invisible in the pocket).
 */
class AodScanActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as T1dmApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Pin the panel near-black: dim-but-legible when showing the dashboard, effectively-off otherwise.
        // A hard 0f reads as "system default" on some OEMs, so floor at a tiny epsilon.
        window.attributes = window.attributes.apply {
            screenBrightness = if (container.aggressiveShowGlucoseSnapshot) BRIGHT_GLUCOSE else BRIGHT_BLACK
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent { AodSurface(container) }
    }

    /** Release the surface the instant it is no longer visible (screen off, or covered by an alarm's
     *  full-screen intent) so the user's next wake shows the real keyguard; the service re-raises us
     *  after its grace window if the screen simply went back off. */
    override fun onStop() {
        super.onStop()
        finish()
    }

    private companion object {
        const val BRIGHT_GLUCOSE = 0.06f
        const val BRIGHT_BLACK = 0.004f
    }
}

// ── Monochrome palette (dim greys on black — legible on a near-off OLED, no colour) ─────────────────
private val INK = Color(0xFFCFCFCF)
private val INK_MID = Color(0xFF9E9E9E)
private val INK_DIM = Color(0xFF6C6C6C)
private val INK_FAINT = Color(0xFF3C3C3C)

/** The full data snapshot the dashboard renders — everything drawn from the app's own container. */
private data class AodData(
    val bgText: String,
    val arrow: String,
    val unitLabel: String,
    val readingAtMs: Long?,
    val rssi: Int?,
    val death: Boolean,
    val glyText: String,
    val batteryPct: Int?,
    val charging: Boolean,
    val forecastText: String?,
    val iobU: Double?,
    val cobG: Double?,
    val gmi: Double?,
    val steps: Int?,
    val tempText: String?,
    val sensorText: String?,
    val pt: PredictedTime?,
)

private suspend fun fetchAod(ctx: Context): AodData {
    val container = (ctx.applicationContext as T1dmApplication).container
    val snap = currentWidgetSnapshot(ctx)
    val g = snap.glance
    val unit = snap.unit
    val readingAtMs = if (g.hasReading) System.currentTimeMillis() - g.readingAgeMs else null

    val tempUnit = TempUnit.fromKey(runCatching { container.settingsStore.temperatureUnit.first() }.getOrNull())
    val tempC = withContext(container.dispatchers.io) { container.readDeviceTempC() }

    val bm = ctx.getSystemService(BatteryManager::class.java)
    val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    val charging = bm?.isCharging == true

    val sensorText = runCatching { container.settingsStore.sensorExpiryMs.first() }.getOrNull()
        ?.let { fmtRemaining(it - System.currentTimeMillis()) }

    val forecastText = if (g.forecastEligible && g.fcEndMgdl != null) {
        val eta = g.approaching?.etaMin?.let { " · ${it}m" } ?: ""
        "→ ${BgFormat.value(g.fcEndMgdl, unit)} ${BgFormat.unitLabel(unit)}$eta"
    } else {
        null
    }

    return AodData(
        bgText = BgFormat.value(g.bgMgdl, unit),
        arrow = if (g.bgMgdl == null) "" else BgFormat.arrow(g.trend),
        unitLabel = BgFormat.unitLabel(unit),
        readingAtMs = readingAtMs,
        rssi = snap.rssi,
        death = snap.death,
        glyText = snap.glyText,
        batteryPct = pct,
        charging = charging,
        forecastText = forecastText,
        iobU = snap.iobU,
        cobG = snap.cobG,
        gmi = snap.gmi,
        steps = snap.steps,
        tempText = tempC?.let { tempUnit.format(it) },
        sensorText = sensorText,
        pt = container.inferenceState.value.selectedPredictedTime,
    )
}

/** Compact remaining-time for the sensor cell, e.g. "6d 4h" / "4h 12m" / "38m" / "expired". */
private fun fmtRemaining(ms: Long): String {
    if (ms <= 0) return "expired"
    val totalMin = ms / 60_000L
    val d = totalMin / 1440; val h = (totalMin % 1440) / 60; val m = totalMin % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

@Composable
private fun AodSurface(container: AppContainer) {
    val showGlucose by container.aggressiveShowGlucose.collectAsState(true)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (showGlucose) AodDashboard(container)
    }
}

@Composable
private fun AodDashboard(container: AppContainer) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var data by remember { mutableStateOf<AodData?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            data = runCatching { fetchAod(ctx) }.getOrNull()
            kotlinx.coroutines.delay(REFRESH_MS)
        }
    }
    val d = data ?: return

    // Anti-burn-in drift: a slow (~8-min) Lissajous creep applied as a GPU-layer translation, so it
    // moves sub-pixel per frame — imperceptible at a glance, yet it walks the bright pixels around over
    // minutes. (The old whole-dp offset stepped ~1 dp every few seconds, which read as a visible jump.)
    val phase by rememberInfiniteTransition(label = "aod-drift").animateFloat(
        initialValue = 0f, targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = DRIFT_PERIOD_MS, easing = LinearEasing)),
        label = "aod-drift-phase",
    )

    Column(
        Modifier.fillMaxSize()
            .graphicsLayer {
                translationX = DRIFT_DP.dp.toPx() * sin(phase)
                translationY = DRIFT_DP.dp.toPx() * sin(phase * 1.3f + 0.7f)
            }
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Mode + status … battery. Inset to span exactly the two clocks below rather than the full
        // padded width (which read as too spread out): the clocks sit in a SpaceEvenly row of two
        // DIAL_SIZE dials, so their outer edges are one SpaceEvenly gap — (width − 2·dial) / 3 — in
        // from the padding; pad this row by that same gap and its ends line up with the clocks'.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = ((maxWidth - DIAL_SIZE * 2) / 3).coerceAtLeast(0.dp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = gap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${if (d.death) "DEATH" else "NORMAL"}   ·   ${d.glyText}",
                    color = if (d.death) INK else INK_MID,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = d.batteryPct?.let { "$it%" } ?: "",
                        color = INK_MID,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    // A drawn (monochrome) charging bolt — never an emoji, which would render in colour.
                    if (d.charging && d.batteryPct != null) ChargingBolt(Modifier.size(width = 8.dp, height = 13.dp))
                }
            }
        }

        Spacer(Modifier.size(18.dp))

        // The two clocks — real (analog) and the model's circadian belief (circular histogram).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            LabeledDial("TIME") { AnalogClock(Modifier.size(DIAL_SIZE)) }
            LabeledDial(
                d.pt?.let { "CIRCADIAN · R %.2f".format(it.resultantR) } ?: "CIRCADIAN",
            ) { CircadianDial(d.pt, Modifier.size(DIAL_SIZE)) }
        }

        Spacer(Modifier.size(20.dp))

        // BG hero.
        Text(
            text = if (d.arrow.isEmpty()) d.bgText else "${d.bgText} ${d.arrow}",
            color = INK,
            fontSize = 84.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
        )
        AgeLine(d.unitLabel, d.readingAtMs)
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalBars(d.rssi, Modifier.size(width = 26.dp, height = 16.dp))
            Text("${d.rssi ?: "--"} dBm", color = INK_DIM, fontSize = 12.sp)
        }
        d.forecastText?.let {
            Spacer(Modifier.size(6.dp))
            Text("forecast $it", color = INK_MID, fontSize = 14.sp)
        }

        Spacer(Modifier.size(22.dp))

        // Scalar read-outs, two per row.
        val cells = listOf(
            "IOB" to (d.iobU?.let { "%.1f U".format(it) } ?: "—"),
            "COB" to (d.cobG?.let { "%.0f g".format(it) } ?: "—"),
            "GMI" to (d.gmi?.let { "%.1f %%".format(it) } ?: "—"),
            "STEPS" to (d.steps?.toString() ?: "—"),
            "TEMP" to (d.tempText ?: "—"),
            "SENSOR" to (d.sensorText ?: "—"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            cells.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (label, value) -> ReadoutCell(label, value, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** A monochrome lightning bolt (charging indicator), drawn rather than an emoji so it stays grey. */
@Composable
private fun ChargingBolt(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val bolt = Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w * 0.12f, h * 0.55f)
            lineTo(w * 0.44f, h * 0.55f)
            lineTo(w * 0.30f, h)
            lineTo(w * 0.92f, h * 0.40f)
            lineTo(w * 0.54f, h * 0.40f)
            close()
        }
        drawPath(bolt, INK_MID)
    }
}

@Composable
private fun LabeledDial(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
        Text(label, color = INK_DIM, fontSize = 10.sp, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun ReadoutCell(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = INK, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(label, color = INK_DIM, fontSize = 10.sp, letterSpacing = 1.5.sp)
    }
}

/** "mg/dL · updated 9m ago" with a live-ticking age (own 1 s ticker so only this line recomposes). */
@Composable
private fun AgeLine(unitLabel: String, readingAtMs: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(readingAtMs) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000L) }
    }
    val age = readingAtMs?.let { "updated ${BgFormat.age((now - it).coerceAtLeast(0L))}" } ?: "no reading yet"
    Text("$unitLabel  ·  $age", color = INK_DIM, fontSize = 14.sp, textAlign = TextAlign.Center)
}

// ── Analog wall clock (own 1 s ticker so only the clock recomposes each second) ─────────────────────
@Composable
private fun AnalogClock(modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000L) }
    }
    val cal = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val h = cal.get(Calendar.HOUR); val m = cal.get(Calendar.MINUTE); val s = cal.get(Calendar.SECOND)
    Canvas(modifier.aspectRatio(1f)) {
        val r = min(size.width, size.height) / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(INK_FAINT, radius = r * 0.98f, center = c, style = Stroke(width = 2f))
        for (i in 0 until 12) {
            val a = topClockwise(i / 12.0)
            drawLine(INK_FAINT, pointAt(c, r * (if (i % 3 == 0) 0.80f else 0.88f), a), pointAt(c, r * 0.96f, a),
                strokeWidth = if (i % 3 == 0) 2.5f else 1.2f)
        }
        drawHand(c, r * 0.48f, topClockwise(((h % 12) + m / 60.0) / 12.0), INK, 4.5f)
        drawHand(c, r * 0.70f, topClockwise((m + s / 60.0) / 60.0), INK_MID, 3f)
        drawHand(c, r * 0.82f, topClockwise(s / 60.0), INK_DIM, 1.2f)
        drawCircle(INK, radius = 4f, center = c)
    }
}

// ── Circadian dial: the model's hour-of-day belief as a 12-wedge circular histogram + resultant hand,
//    monochrome (mirrors feature:dashboard CircadianDial geometry). Own slow ticker for the local hand. ─
@Composable
private fun CircadianDial(pt: PredictedTime?, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(20_000L) }
    }
    val localHour = localHourOfDay(now)
    Canvas(modifier.aspectRatio(1f)) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val center = Offset(cx, cy)
        val rOuter = min(cx, cy) * 0.94f
        val rHist = rOuter * 0.80f
        drawCircle(INK_FAINT, radius = rOuter, center = center, style = Stroke(width = 2f))

        if (pt != null) {
            val probs = pt.probs
            val nBins = probs.size.coerceAtLeast(1)
            val binH = if (pt.binHours > 0) pt.binHours else 24.0 / nBins
            val maxP = probs.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            probs.forEachIndexed { k, p ->
                val frac = (p / maxP).coerceIn(0.0, 1.0).toFloat()
                val wr = rHist * (0.20f + 0.80f * frac)
                drawArc(
                    color = INK.copy(alpha = 0.18f + 0.72f * frac),
                    startAngle = (-90.0 + (k * binH) / 24.0 * 360.0).toFloat(),
                    sweepAngle = (binH / 24.0 * 360.0).toFloat(),
                    useCenter = true,
                    topLeft = Offset(cx - wr, cy - wr),
                    size = Size(wr * 2, wr * 2),
                )
            }
            // Model hand: length + alpha ∝ resultant R.
            val rr = pt.resultantR.coerceIn(0.0, 1.0)
            drawHand(center, rHist * (0.30f + 0.65f * rr.toFloat()), topClockwise(pt.predictedHour / 24.0),
                INK.copy(alpha = (0.35f + 0.65f * rr).toFloat()), 5f)
        }

        // Hour ticks every 2 h; faint local wall-clock hand for contrast; hub.
        for (hh in 0 until 24 step 2) {
            val a = topClockwise(hh / 24.0)
            drawLine(INK_FAINT, pointAt(center, rOuter * 0.90f, a), pointAt(center, rOuter, a),
                strokeWidth = if (hh % 6 == 0) 2.5f else 1.2f)
        }
        drawHand(center, rHist * 0.9f, topClockwise(localHour / 24.0), INK_DIM.copy(alpha = 0.6f), 2.5f)
        drawCircle(INK_MID, radius = 4f, center = center)
    }
}

// ── Monochrome CGM signal bars (thresholds mirror core.design SignalBars: −60/−70/−80/−90). ─────────
@Composable
private fun SignalBars(rssi: Int?, modifier: Modifier) {
    val filled = when {
        rssi == null -> 0
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    Canvas(modifier) {
        val n = 4
        val gap = size.width * 0.12f
        val bw = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val bh = size.height * (0.35f + 0.65f * (i + 1) / n)
            val x = i * (bw + gap)
            drawRect(
                color = if (i < filled) INK else INK_FAINT,
                topLeft = Offset(x, size.height - bh),
                size = Size(bw, bh),
            )
        }
    }
}

// ── shared geometry ────────────────────────────────────────────────────────────────────────────────
/** Fraction of a full turn (0..1) → radians, 12 o'clock at top, increasing clockwise. */
private fun topClockwise(frac: Double): Double = -PI / 2.0 + frac * 2.0 * PI

private fun pointAt(c: Offset, r: Float, ang: Double): Offset =
    Offset(c.x + (r * cos(ang)).toFloat(), c.y + (r * sin(ang)).toFloat())

private fun DrawScope.drawHand(center: Offset, len: Float, ang: Double, color: Color, width: Float) {
    drawLine(color, center, pointAt(center, len, ang), strokeWidth = width, cap = StrokeCap.Round)
}

private fun localHourOfDay(ms: Long): Double {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0 + c.get(Calendar.SECOND) / 3600.0
}

// Same drift distance + speed as the original whole-dp version (identical OLED protection); only the
// RENDERING changed — sub-pixel via a GPU layer instead of 1-dp steps, so it no longer visibly jumps.
private const val DRIFT_DP = 12f
private const val DRIFT_PERIOD_MS = 120_000
private const val REFRESH_MS = 10_000L
// The clock/circadian dial edge length; the mode+battery header insets itself by the SpaceEvenly gap
// this implies so its ends line up with the two dials.
private val DIAL_SIZE = 112.dp
