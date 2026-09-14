package com.t1dm.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.JourneyPath
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.SevenSegmentClock
import com.t1dm.core.design.JourneyMarks
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.journeyProgress
import com.t1dm.core.model.DkaTimeline
import com.t1dm.core.model.PredictedTime
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Dial geometry mirrors T1DMAI `clock_face.py` / `utils.clock_wedge_geometry`. */
@Composable
fun CircadianScreen(
    predictedTime: PredictedTime?,
    realBackendAvailable: Boolean,
    noModel: Boolean = false,
    hasTimeSection: Boolean = true,
    warmingUp: Boolean = false,
    lowContext: Boolean = false,
    /** Insulin-on-board, U. */
    iobU: Double? = null,
    /** Wall-clock ms at which IOB is projected to reach zero. */
    iobZeroMs: Long? = null,
    dkaTimeline: DkaTimeline = DkaTimeline.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxSize()
            .fadingEdges(scroll)
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "The model's belief about which hour-of-day the current physiology resembles",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (predictedTime == null) {
            EmptyCircadian(realBackendAvailable, noModel, hasTimeSection, warmingUp)
        } else {
            if (lowContext) LowContextCaveat()

            val nowMs by produceState(System.currentTimeMillis()) {
                while (true) {
                    value = System.currentTimeMillis()
                    kotlinx.coroutines.delay(10_000)
                }
            }
            val localHour = localHourOfDay(nowMs)

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircadianDial(
                    pt = predictedTime,
                    localHour = localHour,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp),
                )
            }

            ReadoutRow("Model hour", formatHour(predictedTime.predictedHour))
            ReadoutRow("Confidence (R)", "%.2f".format(predictedTime.resultantR))
            ReadoutRow("Local time", formatHour(localHour))
            ReadoutRow("Model − local offset", formatOffset(predictedTime.predictedHour - localHour))
            ReadoutRow("Bins", "${predictedTime.nBins} × ${"%.0f".format(predictedTime.binHours)} h")
            if (predictedTime.resultantR < 0.05) {
                Text(
                    "Nearly uniform (R ≈ 0) — no strong circadian preference this cycle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Independent of the circadian belief, so it renders in every mode.
        DeathCountdownSection(iobU, iobZeroMs, dkaTimeline)
    }
}

/** Display only: no §3.6 rail reads this. An estimate, not a clinical alarm. */
@Composable
private fun DeathCountdownSection(iobU: Double?, iobZeroMs: Long?, tl: DkaTimeline) {
    // State kept wrapped: readers are lambdas the Canvas invokes; ticks repaint, not recompose.
    val nowMs = produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    // Captured once; live nowMs would cancel to a constant, freezing or faking the crossing.
    val anchor = iobZeroMs
    fun h(x: Double) = (x * 3_600_000.0).toLong()
    val marks = JourneyMarks.EVEN
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Insulin-exhaustion projection", style = MaterialTheme.typography.labelLarge)
            if (iobU != null) {
                Text(
                    "%.1f U".format(iobU),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (anchor == null) {
            Text(
                if (iobU != null && iobU > 0.0) "Exhaustion time unknown" else "No insulin on board",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val tDka = anchor + h(tl.iobZeroToDkaHours)
            val tComa = tDka + h(tl.dkaToComaHours)
            val tDeath = tComa + h(tl.comaToDeathHours)
            CountdownRow("DKA") { tDka - nowMs.value }
            CountdownRow("Coma") { tComa - nowMs.value }
            CountdownRow("Death") { tDeath - nowMs.value }
            JourneyPath(
                progress = { journeyProgress(nowMs.value, anchor, tl) },
                marks = marks,
                modifier = Modifier.fillMaxWidth().height(96.dp).padding(top = 6.dp),
            )
        }
    }
}

/** A lapsed landmark reads in error. */
@Composable
private fun CountdownRow(label: String, remainingMs: () -> Long) {
    Row(
        // Roughly half a glyph against the 28 dp digit block; 2 dp left the clocks touching.
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SevenSegmentClock(remainingMs, Modifier.weight(1f).height(28.dp).padding(start = 12.dp))
    }
}

@Composable
private fun EmptyCircadian(
    realBackendAvailable: Boolean,
    noModel: Boolean,
    hasTimeSection: Boolean,
    warmingUp: Boolean,
) {
    val msg = when {
        // First: with nothing installed the other three flags still hold the last model's values.
        noModel ->
            "Model time unavailable — no model installed"
        !realBackendAvailable ->
            "Model time unavailable — stub backend has no circadian probe"
        !hasTimeSection ->
            "Model time unavailable — this model has no time section"
        warmingUp ->
            "Model time unavailable — warming up; needs ≈8 h BG"
        else ->
            "Model time unavailable — probe output couldn't be decoded this cycle"
    }
    Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LowContextCaveat() {
    Text(
        "Low-confidence — a circadian belief, not a forecast",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

/** Wrapped to `[-12, 12)` h, e.g. "+1:30" / "−2:00". */
private fun formatOffset(deltaHours: Double): String {
    var d = deltaHours % 24.0
    if (d < -12.0) d += 24.0
    if (d >= 12.0) d -= 24.0
    val sign = if (d < 0) "−" else "+"
    val a = kotlin.math.abs(d)
    val hh = a.toInt()
    val mm = ((a - hh) * 60.0).toInt()
    return "%s%d:%02d".format(sign, hh, mm)
}

@Composable
private fun ReadoutRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CircadianDial(pt: PredictedTime, localHour: Double, modifier: Modifier) {
    val palette = LocalT1dmSemantics.current
    val cs = MaterialTheme.colorScheme
    val wedgeColor = cs.primary
    val handColor = cs.secondary
    val faceColor = palette.grid
    val tickInk = cs.onSurface
    val localHandColor = cs.onSurface.copy(alpha = 0.35f)

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)
        val rOuter = min(cx, cy) * 0.92f
        val rHist = rOuter * 0.80f

        drawCircle(faceColor, radius = rOuter, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
        drawCircle(faceColor.copy(alpha = 0.25f), radius = rHist, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))

        val probs = pt.probs
        val nBins = probs.size.coerceAtLeast(1)
        val binH = if (pt.binHours > 0) pt.binHours else 24.0 / nBins
        val maxP = probs.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        probs.forEachIndexed { k, p ->
            val frac = (p / maxP).coerceIn(0.0, 1.0)
            val startHour = k * binH
            val startAngle = hourToSweepDegrees(startHour)
            val sweep = (binH / 24.0 * 360.0).toFloat()
            val wr = rHist * (0.20f + 0.80f * frac.toFloat())
            val alpha = (0.20f + 0.80f * frac.toFloat())
            drawArc(
                color = wedgeColor.copy(alpha = alpha),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(cx - wr, cy - wr),
                size = Size(wr * 2, wr * 2),
            )
            drawArc(
                color = faceColor.copy(alpha = 0.4f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(cx - wr, cy - wr),
                size = Size(wr * 2, wr * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
            )
        }

        for (h in 0 until 24 step 2) {
            val ang = hourToRadians(h.toDouble())
            val inner = pointAt(center, rOuter * 0.92f, ang)
            val outer = pointAt(center, rOuter, ang)
            drawLine(tickInk.copy(alpha = 0.5f), inner, outer, strokeWidth = if (h % 6 == 0) 3f else 1.5f)
        }
        drawFaceLabels(center, rOuter, tickInk.toArgb())

        drawHand(center, rHist * 0.9f, hourToRadians(localHour), localHandColor, 4f)

        val r = pt.resultantR.coerceIn(0.0, 1.0)
        val len = rHist * (0.30f + 0.65f * r.toFloat())
        drawHand(center, len, hourToRadians(pt.predictedHour), handColor.copy(alpha = (0.35f + 0.65f * r).toFloat()), 6f)
        drawCircle(handColor, radius = 7f, center = center)
    }
}

// Hour 0 at top, increasing clockwise on a 24-h dial.
private fun hourToRadians(hour: Double): Double = -PI / 2.0 + (hour / 24.0) * 2.0 * PI

/** Compose `drawArc` uses degrees with 0° at 3 o'clock, positive = clockwise. */
private fun hourToSweepDegrees(hour: Double): Float = (-90.0 + (hour / 24.0) * 360.0).toFloat()

private fun pointAt(c: Offset, r: Float, ang: Double): Offset =
    Offset(c.x + (r * cos(ang)).toFloat(), c.y + (r * sin(ang)).toFloat())

private fun DrawScope.drawHand(center: Offset, len: Float, ang: Double, color: Color, width: Float) {
    drawLine(color, center, pointAt(center, len, ang), strokeWidth = width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
}

private fun DrawScope.drawFaceLabels(center: Offset, rOuter: Float, argb: Int) {
    val paint = android.graphics.Paint().apply {
        color = argb
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = rOuter * 0.11f
        isAntiAlias = true
        isFakeBoldText = true
    }
    val labels = listOf(0 to "00", 6 to "06", 12 to "12", 18 to "18")
    labels.forEach { (h, s) ->
        val p = pointAt(center, rOuter * 0.78f, hourToRadians(h.toDouble()))
        drawContext.canvas.nativeCanvas.drawText(s, p.x, p.y + paint.textSize * 0.35f, paint)
    }
}

private fun localHourOfDay(ms: Long): Double {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0 + c.get(Calendar.SECOND) / 3600.0
}

private fun formatHour(hour: Double): String {
    var h = hour % 24.0
    if (h < 0) h += 24.0
    val hh = h.toInt()
    val mm = ((h - hh) * 60.0).toInt()
    return "%02d:%02d".format(hh, mm)
}
