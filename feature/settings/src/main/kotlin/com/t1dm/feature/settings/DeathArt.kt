package com.t1dm.feature.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.sin

/** Centred, scaled to `size`; [primary]=theme accent, [accent]=colorScheme.error, [ink]=fg. */

private fun Color.toward(other: Color, t: Float): Color = lerp(this, other, t)
private val Color.deep get() = toward(Color.Black, 0.62f)
private val Color.abyss get() = toward(Color.Black, 0.86f)

/** [phase] is a clock in [0, 2π); 0f is still. */
fun DrawScope.drawReaper(phase: Float, primary: Color, accent: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val floatY = sin(phase) * h * 0.018f
    val swayDeg = sin(phase * 0.5f) * 2.2f
    val breath = 0.88f + 0.12f * ((sin(phase * 0.8f) + 1f) * 0.5f)

    val shoulderY = h * 0.34f
    val headCy = h * 0.235f

    translate(top = floatY) {
        rotate(swayDeg, pivot = Offset(cx, shoulderY)) {
            drawCircle(
                accent.copy(alpha = 0.16f * breath),
                radius = w * 0.205f,
                center = Offset(cx, headCy),
                style = Stroke(width = w * 0.006f),
            )
            drawCircle(
                accent.copy(alpha = 0.06f * breath),
                radius = w * 0.185f,
                center = Offset(cx, headCy),
            )

            val hemY = h * 0.90f
            val halfShoulder = w * 0.205f
            val halfHem = w * 0.345f
            val hoodTopY = h * 0.085f

            val robe = Path().apply {
                moveTo(cx - halfShoulder, shoulderY)
                cubicTo(
                    cx - halfShoulder * 1.12f, shoulderY + (hemY - shoulderY) * 0.48f,
                    cx - halfHem * 1.02f, hemY - h * 0.07f,
                    cx - halfHem, hemY,
                )
                lineTo(cx - halfHem * 0.58f, hemY - h * 0.035f)
                lineTo(cx - halfHem * 0.24f, hemY - h * 0.008f)
                lineTo(cx, hemY - h * 0.045f)
                lineTo(cx + halfHem * 0.24f, hemY - h * 0.008f)
                lineTo(cx + halfHem * 0.58f, hemY - h * 0.035f)
                lineTo(cx + halfHem, hemY)
                cubicTo(
                    cx + halfHem * 1.02f, hemY - h * 0.07f,
                    cx + halfShoulder * 1.12f, shoulderY + (hemY - shoulderY) * 0.48f,
                    cx + halfShoulder, shoulderY,
                )
                cubicTo(
                    cx + halfShoulder, shoulderY - h * 0.15f,
                    cx + halfShoulder * 0.52f, hoodTopY,
                    cx, hoodTopY,
                )
                cubicTo(
                    cx - halfShoulder * 0.52f, hoodTopY,
                    cx - halfShoulder, shoulderY - h * 0.15f,
                    cx - halfShoulder, shoulderY,
                )
                close()
            }
            val robeGrad = Brush.verticalGradient(
                colors = listOf(
                    ink.toward(primary, 0.10f),
                    ink.deep,
                    ink.abyss,
                ),
                startY = hoodTopY,
                endY = hemY,
            )
            drawPath(robe, robeGrad, alpha = breath)
            drawPath(robe, accent.copy(alpha = 0.22f * breath), style = Stroke(width = w * 0.006f))

            val creaseP = ink.abyss.copy(alpha = 0.55f * breath)
            val ridgeP = ink.toward(primary, 0.22f).copy(alpha = 0.35f * breath)
            val creasePen = Stroke(width = w * 0.012f, cap = StrokeCap.Round)
            val ridgePen = Stroke(width = w * 0.008f, cap = StrokeCap.Round)
            for (s in -1..1) {
                val ox = s * halfShoulder * 0.55f
                val fold = Path().apply {
                    moveTo(cx + ox * 0.5f, shoulderY + h * 0.02f)
                    cubicTo(
                        cx + ox, shoulderY + (hemY - shoulderY) * 0.4f,
                        cx + ox * 1.3f, hemY - h * 0.18f,
                        cx + ox * 1.15f, hemY - h * 0.03f,
                    )
                }
                drawPath(fold, creaseP, style = creasePen)
            }
            for (s in intArrayOf(-1, 1)) {
                val ox = s * halfShoulder * 0.28f
                val ridge = Path().apply {
                    moveTo(cx + ox, shoulderY + h * 0.04f)
                    cubicTo(
                        cx + ox * 1.4f, shoulderY + (hemY - shoulderY) * 0.45f,
                        cx + ox * 1.7f, hemY - h * 0.16f,
                        cx + ox * 1.5f, hemY - h * 0.05f,
                    )
                }
                drawPath(ridge, ridgeP, style = ridgePen)
            }

            val faceR = w * 0.108f
            drawOval(
                Brush.radialGradient(
                    colors = listOf(Color.Black, ink.abyss),
                    center = Offset(cx, headCy),
                    radius = faceR * 1.4f,
                ),
                topLeft = Offset(cx - faceR * 0.86f, headCy - faceR * 1.15f),
                size = Size(faceR * 1.72f, faceR * 2.3f),
                alpha = 0.92f * breath,
            )
            val glint = primary.toward(Color.White, 0.3f).copy(alpha = (0.55f + 0.35f * sin(phase)).coerceIn(0f, 1f) * breath)
            drawCircle(glint, radius = faceR * 0.13f, center = Offset(cx - faceR * 0.34f, headCy - faceR * 0.05f))
            drawCircle(glint, radius = faceR * 0.13f, center = Offset(cx + faceR * 0.34f, headCy - faceR * 0.05f))

            val snathTop = Offset(cx + w * 0.30f, h * 0.085f)
            val snathBot = Offset(cx + w * 0.035f, h * 0.945f)
            val snath = Path().apply {
                moveTo(snathTop.x, snathTop.y)
                quadraticTo(cx + w * 0.24f, h * 0.55f, snathBot.x, snathBot.y)
            }
            drawPath(snath, ink.abyss.copy(alpha = 0.9f * breath), style = Stroke(width = w * 0.022f, cap = StrokeCap.Round))
            drawPath(snath, ink.toward(primary, 0.35f).copy(alpha = 0.45f * breath), style = Stroke(width = w * 0.007f, cap = StrokeCap.Round))

            val gripX = cx + w * 0.185f
            val gripY = h * 0.44f
            val bonePen = Stroke(width = w * 0.010f, cap = StrokeCap.Round)
            val bone = ink.toward(Color.White, 0.15f).copy(alpha = 0.85f * breath)
            for (f in 0..3) {
                val fy = gripY + f * h * 0.028f
                drawLine(bone, Offset(gripX - w * 0.03f, fy), Offset(gripX + w * 0.02f, fy + h * 0.006f), strokeWidth = w * 0.010f, cap = StrokeCap.Round)
            }
            drawLine(bone, Offset(gripX - w * 0.035f, gripY - h * 0.01f), Offset(gripX - w * 0.045f, gripY + h * 0.09f), strokeWidth = w * 0.012f, cap = StrokeCap.Round)

            val bx = snathTop.x
            val by = snathTop.y + h * 0.005f
            val blade = Path().apply {
                moveTo(bx, by)
                cubicTo(
                    bx - w * 0.28f, by - h * 0.02f,
                    bx - w * 0.46f, by + h * 0.07f,
                    bx - w * 0.40f, by + h * 0.205f,
                )
                cubicTo(
                    bx - w * 0.30f, by + h * 0.10f,
                    bx - w * 0.12f, by + h * 0.05f,
                    bx, by,
                )
                close()
            }
            drawPath(
                blade,
                Brush.linearGradient(
                    colors = listOf(primary.toward(Color.White, 0.35f), ink.deep, ink.abyss),
                    start = Offset(bx - w * 0.40f, by),
                    end = Offset(bx, by + h * 0.20f),
                ),
                alpha = breath,
            )
            val edge = Path().apply {
                moveTo(bx, by)
                cubicTo(
                    bx - w * 0.28f, by - h * 0.02f,
                    bx - w * 0.46f, by + h * 0.07f,
                    bx - w * 0.40f, by + h * 0.205f,
                )
            }
            drawPath(edge, primary.copy(alpha = 0.9f * breath), style = Stroke(width = w * 0.008f, cap = StrokeCap.Round))
            drawPath(blade, ink.abyss.copy(alpha = 0.4f * breath), style = Stroke(width = w * 0.004f))
        }
    }
}

/** Pivots [swingDeg] degrees about the top mount; 0 = at rest. */
fun DrawScope.drawBell(swingDeg: Float, primary: Color, accent: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val pivot = Offset(cx, h * 0.115f)

    val timber = ink.deep
    val postW = w * 0.028f
    drawLine(timber, Offset(w * 0.16f, h * 0.06f), Offset(w * 0.16f, h * 0.30f), strokeWidth = postW * 1.4f, cap = StrokeCap.Round)
    drawLine(timber, Offset(w * 0.84f, h * 0.06f), Offset(w * 0.84f, h * 0.30f), strokeWidth = postW * 1.4f, cap = StrokeCap.Round)
    val yoke = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = w * 0.20f, top = h * 0.055f, right = w * 0.80f, bottom = h * 0.115f,
                radiusX = h * 0.02f, radiusY = h * 0.02f,
            ),
        )
    }
    drawPath(
        yoke,
        Brush.verticalGradient(listOf(ink.toward(primary, 0.15f), ink.deep), startY = h * 0.055f, endY = h * 0.115f),
    )
    drawPath(yoke, ink.abyss.copy(alpha = 0.6f), style = Stroke(width = w * 0.004f))

    rotate(swingDeg, pivot) {
        val shoulderY = h * 0.42f
        val crownY = h * 0.30f
        val lipY = h * 0.80f
        val soundbowY = h * 0.735f
        val halfTop = w * 0.165f
        val halfBow = w * 0.28f
        val halfMouth = w * 0.315f

        drawLine(accent.toward(ink, 0.4f), pivot, Offset(cx, crownY), strokeWidth = w * 0.014f, cap = StrokeCap.Round)
        for (s in intArrayOf(-1, 1)) {
            val ear = Path().apply {
                moveTo(cx + s * w * 0.02f, crownY)
                cubicTo(
                    cx + s * w * 0.10f, crownY - h * 0.02f,
                    cx + s * w * 0.10f, h * 0.16f,
                    cx + s * w * 0.045f, h * 0.15f,
                )
            }
            drawPath(ear, ink.toward(primary, 0.2f), style = Stroke(width = w * 0.02f, cap = StrokeCap.Round))
        }

        val body = Path().apply {
            moveTo(cx - halfTop, shoulderY)
            cubicTo(
                cx - halfTop * 1.08f, shoulderY + (soundbowY - shoulderY) * 0.55f,
                cx - halfBow * 1.02f, soundbowY - h * 0.02f,
                cx - halfBow, soundbowY,
            )
            cubicTo(
                cx - halfMouth * 0.99f, soundbowY + (lipY - soundbowY) * 0.5f,
                cx - halfMouth, lipY - h * 0.01f,
                cx - halfMouth, lipY,
            )
            lineTo(cx + halfMouth, lipY)
            cubicTo(
                cx + halfMouth, lipY - h * 0.01f,
                cx + halfMouth * 0.99f, soundbowY + (lipY - soundbowY) * 0.5f,
                cx + halfBow, soundbowY,
            )
            cubicTo(
                cx + halfBow * 1.02f, soundbowY - h * 0.02f,
                cx + halfTop * 1.08f, shoulderY + (soundbowY - shoulderY) * 0.55f,
                cx + halfTop, shoulderY,
            )
            cubicTo(cx + halfTop, crownY, cx - halfTop, crownY, cx - halfTop, shoulderY)
            close()
        }
        drawPath(
            body,
            Brush.verticalGradient(
                colors = listOf(
                    ink.toward(primary, 0.32f),
                    ink.toward(primary, 0.06f),
                    ink.deep,
                    ink.abyss,
                ),
                startY = crownY,
                endY = lipY,
            ),
        )
        drawPath(body, ink.abyss.copy(alpha = 0.7f), style = Stroke(width = w * 0.006f))

        drawLine(
            accent.toward(ink, 0.25f).copy(alpha = 0.7f),
            Offset(cx - halfBow, soundbowY), Offset(cx + halfBow, soundbowY),
            strokeWidth = w * 0.008f,
        )

        drawLine(
            accent.toward(ink, 0.15f),
            Offset(cx - halfMouth * 1.02f, lipY), Offset(cx + halfMouth * 1.02f, lipY),
            strokeWidth = w * 0.018f, cap = StrokeCap.Round,
        )
        drawLine(ink.abyss, Offset(cx, crownY + h * 0.02f), Offset(cx, soundbowY + h * 0.02f), strokeWidth = w * 0.008f, cap = StrokeCap.Round)
        drawCircle(
            Brush.radialGradient(
                colors = listOf(ink.toward(primary, 0.2f), ink.abyss),
                center = Offset(cx - w * 0.01f, soundbowY + h * 0.03f),
                radius = w * 0.06f,
            ),
            radius = w * 0.05f,
            center = Offset(cx, soundbowY + h * 0.045f),
        )
    }
}

/** [phase] is a clock in [0, 2π); 0f is still. */
fun DrawScope.drawSkull(phase: Float, primary: Color, accent: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val breath = 0.94f + 0.06f * ((sin(phase * 0.6f) + 1f) * 0.5f)

    val crownY = h * 0.075f
    val parietalY = h * 0.26f
    val templeY = h * 0.375f
    val cheekY = h * 0.505f
    val jawBaseY = h * 0.785f
    val chinY = h * 0.845f
    val parietalHalf = w * 0.325f
    val templeHalf = w * 0.275f
    val cheekHalf = w * 0.30f
    val jawHalf = w * 0.165f
    val domeCtrl = w * 0.15f

    val skull = Path().apply {
        moveTo(cx - parietalHalf, parietalY)
        cubicTo(cx - parietalHalf * 0.98f, crownY + h * 0.015f, cx - domeCtrl, crownY, cx, crownY)
        cubicTo(cx + domeCtrl, crownY, cx + parietalHalf * 0.98f, crownY + h * 0.015f, cx + parietalHalf, parietalY)
        cubicTo(cx + parietalHalf, parietalY + h * 0.055f, cx + templeHalf, templeY - h * 0.025f, cx + templeHalf, templeY)
        cubicTo(cx + templeHalf, templeY + h * 0.045f, cx + cheekHalf, cheekY - h * 0.055f, cx + cheekHalf, cheekY)
        cubicTo(cx + cheekHalf, cheekY + h * 0.10f, cx + jawHalf, jawBaseY - h * 0.06f, cx + jawHalf, jawBaseY)
        cubicTo(cx + jawHalf, jawBaseY + h * 0.035f, cx + jawHalf * 0.5f, chinY, cx, chinY)
        cubicTo(cx - jawHalf * 0.5f, chinY, cx - jawHalf, jawBaseY + h * 0.035f, cx - jawHalf, jawBaseY)
        cubicTo(cx - jawHalf, jawBaseY - h * 0.06f, cx - cheekHalf, cheekY + h * 0.10f, cx - cheekHalf, cheekY)
        cubicTo(cx - cheekHalf, cheekY - h * 0.055f, cx - templeHalf, templeY + h * 0.045f, cx - templeHalf, templeY)
        cubicTo(cx - templeHalf, templeY - h * 0.025f, cx - parietalHalf, parietalY + h * 0.055f, cx - parietalHalf, parietalY)
        close()
    }
    drawPath(
        skull,
        Brush.radialGradient(
            colors = listOf(
                ink.toward(Color.White, 0.14f),
                ink,
                ink.deep,
                ink.abyss,
            ),
            center = Offset(cx, h * 0.30f),
            radius = w * 0.62f,
        ),
        alpha = breath,
    )
    val rim = Path().apply {
        moveTo(cx - parietalHalf, parietalY)
        cubicTo(cx - parietalHalf * 0.98f, crownY + h * 0.015f, cx - domeCtrl, crownY, cx, crownY)
        cubicTo(cx + domeCtrl, crownY, cx + parietalHalf * 0.98f, crownY + h * 0.015f, cx + parietalHalf, parietalY)
    }
    drawPath(rim, primary.copy(alpha = 0.42f * breath), style = Stroke(width = w * 0.006f, cap = StrokeCap.Round))
    drawPath(skull, ink.abyss.copy(alpha = 0.5f), style = Stroke(width = w * 0.005f))

    for (s in intArrayOf(-1, 1)) {
        drawOval(
            ink.abyss.copy(alpha = 0.16f * breath),
            topLeft = Offset(cx + s * w * 0.205f - w * 0.055f, h * 0.30f),
            size = Size(w * 0.11f, h * 0.12f),
        )
    }

    val browY = h * 0.372f
    val browCrest = Path().apply {
        moveTo(cx - w * 0.275f, browY + h * 0.012f)
        cubicTo(cx - w * 0.17f, browY - h * 0.02f, cx - w * 0.055f, browY - h * 0.004f, cx, browY + h * 0.014f)
        cubicTo(cx + w * 0.055f, browY - h * 0.004f, cx + w * 0.17f, browY - h * 0.02f, cx + w * 0.275f, browY + h * 0.012f)
    }
    drawPath(browCrest, ink.toward(Color.White, 0.12f).copy(alpha = 0.4f * breath), style = Stroke(width = w * 0.011f, cap = StrokeCap.Round))

    for (s in intArrayOf(-1, 1)) {
        val zyg = Path().apply {
            moveTo(cx + s * w * 0.265f, cheekY - h * 0.03f)
            quadraticTo(cx + s * w * 0.205f, cheekY + h * 0.05f, cx + s * w * 0.10f, cheekY + h * 0.085f)
        }
        drawPath(zyg, ink.toward(Color.White, 0.08f).copy(alpha = 0.28f * breath), style = Stroke(width = w * 0.013f, cap = StrokeCap.Round))
    }

    val eyeCy = h * 0.44f
    val sockHalfW = w * 0.115f
    val sockTop = h * 0.385f
    val sockBot = h * 0.505f
    val eyeDx = w * 0.15f
    val ember = sin(phase * 0.5f).let { it * it }
    val glowAlpha = 0.24f + 0.5f * ember
    val glowR = sockHalfW * (0.9f + 0.5f * ember)
    val emberCore = accent.toward(Color.White, 0.45f)
    for (s in intArrayOf(-1, 1)) {
        val ecx = cx + s * eyeDx
        val outerX = ecx - s * sockHalfW
        val innerX = ecx + s * sockHalfW
        val socket = Path().apply {
            moveTo(outerX, sockTop + h * 0.006f)
            quadraticTo(ecx, sockTop - h * 0.014f, innerX, sockTop + h * 0.012f)
            quadraticTo(innerX - s * sockHalfW * 0.05f, sockBot - h * 0.03f, ecx + s * sockHalfW * 0.28f, sockBot)
            quadraticTo(outerX + s * sockHalfW * 0.35f, sockBot - h * 0.004f, outerX, sockTop + h * 0.006f)
            close()
        }
        drawPath(
            socket,
            Brush.radialGradient(
                colors = listOf(Color.Black, ink.abyss, ink.abyss.copy(alpha = 0f)),
                center = Offset(ecx, eyeCy),
                radius = sockHalfW * 1.5f,
            ),
            alpha = breath,
        )
        drawCircle(
            Brush.radialGradient(
                colors = listOf(
                    emberCore.copy(alpha = glowAlpha * breath),
                    primary.toward(accent, 0.4f).copy(alpha = glowAlpha * 0.65f * breath),
                    primary.copy(alpha = 0f),
                ),
                center = Offset(ecx, eyeCy),
                radius = glowR,
            ),
            radius = glowR,
            center = Offset(ecx, eyeCy),
        )
        drawCircle(
            emberCore.copy(alpha = (0.5f + 0.4f * ember) * breath),
            radius = w * 0.013f * (1f + 0.35f * ember),
            center = Offset(ecx, eyeCy),
        )
        drawPath(socket, ink.abyss.copy(alpha = 0.5f), style = Stroke(width = w * 0.004f))
    }

    val nasalTop = h * 0.53f
    val nasalBot = h * 0.635f
    val nasal = Path().apply {
        moveTo(cx, nasalTop + h * 0.012f)
        cubicTo(cx - w * 0.012f, nasalTop - h * 0.004f, cx - w * 0.05f, nasalTop + h * 0.002f, cx - w * 0.052f, nasalTop + h * 0.03f)
        cubicTo(cx - w * 0.054f, nasalBot - h * 0.04f, cx - w * 0.022f, nasalBot - h * 0.012f, cx, nasalBot)
        cubicTo(cx + w * 0.022f, nasalBot - h * 0.012f, cx + w * 0.054f, nasalBot - h * 0.04f, cx + w * 0.052f, nasalTop + h * 0.03f)
        cubicTo(cx + w * 0.05f, nasalTop + h * 0.002f, cx + w * 0.012f, nasalTop - h * 0.004f, cx, nasalTop + h * 0.012f)
        close()
    }
    drawPath(nasal, ink.abyss.copy(alpha = 0.92f * breath))
    drawPath(nasal, ink.abyss.copy(alpha = 0.5f), style = Stroke(width = w * 0.003f))

    val teethTop = h * 0.66f
    val teethBot = h * 0.75f
    val teethHalf = w * 0.14f
    val gum = Path().apply {
        moveTo(cx - teethHalf, teethTop - h * 0.004f)
        quadraticTo(cx, teethTop - h * 0.022f, cx + teethHalf, teethTop - h * 0.004f)
        lineTo(cx + teethHalf, teethBot)
        quadraticTo(cx, teethBot + h * 0.014f, cx - teethHalf, teethBot)
        close()
    }
    drawPath(
        gum,
        Brush.verticalGradient(
            colors = listOf(ink.toward(Color.White, 0.11f), ink.deep),
            startY = teethTop, endY = teethBot,
        ),
        alpha = breath,
    )
    val divider = ink.abyss.copy(alpha = 0.72f * breath)
    val nTeeth = 7
    for (i in 1 until nTeeth) {
        val t = i.toFloat() / nTeeth
        val tx = cx - teethHalf + t * teethHalf * 2f
        val edge = (t - 0.5f) * 2f
        val topOff = edge * edge * h * 0.013f
        drawLine(divider, Offset(tx, teethTop + topOff), Offset(tx, teethBot - h * 0.004f), strokeWidth = w * 0.005f, cap = StrokeCap.Round)
    }
    drawPath(gum, ink.abyss.copy(alpha = 0.5f), style = Stroke(width = w * 0.004f, join = StrokeJoin.Round))

    drawLine(
        ink.abyss.copy(alpha = 0.28f * breath),
        Offset(cx, browY + h * 0.01f), Offset(cx, nasalTop - h * 0.01f),
        strokeWidth = w * 0.003f,
    )
}

/** [phase] is a clock in [0,2π); 0f is still. Sheet is pale in every theme, not ink-derived. */
fun DrawScope.drawContract(phase: Float, primary: Color, accent: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val sway = sin(phase) * 0.9f

    val parchTop = Color.White.toward(primary, 0.05f).toward(accent, 0.02f)
    val parchBot = parchTop.toward(ink, 0.10f).toward(accent, 0.03f)
    val writing = Color.Black.toward(primary, 0.16f)
    val edge = ink.toward(Color.Black, 0.35f)

    val l = w * 0.145f
    val r = w * 0.855f
    val t = h * 0.115f
    val b = h * 0.885f
    val corner = w * 0.028f
    val deckle = w * 0.0045f
    val sheet = deckledSheet(l, t, r, b, corner, deckle)

    rotate(-2.4f + sway, pivot = Offset(cx, cy)) {
        translate(w * 0.020f, h * 0.024f) { drawPath(sheet, edge.copy(alpha = 0.20f)) }
        translate(w * 0.010f, h * 0.013f) { drawPath(sheet, edge.copy(alpha = 0.22f)) }

        drawPath(
            sheet,
            Brush.verticalGradient(colors = listOf(parchTop, parchBot), startY = t, endY = b),
        )
        drawPath(sheet, edge.copy(alpha = 0.35f), style = Stroke(width = w * 0.004f))
        // The vertical fold is the seam the rescind tear follows.
        drawPath(sheet, writing.copy(alpha = 0.06f), style = Stroke(width = w * 0.0025f))
        drawLine(
            writing.copy(alpha = 0.07f),
            Offset(cx, t + h * 0.02f), Offset(cx, b - h * 0.02f),
            strokeWidth = w * 0.0025f,
        )

        val contentL = l + (r - l) * 0.09f
        val contentR = r - (r - l) * 0.09f
        val span = contentR - contentL

        val headY = t + (b - t) * 0.12f
        drawLine(
            writing.copy(alpha = 0.85f),
            Offset(contentL, headY), Offset(contentL + span * 0.58f, headY),
            strokeWidth = (b - t) * 0.022f, cap = StrokeCap.Round,
        )
        drawLine(
            writing.copy(alpha = 0.6f),
            Offset(contentL, headY + (b - t) * 0.05f), Offset(contentL + span * 0.36f, headY + (b - t) * 0.05f),
            strokeWidth = (b - t) * 0.012f, cap = StrokeCap.Round,
        )

        val ruleY = b - (b - t) * 0.155f
        val textTop = headY + (b - t) * 0.135f
        val textBot = ruleY - (b - t) * 0.06f
        val ends = floatArrayOf(1.0f, 0.94f, 0.99f, 0.63f, 1.0f, 0.9f, 0.97f, 0.52f)
        for (i in ends.indices) {
            val ly = textTop + (textBot - textTop) * (i.toFloat() / (ends.size - 1))
            drawLine(
                writing.copy(alpha = 0.62f),
                Offset(contentL, ly), Offset(contentL + span * ends[i], ly),
                strokeWidth = (b - t) * 0.006f, cap = StrokeCap.Round,
            )
        }

        val scrawl = Path().apply {
            val sy = ruleY - (b - t) * 0.018f
            val sx = contentL + span * 0.02f
            moveTo(sx, sy)
            cubicTo(sx + span * 0.06f, sy - (b - t) * 0.05f, sx + span * 0.10f, sy + (b - t) * 0.03f, sx + span * 0.16f, sy - (b - t) * 0.02f)
            cubicTo(sx + span * 0.20f, sy - (b - t) * 0.06f, sx + span * 0.26f, sy + (b - t) * 0.04f, sx + span * 0.34f, sy - (b - t) * 0.01f)
            cubicTo(sx + span * 0.40f, sy - (b - t) * 0.05f, sx + span * 0.46f, sy + (b - t) * 0.02f, sx + span * 0.5f, sy - (b - t) * 0.03f)
        }
        drawPath(scrawl, writing.toward(primary, 0.22f).copy(alpha = 0.82f), style = Stroke(width = (b - t) * 0.009f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(
            writing.copy(alpha = 0.5f),
            Offset(contentL, ruleY), Offset(contentL + span * 0.6f, ruleY),
            strokeWidth = (b - t) * 0.005f,
        )
        drawLine(writing.copy(alpha = 0.5f), Offset(contentL - span * 0.01f, ruleY - (b - t) * 0.02f), Offset(contentL + span * 0.02f, ruleY + (b - t) * 0.01f), strokeWidth = (b - t) * 0.005f)
        drawLine(writing.copy(alpha = 0.5f), Offset(contentL - span * 0.01f, ruleY + (b - t) * 0.01f), Offset(contentL + span * 0.02f, ruleY - (b - t) * 0.02f), strokeWidth = (b - t) * 0.005f)

        val sealCx = contentR - span * 0.09f
        val sealCy = b - (b - t) * 0.155f
        val sealR = span * 0.085f
        drawCircle(edge.copy(alpha = 0.28f), radius = sealR * 1.02f, center = Offset(sealCx + w * 0.005f, sealCy + h * 0.006f))
        drawCircle(
            Brush.radialGradient(
                colors = listOf(accent.toward(Color.White, 0.28f), accent, accent.toward(Color.Black, 0.45f)),
                center = Offset(sealCx - sealR * 0.3f, sealCy - sealR * 0.3f),
                radius = sealR * 1.5f,
            ),
            radius = sealR,
            center = Offset(sealCx, sealCy),
        )
        drawCircle(accent.toward(Color.Black, 0.5f).copy(alpha = 0.7f), radius = sealR, center = Offset(sealCx, sealCy), style = Stroke(width = w * 0.004f))
        drawCircle(accent.toward(Color.White, 0.3f).copy(alpha = 0.45f), radius = sealR * 0.66f, center = Offset(sealCx, sealCy), style = Stroke(width = w * 0.0035f))
        val sigilInk = accent.toward(Color.White, 0.4f).copy(alpha = 0.5f)
        for (k in 0 until 5) {
            val a = (k.toFloat() / 5f) * (2f * kotlin.math.PI.toFloat()) - kotlin.math.PI.toFloat() / 2f
            drawLine(
                sigilInk,
                Offset(sealCx, sealCy),
                Offset(sealCx + kotlin.math.cos(a) * sealR * 0.5f, sealCy + kotlin.math.sin(a) * sealR * 0.5f),
                strokeWidth = w * 0.0035f, cap = StrokeCap.Round,
            )
        }
    }
}

/** Corners rounded by [corner]; each edge waves by at most [deckle]. */
private fun deckledSheet(l: Float, t: Float, r: Float, b: Float, corner: Float, deckle: Float): Path {
    val p = Path()
    val n = 12
    fun mix(a: Float, z: Float, f: Float) = a + (z - a) * f
    p.moveTo(l + corner, t)
    for (i in 1..n) {
        val f = i.toFloat() / n
        p.lineTo(mix(l + corner, r - corner, f), t - deckle * sin(i * 1.9f))
    }
    p.quadraticTo(r, t, r, t + corner)
    for (i in 1..n) {
        val f = i.toFloat() / n
        p.lineTo(r + deckle * sin(i * 2.1f + 1.3f), mix(t + corner, b - corner, f))
    }
    p.quadraticTo(r, b, r - corner, b)
    for (i in 1..n) {
        val f = i.toFloat() / n
        p.lineTo(mix(r - corner, l + corner, f), b + deckle * sin(i * 1.7f + 0.6f))
    }
    p.quadraticTo(l, b, l, b - corner)
    for (i in 1..n) {
        val f = i.toFloat() / n
        p.lineTo(l - deckle * sin(i * 2.0f + 2.1f), mix(b - corner, t + corner, f))
    }
    p.quadraticTo(l, t, l + corner, t)
    p.close()
    return p
}
