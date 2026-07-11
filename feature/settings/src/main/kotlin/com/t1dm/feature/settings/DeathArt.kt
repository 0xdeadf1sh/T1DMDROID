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

/**
 * The engraved iconography of DEATH mode — three memento-mori figures rendered wholly in Compose
 * Canvas, each drawn centred and scaled to fill the caller's `size`. The register is that of a
 * cathedral relief or a tarot frontispiece: solemn, funereal, never gory. Every hue is derived from
 * the three colour roles the caller passes so the dread renders in each theme's own key —
 * [primary] the theme accent (cold rim-light), [accent] the alarm-red ([androidx.compose.material3]
 * `colorScheme.error`, for haloes and blood-warmth), [ink] the neutral foreground (bone, linework).
 */

// A few tints derived from the roles; kept local so the figures read as one hand.
private fun Color.toward(other: Color, t: Float): Color = lerp(this, other, t)
private val Color.deep get() = toward(Color.Black, 0.62f)
private val Color.abyss get() = toward(Color.Black, 0.86f)

// ── the Angel of Death ───────────────────────────────────────────────────────────────────────────

/**
 * A hooded, robed Angel of Death bearing a scythe. [phase] (a clock in [0, 2π)) drives a slow
 * vertical float, a faint robe sway, and a breathing alpha; passing 0f yields a still figure. The
 * cowl falls into shadow, two cold glints where eyes would be; a thin [primary] rim-light rides the
 * crescent blade; a hairline [accent] halo hangs behind the hood.
 */
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
            // A cold votive halo behind the cowl — a hairline ring of alarm-red, barely there.
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

            // The robe silhouette: cowl crown down through the shoulders to a wide, riven hem.
            val robe = Path().apply {
                moveTo(cx - halfShoulder, shoulderY)
                cubicTo(
                    cx - halfShoulder * 1.12f, shoulderY + (hemY - shoulderY) * 0.48f,
                    cx - halfHem * 1.02f, hemY - h * 0.07f,
                    cx - halfHem, hemY,
                )
                // A frayed, uneven hem.
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
                // The hood, arching over the void where a face would be.
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

            // Layered folds: darker creases and a pair of cold highlight ridges.
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

            // The hollow of the cowl: a well of shadow with two faint, cold glints for eyes.
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

            // The scythe: a long snath crossing behind the shoulder, a crescent blade at its head.
            val snathTop = Offset(cx + w * 0.30f, h * 0.085f)
            val snathBot = Offset(cx + w * 0.035f, h * 0.945f)
            val snath = Path().apply {
                moveTo(snathTop.x, snathTop.y)
                quadraticTo(cx + w * 0.24f, h * 0.55f, snathBot.x, snathBot.y)
            }
            drawPath(snath, ink.abyss.copy(alpha = 0.9f * breath), style = Stroke(width = w * 0.022f, cap = StrokeCap.Round))
            drawPath(snath, ink.toward(primary, 0.35f).copy(alpha = 0.45f * breath), style = Stroke(width = w * 0.007f, cap = StrokeCap.Round))

            // A skeletal hand closed about the snath.
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
                // Convex back of the blade sweeping out to the point.
                cubicTo(
                    bx - w * 0.28f, by - h * 0.02f,
                    bx - w * 0.46f, by + h * 0.07f,
                    bx - w * 0.40f, by + h * 0.205f,
                )
                // Concave cutting edge returning to the heel.
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
            // The rim-light riding the back of the crescent.
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

// ── the passing bell ───────────────────────────────────────────────────────────────────────────────

/**
 * A heavy funeral bell hung in a yoke, tolling. It pivots [swingDeg] degrees about the top mount
 * (0 = at rest). Cast-metal shading falls from [primary]/[ink]; [accent] warms the crown and lip;
 * a defined soundbow and a hanging clapper give it weight.
 */
fun DrawScope.drawBell(swingDeg: Float, primary: Color, accent: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val pivot = Offset(cx, h * 0.115f)

    // The headstock and its posts stand fast while the bell swings beneath.
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

        // Canons: the looped ears the bell hangs from, and the staple pinning it to the yoke.
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

        // The bell body: shoulder, waist, flaring to the soundbow and mouth.
        val body = Path().apply {
            moveTo(cx - halfTop, shoulderY)
            cubicTo(
                cx - halfTop * 1.08f, shoulderY + (soundbowY - shoulderY) * 0.55f,
                cx - halfBow * 1.02f, soundbowY - h * 0.02f,
                cx - halfBow, soundbowY,
            )
            // The soundbow thickens outward to the flared mouth.
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
            // The domed crown closing the shoulder.
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

        // The incised soundbow band.
        drawLine(
            accent.toward(ink, 0.25f).copy(alpha = 0.7f),
            Offset(cx - halfBow, soundbowY), Offset(cx + halfBow, soundbowY),
            strokeWidth = w * 0.008f,
        )

        // The flared lip, and the clapper hung within it.
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

// ── the skull ────────────────────────────────────────────────────────────────────────────────────

/**
 * A large, symmetric human skull, frontal. Tonal modelling falls from [ink] via a soft radial
 * gradient; a cold [primary] rim-light rides the crown, a whisper of [accent] warms the sockets.
 * [phase] drives a barely-there breathing/flicker so it reads alive-yet-dead; passing 0f is still.
 */
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

    // The cranium: a broad rounded vault, a slight temporal pinch, the zygomatic flare of the
    // cheekbones, then a tapering maxilla — the proportions of an ordinary human skull, not a creature.
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
    // A cold rim-light along the crown; the bone's own dark contour beneath.
    val rim = Path().apply {
        moveTo(cx - parietalHalf, parietalY)
        cubicTo(cx - parietalHalf * 0.98f, crownY + h * 0.015f, cx - domeCtrl, crownY, cx, crownY)
        cubicTo(cx + domeCtrl, crownY, cx + parietalHalf * 0.98f, crownY + h * 0.015f, cx + parietalHalf, parietalY)
    }
    drawPath(rim, primary.copy(alpha = 0.42f * breath), style = Stroke(width = w * 0.006f, cap = StrokeCap.Round))
    drawPath(skull, ink.abyss.copy(alpha = 0.5f), style = Stroke(width = w * 0.005f))

    // Temporal hollows — the faint shadowed flats above the cheekbones.
    for (s in intArrayOf(-1, 1)) {
        drawOval(
            ink.abyss.copy(alpha = 0.16f * breath),
            topLeft = Offset(cx + s * w * 0.205f - w * 0.055f, h * 0.30f),
            size = Size(w * 0.11f, h * 0.12f),
        )
    }

    // The brow ridge: a supraorbital crest, lit along its ridge and dipping at the glabella.
    val browY = h * 0.372f
    val browCrest = Path().apply {
        moveTo(cx - w * 0.275f, browY + h * 0.012f)
        cubicTo(cx - w * 0.17f, browY - h * 0.02f, cx - w * 0.055f, browY - h * 0.004f, cx, browY + h * 0.014f)
        cubicTo(cx + w * 0.055f, browY - h * 0.004f, cx + w * 0.17f, browY - h * 0.02f, cx + w * 0.275f, browY + h * 0.012f)
    }
    drawPath(browCrest, ink.toward(Color.White, 0.12f).copy(alpha = 0.4f * breath), style = Stroke(width = w * 0.011f, cap = StrokeCap.Round))

    // Zygomatic ridges — a highlight riding each cheekbone.
    for (s in intArrayOf(-1, 1)) {
        val zyg = Path().apply {
            moveTo(cx + s * w * 0.265f, cheekY - h * 0.03f)
            quadraticTo(cx + s * w * 0.205f, cheekY + h * 0.05f, cx + s * w * 0.10f, cheekY + h * 0.085f)
        }
        drawPath(zyg, ink.toward(Color.White, 0.08f).copy(alpha = 0.28f * breath), style = Stroke(width = w * 0.013f, cap = StrokeCap.Round))
    }

    // The eye sockets: rounded-triangular orbits sunk into shadow, each cradling a breathing ember.
    val eyeCy = h * 0.44f
    val sockHalfW = w * 0.115f
    val sockTop = h * 0.385f
    val sockBot = h * 0.505f
    val eyeDx = w * 0.15f
    // A single slow breath of the glow: zero at phase 0 (calm, low), swelling to full at mid-cycle.
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
        // The ember: a warm radial bloom, brightest at its core, pulsing off [phase].
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

    // The nasal aperture — an inverted heart, lobed at the bridge, tapering to a point below.
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

    // The maxilla and its even upper row of teeth.
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
        // A shallow arch: the row rises a touch toward the corners.
        val edge = (t - 0.5f) * 2f
        val topOff = edge * edge * h * 0.013f
        drawLine(divider, Offset(tx, teethTop + topOff), Offset(tx, teethBot - h * 0.004f), strokeWidth = w * 0.005f, cap = StrokeCap.Round)
    }
    drawPath(gum, ink.abyss.copy(alpha = 0.5f), style = Stroke(width = w * 0.004f, join = StrokeJoin.Round))

    // A faint sagittal line down the nasal bridge, lending the bone its symmetry.
    drawLine(
        ink.abyss.copy(alpha = 0.28f * breath),
        Offset(cx, browY + h * 0.01f), Offset(cx, nasalTop - h * 0.01f),
        strokeWidth = w * 0.003f,
    )
}
