package com.t1dm.core.design

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp

/** CGM sensor icon, centred/scaled to `size`; [primary] theme accent, [ink] neutral foreground. */

private fun Color.mix(other: Color, t: Float): Color = lerp(this, other, t)

fun DrawScope.drawCgmSensor(primary: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = minOf(w, h) * 0.5f

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
    drawCircle(
        primary.mix(Color.White, 0.18f).copy(alpha = 0.5f),
        radius = domeR * 0.70f,
        center = Offset(cx, cy),
        style = Stroke(width = w * 0.005f),
    )
    drawCircle(
        primary.mix(Color.White, 0.55f),
        radius = domeR * 0.22f,
        center = Offset(cx, cy),
    )

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
