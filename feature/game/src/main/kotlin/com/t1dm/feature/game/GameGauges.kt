package com.t1dm.feature.game

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Degrees of arc, opening downward. */
private const val SWEEP_DEG = 225f
private const val START_DEG = 157.5f

/** Not composables: [CarFrame] reads in draw phase; 60/s needle invalidates draw, no recompose. */
internal fun DrawScope.drawGauges(
    f: CarFrame,
    centreX: Float,
    bottomY: Float,
    radius: Float,
    measurer: TextMeasurer,
    ink: Color,
    accent: Color,
    warn: Color,
) {
    val gap = radius * 2.5f
    drawDial(
        centre = Offset(centreX - gap * 0.5f, bottomY - radius),
        radius = radius,
        frac = f.speedMs / SPEED_FULL_SCALE_MS,
        // No redline on the speedometer: the limiter is not a hazard.
        measurer = measurer, ink = ink, needle = accent, warn = warn, redlineFrom = 1.1f,
    )
    drawDial(
        centre = Offset(centreX + gap * 0.5f, bottomY - radius),
        radius = radius,
        frac = f.rpm / RPM_FULL_SCALE,
        measurer = measurer, ink = ink, needle = accent, warn = warn,
        redlineFrom = TOP_RPM / RPM_FULL_SCALE,
    )
}

/** Spans the PLOT's width (x axis is time); sits over trace covered. See [CarFrame.progress]. */
internal fun DrawScope.drawProgress(
    progress: Float,
    left: Float,
    right: Float,
    top: Float,
    thickness: Float,
    ink: Color,
    accent: Color,
) {
    val w = right - left
    if (w <= 1f || thickness <= 0f) return
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val radius = CornerRadius(thickness * 0.5f)
    drawRoundRect(
        ink.copy(alpha = 0.18f),
        topLeft = Offset(left, top),
        size = Size(w, thickness),
        cornerRadius = radius,
    )
    if (p > 0f) {
        drawRoundRect(
            accent,
            topLeft = Offset(left, top),
            // Never thinner than tall: a rounded rect narrower than its corner radius pinches.
            size = Size((w * p).coerceAtLeast(thickness), thickness),
            cornerRadius = radius,
        )
    }
}

private fun DrawScope.drawDial(
    centre: Offset,
    radius: Float,
    frac: Float,
    measurer: TextMeasurer,
    ink: Color,
    needle: Color,
    warn: Color,
    redlineFrom: Float,
) {
    val f = frac.coerceIn(0f, 1f)
    val box = Size(radius * 2f, radius * 2f)
    val topLeft = Offset(centre.x - radius, centre.y - radius)

    // Translucent, so the track still reads through the face.
    drawCircle(ink.copy(alpha = 0.22f), radius, centre)
    drawArc(
        color = ink.copy(alpha = 0.45f),
        startAngle = START_DEG, sweepAngle = SWEEP_DEG, useCenter = false,
        topLeft = topLeft, size = box,
        style = Stroke(width = radius * 0.09f, cap = StrokeCap.Round),
    )
    if (redlineFrom < 1f) {
        drawArc(
            color = warn.copy(alpha = 0.75f),
            startAngle = START_DEG + SWEEP_DEG * redlineFrom,
            sweepAngle = SWEEP_DEG * (1f - redlineFrom),
            useCenter = false, topLeft = topLeft, size = box,
            style = Stroke(width = radius * 0.09f, cap = StrokeCap.Round),
        )
    }
    for (i in 0..10) {
        val a = (START_DEG + SWEEP_DEG * i / 10f) * PI.toFloat() / 180f
        val outer = radius * 0.86f
        val inner = if (i % 5 == 0) radius * 0.66f else radius * 0.76f
        drawLine(
            ink.copy(alpha = 0.55f),
            Offset(centre.x + cos(a) * inner, centre.y + sin(a) * inner),
            Offset(centre.x + cos(a) * outer, centre.y + sin(a) * outer),
            strokeWidth = radius * 0.045f,
        )
    }
    val na = (START_DEG + SWEEP_DEG * f) * PI.toFloat() / 180f
    drawLine(
        if (f >= redlineFrom) warn else needle,
        Offset(centre.x - cos(na) * radius * 0.14f, centre.y - sin(na) * radius * 0.14f),
        Offset(centre.x + cos(na) * radius * 0.72f, centre.y + sin(na) * radius * 0.72f),
        strokeWidth = radius * 0.075f,
        cap = StrokeCap.Round,
    )
    drawCircle(needle, radius * 0.10f, centre)

}
