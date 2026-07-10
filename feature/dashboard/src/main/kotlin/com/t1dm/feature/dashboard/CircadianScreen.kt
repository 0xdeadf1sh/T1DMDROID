package com.t1dm.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.model.PredictedTime
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Circadian Clock (item 22 / Phase 7D). Renders the selected model's decoded hour-of-day
 * belief ([PredictedTime]) as a 24-hour dial: a 12-wedge circular histogram (wedge k spans
 * `[2k, 2k+2)` h, its radius + opacity ∝ `probs[k]`) with an analog-clock HAND at
 * [PredictedTime.predictedHour] whose length + alpha ∝ [PredictedTime.resultantR] (the confidence /
 * resultant length). A readable clock face (hour ticks + 00/06/12/18 labels) underlays it, and the
 * local wall-clock hand is drawn faintly for contrast (ties to the 7A dual-time idea).
 *
 * The geometry mirrors T1DMAI `clock_face.py` / `utils.clock_wedge_geometry`: hour 0 at the top,
 * increasing clockwise, `resultantR` already computed in the Rust `decode_time`. When the belief is
 * absent (the descriptor has no time section, or the STUB backend serves the forecast) it states so
 * plainly — every empty state says WHY (ux-decisions.md).
 */
@Composable
fun CircadianScreen(
    predictedTime: PredictedTime?,
    realBackendAvailable: Boolean,
    /** Whether the selected model's descriptor even declares a time section (issue 7 — true null cause). */
    hasTimeSection: Boolean = true,
    /** Whether the forecast is still WARMING UP / collecting context (issue 7 — the real reason during warmup). */
    warmingUp: Boolean = false,
    /** Whether [predictedTime], when present, was formed on limited warmup context (⇒ low-confidence caveat). */
    lowContext: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // N1 — the "Circadian clock" title lives in the breadcrumb; no duplicate in-view header.
        Text(
            "The model's belief about what hour-of-day the current physiology resembles — a circular " +
                "histogram over 12 two-hour bins with a hand at its resultant hour.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (predictedTime == null) {
            EmptyCircadian(realBackendAvailable, hasTimeSection, warmingUp)
            return@Column
        }

        if (lowContext) LowContextCaveat()

        // A gentle 10-second tick so the local-time hand stays honest without a busy loop.
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
        ReadoutRow("Confidence (resultant R)", "%.2f".format(predictedTime.resultantR))
        ReadoutRow("Local time", formatHour(localHour))
        ReadoutRow("Model − local offset", formatOffset(predictedTime.predictedHour - localHour))
        ReadoutRow("Bins", "${predictedTime.nBins} × ${"%.0f".format(predictedTime.binHours)} h")
        if (predictedTime.resultantR < 0.05) {
            Text(
                "The belief is nearly uniform (R ≈ 0) — the model has no strong circadian preference this cycle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The four DISTINCT reasons the hour-of-day belief can be absent, each named plainly (issue 7 — the
 * old copy misattributed a warmup gap to "no time section"). Precedence: a stub backend has no probe
 * at all; then a descriptor without a time section; then warmup (transient, resolves as history
 * accrues); otherwise the probe ran but its output could not be decoded this cycle.
 */
@Composable
private fun EmptyCircadian(realBackendAvailable: Boolean, hasTimeSection: Boolean, warmingUp: Boolean) {
    val msg = when {
        !realBackendAvailable ->
            "Model time unavailable — the forecast is currently served by the fallback stub backend, " +
                "which carries no circadian probe."
        !hasTimeSection ->
            "Model time unavailable — the selected model's descriptor has no time section, so there is " +
                "no hour-of-day belief to render."
        warmingUp ->
            "Model time not available yet — still collecting context (warmup). The circadian probe " +
                "needs at least the model's minimum history (≈8 h of BG) before it can form a belief; " +
                "it will appear here, low-confidence at first, once enough real BG has accrued."
        else ->
            "Model time unavailable — the time probe ran this cycle but its output could not be decoded " +
                "into an hour-of-day belief."
    }
    Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Caveat shown above the dial when the belief was formed on limited warmup context (issues 7 & 9). */
@Composable
private fun LowContextCaveat() {
    Text(
        "Low-confidence — formed during warmup on limited history while the BG forecast is withheld. " +
            "This is a circadian-phase belief, not a glucose forecast; it firms up as more real BG accrues.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

/** A signed hour offset wrapped to `[-12, 12)` h, e.g. "+1:30" / "−2:00". */
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

        // 12 wedges (bin k over [2k, 2k+2) h): radius + opacity ∝ probs[k]/maxProb.
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

        // Hour ticks every 2 h; 00/06/12/18 labelled.
        for (h in 0 until 24 step 2) {
            val ang = hourToRadians(h.toDouble())
            val inner = pointAt(center, rOuter * 0.92f, ang)
            val outer = pointAt(center, rOuter, ang)
            drawLine(tickInk.copy(alpha = 0.5f), inner, outer, strokeWidth = if (h % 6 == 0) 3f else 1.5f)
        }
        drawFaceLabels(center, rOuter, tickInk.toArgb())

        // Local wall-clock hand (faint, for contrast).
        drawHand(center, rHist * 0.9f, hourToRadians(localHour), localHandColor, 4f)

        // The MODEL hand: length + alpha ∝ resultantR.
        val r = pt.resultantR.coerceIn(0.0, 1.0)
        val len = rHist * (0.30f + 0.65f * r.toFloat())
        drawHand(center, len, hourToRadians(pt.predictedHour), handColor.copy(alpha = (0.35f + 0.65f * r).toFloat()), 6f)
        drawCircle(handColor, radius = 7f, center = center)
    }
}

// hour 0 at top (12 o'clock), increasing clockwise on a 24-h dial.
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
