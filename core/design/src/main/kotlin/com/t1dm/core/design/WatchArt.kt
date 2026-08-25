package com.t1dm.core.design

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp

private fun Color.toward(other: Color, t: Float): Color = lerp(this, other, t)
private val Color.deep get() = toward(Color.Black, 0.52f)
private val Color.abyss get() = toward(Color.Black, 0.82f)

fun DrawScope.drawEsp32Watch(primary: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f

    val caseHalf = w * 0.28f
    val caseL = cx - caseHalf
    val caseR = cx + caseHalf
    val caseT = cy - caseHalf
    val caseB = cy + caseHalf

    val strapHalf = w * 0.165f
    val strapMetal = Brush.horizontalGradient(
        colors = listOf(ink.deep, ink.toward(primary, 0.06f), ink.deep),
        startX = cx - strapHalf, endX = cx + strapHalf,
    )
    for (up in intArrayOf(1, -1)) {
        val near = if (up == 1) caseT + caseHalf * 0.10f else caseB - caseHalf * 0.10f
        val far = if (up == 1) h * 0.03f else h * 0.97f
        val topY = minOf(near, far)
        val bandH = kotlin.math.abs(far - near)
        drawRoundRect(
            strapMetal,
            topLeft = Offset(cx - strapHalf, topY),
            size = Size(strapHalf * 2f, bandH),
            cornerRadius = CornerRadius(strapHalf * 0.55f, strapHalf * 0.55f),
        )
        drawRoundRect(
            ink.abyss.copy(alpha = 0.6f),
            topLeft = Offset(cx - strapHalf, topY),
            size = Size(strapHalf * 2f, bandH),
            cornerRadius = CornerRadius(strapHalf * 0.55f, strapHalf * 0.55f),
            style = Stroke(width = w * 0.004f),
        )
        val stitch = ink.toward(Color.White, 0.22f).copy(alpha = 0.5f)
        for (s in intArrayOf(-1, 1)) {
            val sx = cx + s * strapHalf * 0.72f
            val segs = 7
            for (k in 0 until segs) {
                val f0 = 0.06f + 0.88f * k / segs
                val f1 = f0 + 0.5f / segs
                drawLine(
                    stitch,
                    Offset(sx, topY + bandH * f0), Offset(sx, topY + bandH * f1),
                    strokeWidth = w * 0.004f, cap = StrokeCap.Round,
                )
            }
        }
        val holeY = if (up == 1) near - bandH * 0.06f else near + bandH * 0.06f
        drawCircle(ink.abyss.copy(alpha = 0.7f), radius = w * 0.012f, center = Offset(cx, holeY))
    }

    val pcb = lerp(Color(0xFF12351B), primary, 0.10f)
    val pad = Color(0xFFB08A2E)
    drawRoundRect(
        pcb,
        topLeft = Offset(cx - caseHalf * 0.5f, caseB - caseHalf * 0.06f),
        size = Size(caseHalf, caseHalf * 0.34f),
        cornerRadius = CornerRadius(w * 0.01f, w * 0.01f),
    )
    for (k in -1..1) {
        drawRoundRect(
            pad,
            topLeft = Offset(cx + k * caseHalf * 0.24f - w * 0.012f, caseB + caseHalf * 0.16f),
            size = Size(w * 0.024f, caseHalf * 0.12f),
            cornerRadius = CornerRadius(w * 0.004f, w * 0.004f),
        )
        drawLine(pad.copy(alpha = 0.6f), Offset(cx + k * caseHalf * 0.24f, caseB + caseHalf * 0.06f), Offset(cx + k * caseHalf * 0.24f, caseB + caseHalf * 0.16f), strokeWidth = w * 0.004f)
    }

    val btnMetal = Brush.horizontalGradient(listOf(ink.toward(Color.White, 0.18f), ink.deep), startX = caseR, endX = caseR + w * 0.05f)
    for (by in floatArrayOf(cy - caseHalf * 0.40f, cy + caseHalf * 0.28f)) {
        val bh = if (by < cy) caseHalf * 0.30f else caseHalf * 0.20f
        val dyc = by - cy
        val edgeX = cx + kotlin.math.sqrt((caseHalf * caseHalf - dyc * dyc).coerceAtLeast(0f))
        drawRoundRect(
            btnMetal,
            topLeft = Offset(edgeX - w * 0.006f, by - bh / 2f),
            size = Size(w * 0.045f, bh),
            cornerRadius = CornerRadius(w * 0.012f, w * 0.012f),
        )
        drawRoundRect(
            ink.abyss.copy(alpha = 0.55f),
            topLeft = Offset(edgeX - w * 0.006f, by - bh / 2f),
            size = Size(w * 0.045f, bh),
            cornerRadius = CornerRadius(w * 0.012f, w * 0.012f),
            style = Stroke(width = w * 0.003f),
        )
    }

    drawCircle(
        Color.Black.copy(alpha = 0.28f),
        radius = caseHalf,
        center = Offset(cx + w * 0.012f, cy + h * 0.016f),
    )
    drawCircle(
        Brush.linearGradient(
            colors = listOf(ink.toward(Color.White, 0.24f), ink, ink.deep, ink.abyss),
            start = Offset(caseL, caseT), end = Offset(caseR, caseB),
        ),
        radius = caseHalf,
        center = Offset(cx, cy),
    )
    drawCircle(
        primary.toward(Color.White, 0.3f).copy(alpha = 0.25f),
        radius = caseHalf,
        center = Offset(cx, cy),
        style = Stroke(width = w * 0.006f),
    )
    drawCircle(
        ink.abyss.copy(alpha = 0.8f),
        radius = caseHalf,
        center = Offset(cx, cy),
        style = Stroke(width = w * 0.004f),
    )

    val bezel = caseHalf * 0.20f
    val sRad = caseHalf - bezel
    val sL = cx - sRad
    val sT = cy - sRad
    val sR = cx + sRad
    val sB = cy + sRad
    val sW = sRad * 2f
    val sH = sRad * 2f
    clipPath(Path().apply { addOval(Rect(sL, sT, sR, sB)) }) {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF2C6BC0),
                0.55f to Color(0xFF6FA8DC),
                1f to Color(0xFFCFE3F5),
                startY = sT, endY = sB,
            ),
            topLeft = Offset(sL, sT),
            size = Size(sW, sH),
        )
        cloud(sL + sW * 0.28f, sT + sH * 0.24f, sW * 0.15f)
        cloud(sL + sW * 0.68f, sT + sH * 0.33f, sW * 0.11f)

        val ridge = Path().apply {
            moveTo(sL, sT + sH * 0.70f)
            cubicTo(
                sL + sW * 0.24f, sT + sH * 0.47f,
                sL + sW * 0.58f, sT + sH * 0.60f,
                sR, sT + sH * 0.74f,
            )
        }
        val hill = Path().apply {
            addPath(ridge)
            lineTo(sR, sB)
            lineTo(sL, sB)
            close()
        }
        drawPath(
            hill,
            Brush.verticalGradient(
                0f to Color(0xFF7CB342),
                0.5f to Color(0xFF558B2F),
                1f to Color(0xFF33691E),
                startY = sT + sH * 0.46f, endY = sB,
            ),
        )
        drawPath(ridge, Color.White.copy(alpha = 0.26f), style = Stroke(width = w * 0.004f, cap = StrokeCap.Round))
    }

    drawCircle(
        ink.toward(Color.White, 0.14f).copy(alpha = 0.35f),
        radius = sRad,
        center = Offset(cx, cy),
        style = Stroke(width = w * 0.003f),
    )
}

private fun DrawScope.cloud(x: Float, y: Float, r: Float) {
    val white = Color.White.copy(alpha = 0.90f)
    drawCircle(white, radius = r * 0.55f, center = Offset(x - r * 0.60f, y + r * 0.12f))
    drawCircle(white, radius = r * 0.78f, center = Offset(x, y))
    drawCircle(white, radius = r * 0.50f, center = Offset(x + r * 0.62f, y + r * 0.16f))
    drawCircle(white, radius = r * 0.44f, center = Offset(x + r * 0.16f, y - r * 0.34f))
}
