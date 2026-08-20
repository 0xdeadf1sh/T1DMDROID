package com.t1dm.core.design

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp

/**
 * The CGM SENSOR iconography (the companion to [drawEsp32Watch] for the Watch panel): the round,
 * skin-worn AiDEX X / LinX transmitter rendered wholly in Compose Canvas, drawn centred and scaled to
 * fill the caller's `size`. A soft adhesive patch, a raised circular transmitter dome, a lit centre,
 * and three BLE broadcast arcs rising off the dome — an honest, still portrait of the thing this whole
 * panel talks to. The hues derive from the two roles the caller passes so it renders in each theme's
 * own light: [primary] the theme accent (the dome, the lit centre, the broadcast arcs), [ink] the
 * neutral foreground (the adhesive patch).
 */

private fun Color.mix(other: Color, t: Float): Color = lerp(this, other, t)

fun DrawScope.drawCgmSensor(primary: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = minOf(w, h) * 0.5f

    // ── the adhesive patch: a soft, rounded-square skin pad the sensor sits on ────────────────────
    val padHalf = r * 0.74f
    val padColor = ink.mix(Color.White, 0.14f)
    drawRoundRect(
        Brush.verticalGradient(
            colors = listOf(padColor.copy(alpha = 0.95f), ink.mix(Color.Black, 0.10f).copy(alpha = 0.95f)),
            startY = cy - padHalf, endY = cy + padHalf,
        ),
        topLeft = Offset(cx - padHalf, cy - padHalf),
        size = Size(padHalf * 2f, padHalf * 2f),
        cornerRadius = CornerRadius(padHalf * 0.42f, padHalf * 0.42f),
    )
    drawRoundRect(
        ink.mix(Color.Black, 0.35f).copy(alpha = 0.5f),
        topLeft = Offset(cx - padHalf, cy - padHalf),
        size = Size(padHalf * 2f, padHalf * 2f),
        cornerRadius = CornerRadius(padHalf * 0.42f, padHalf * 0.42f),
        style = Stroke(width = w * 0.006f),
    )

    // ── the transmitter dome: a raised circular case with a bevelled rim ──────────────────────────
    val domeR = r * 0.50f
    drawCircle(
        Brush.radialGradient(
            colors = listOf(primary.mix(Color.White, 0.30f), primary, primary.mix(Color.Black, 0.28f)),
            center = Offset(cx - domeR * 0.22f, cy - domeR * 0.22f),
            radius = domeR * 1.35f,
        ),
        radius = domeR,
        center = Offset(cx, cy),
    )
    drawCircle(
        primary.mix(Color.Black, 0.42f).copy(alpha = 0.7f),
        radius = domeR,
        center = Offset(cx, cy),
        style = Stroke(width = w * 0.008f),
    )
    // a subtle inner ring seam
    drawCircle(
        primary.mix(Color.White, 0.18f).copy(alpha = 0.5f),
        radius = domeR * 0.70f,
        center = Offset(cx, cy),
        style = Stroke(width = w * 0.005f),
    )
    // the lit centre (the sensing well)
    drawCircle(
        primary.mix(Color.White, 0.55f),
        radius = domeR * 0.22f,
        center = Offset(cx, cy),
    )

    // ── three BLE broadcast arcs rising off the upper-right of the dome ───────────────────────────
    val arcCx = cx + domeR * 0.30f
    val arcCy = cy - domeR * 0.30f
    for (i in 1..3) {
        val rad = domeR * (0.55f + i * 0.42f)
        drawArc(
            color = primary.mix(Color.White, 0.10f).copy(alpha = 0.85f - i * 0.20f),
            startAngle = -70f,
            sweepAngle = 55f,
            useCenter = false,
            topLeft = Offset(arcCx - rad, arcCy - rad),
            size = Size(rad * 2f, rad * 2f),
            style = Stroke(width = w * (0.014f - i * 0.002f)),
        )
    }
}
