package com.t1dm.core.design

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The companion WATCH iconography: a DIY, ESP32-based wrist device rendered wholly in Compose Canvas,
 * drawn centred and scaled to fill the caller's `size`. Where [DeathArt] speaks the funereal, this
 * speaks the hobbyist-hardware key — brushed metal, a glowing OLED glanceable read-out, an honest
 * nod to the board beneath. Every hue derives from the three roles the caller passes so the device
 * renders in each theme's own light: [primary] the theme accent (the display's glow), [accent] the
 * alarm-red for a rising-fast flourish, [ink] the neutral foreground (the case and strap metal).
 */

private fun Color.toward(other: Color, t: Float): Color = lerp(this, other, t)
private val Color.deep get() = toward(Color.Black, 0.52f)
private val Color.abyss get() = toward(Color.Black, 0.82f)

// Seven-segment illumination map — which segments burn for each glyph on a faux OLED read-out.
private val SEG = mapOf(
    '0' to "abcdef",
    '1' to "bc",
    '2' to "abged",
    '3' to "abgcd",
    '4' to "fgbc",
    '5' to "afgcd",
    '6' to "afgedc",
    '7' to "abc",
    '8' to "abcdefg",
    '9' to "abcfgd",
)

/**
 * A large, dignified DIY smartwatch: a brushed rounded-square case, a glowing OLED display bearing a
 * glanceable BG read-out (a seven-segment figure, a rising trend arrow, a scrolling trace), side
 * buttons, a stitched wrist strap curving above and below, and a peek of PCB-green at the foot. Off
 * [phase] the display breathes and a data-blip advances along the trace so it feels alive and
 * "pushing every five minutes"; passing 0f yields a still, merely-lit device.
 */
fun DrawScope.drawEsp32Watch(phase: Float, primary: Color, accent: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val breath = 0.5f + 0.5f * sin(phase * 1.1f)          // 0..1 glow envelope
    val blip = ((phase / (2f * PI.toFloat())) % 1f + 1f) % 1f // 0..1 looping blip position

    val caseHalf = w * 0.28f
    val caseL = cx - caseHalf
    val caseR = cx + caseHalf
    val caseT = cy - caseHalf
    val caseB = cy + caseHalf

    // ── the wrist strap, curving away above and below the case ───────────────────────────────────
    val strapHalf = w * 0.165f
    val strapMetal = Brush.horizontalGradient(
        colors = listOf(ink.deep, ink.toward(primary, 0.06f), ink.deep),
        startX = cx - strapHalf, endX = cx + strapHalf,
    )
    for (up in intArrayOf(1, -1)) {
        // Slightly tapered rounded band running from the case out toward the frame edge.
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
        // Twin rows of stitching just inside each long edge.
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
        // A lug hole where the strap threads the case.
        val holeY = if (up == 1) near - bandH * 0.06f else near + bandH * 0.06f
        drawCircle(ink.abyss.copy(alpha = 0.7f), radius = w * 0.012f, center = Offset(cx, holeY))
    }

    // ── a peek of the guts: PCB-green and a gold pad slipping out at the foot ─────────────────────
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

    // ── side buttons on the right flank ───────────────────────────────────────────────────────────
    val btnMetal = Brush.horizontalGradient(listOf(ink.toward(Color.White, 0.18f), ink.deep), startX = caseR, endX = caseR + w * 0.05f)
    for (by in floatArrayOf(cy - caseHalf * 0.45f, cy + caseHalf * 0.30f)) {
        val bh = if (by < cy) caseHalf * 0.34f else caseHalf * 0.22f
        drawRoundRect(
            btnMetal,
            topLeft = Offset(caseR - w * 0.006f, by - bh / 2f),
            size = Size(w * 0.045f, bh),
            cornerRadius = CornerRadius(w * 0.012f, w * 0.012f),
        )
        drawRoundRect(
            ink.abyss.copy(alpha = 0.55f),
            topLeft = Offset(caseR - w * 0.006f, by - bh / 2f),
            size = Size(w * 0.045f, bh),
            cornerRadius = CornerRadius(w * 0.012f, w * 0.012f),
            style = Stroke(width = w * 0.003f),
        )
    }

    // ── the brushed case ─────────────────────────────────────────────────────────────────────────
    val caseRad = CornerRadius(caseHalf * 0.42f, caseHalf * 0.42f)
    // A soft cast shadow beneath the case lifts it off the strap.
    drawRoundRect(
        Color.Black.copy(alpha = 0.28f),
        topLeft = Offset(caseL + w * 0.012f, caseT + h * 0.016f),
        size = Size(caseHalf * 2f, caseHalf * 2f),
        cornerRadius = caseRad,
    )
    drawRoundRect(
        Brush.linearGradient(
            colors = listOf(ink.toward(Color.White, 0.24f), ink, ink.deep, ink.abyss),
            start = Offset(caseL, caseT), end = Offset(caseR, caseB),
        ),
        topLeft = Offset(caseL, caseT),
        size = Size(caseHalf * 2f, caseHalf * 2f),
        cornerRadius = caseRad,
    )
    // A cold rim-light on the upper-left bezel, a dark contour all round.
    drawRoundRect(
        primary.toward(Color.White, 0.3f).copy(alpha = 0.25f),
        topLeft = Offset(caseL, caseT),
        size = Size(caseHalf * 2f, caseHalf * 2f),
        cornerRadius = caseRad,
        style = Stroke(width = w * 0.006f),
    )
    drawRoundRect(
        ink.abyss.copy(alpha = 0.8f),
        topLeft = Offset(caseL, caseT),
        size = Size(caseHalf * 2f, caseHalf * 2f),
        cornerRadius = caseRad,
        style = Stroke(width = w * 0.004f),
    )

    // ── the display ──────────────────────────────────────────────────────────────────────────────
    val bezel = caseHalf * 0.20f
    val sL = caseL + bezel
    val sT = caseT + bezel
    val sR = caseR - bezel
    val sB = caseB - bezel
    val sW = sR - sL
    val sH = sB - sT
    val scrRad = CornerRadius(caseHalf * 0.26f, caseHalf * 0.26f)
    val screenPath = Path().apply {
        addRoundRect(RoundRect(sL, sT, sR, sB, scrRad))
    }
    // The dark glass.
    drawRoundRect(
        Brush.verticalGradient(listOf(ink.abyss.toward(Color.Black, 0.5f), Color.Black), startY = sT, endY = sB),
        topLeft = Offset(sL, sT),
        size = Size(sW, sH),
        cornerRadius = scrRad,
    )

    clipPath(screenPath) {
        // The OLED's ambient glow, breathing off phase.
        drawCircle(
            Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.22f + 0.16f * breath), primary.copy(alpha = 0f)),
                center = Offset(sL + sW * 0.5f, sT + sH * 0.42f),
                radius = sW * 0.75f,
            ),
            radius = sW * 0.75f,
            center = Offset(sL + sW * 0.5f, sT + sH * 0.42f),
        )

        // A scrolling glucose trace across the lower band.
        val baseY = sB - sH * 0.22f
        val amp = sH * 0.12f
        val trace = Path()
        val n = 44
        fun traceY(u: Float): Float = baseY - amp * (sin(u) * 0.6f + sin(u * 0.5f + 1.1f) * 0.4f)
        for (i in 0..n) {
            val x = sL + sW * i / n
            val u = i.toFloat() / n * 4.2f - phase * 0.6f
            if (i == 0) trace.moveTo(x, traceY(u)) else trace.lineTo(x, traceY(u))
        }
        drawPath(trace, primary.copy(alpha = 0.55f), style = Stroke(width = w * 0.005f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // The live data-blip riding the trace — the "pushed every 5 min" heartbeat.
        val bx = sL + sW * blip
        val bu = blip * 4.2f - phase * 0.6f
        val byv = traceY(bu)
        drawCircle(primary.copy(alpha = 0.18f + 0.22f * breath), radius = w * 0.03f * (0.7f + 0.3f * breath), center = Offset(bx, byv))
        drawCircle(primary.toward(Color.White, 0.5f), radius = w * 0.009f, center = Offset(bx, byv))

        // The glanceable BG figure, "5.8", in seven-segment glow.
        val onSeg = primary.toward(Color.White, 0.35f).copy(alpha = 0.85f + 0.15f * breath)
        val offSeg = primary.copy(alpha = 0.08f)
        val dw = sW * 0.17f
        val dh = sH * 0.4f
        val dy = sT + sH * 0.14f
        val d1x = sL + sW * 0.12f
        val d2x = d1x + dw * 1.55f
        sevenSeg('5', d1x, dy, dw, dh, onSeg, offSeg)
        sevenSeg('8', d2x, dy, dw, dh, onSeg, offSeg)
        // The decimal point.
        drawCircle(onSeg, radius = dw * 0.09f, center = Offset(d1x + dw * 1.28f, dy + dh))

        // A rising trend arrow to the right of the figure.
        val ax = sL + sW * 0.78f
        val ay = dy + dh * 0.5f
        val arrowCol = primary.toward(accent, 0.35f).copy(alpha = 0.85f + 0.15f * breath)
        val al = sW * 0.11f
        val tail = Offset(ax - al * 0.5f, ay + al * 0.5f)
        val head = Offset(ax + al * 0.5f, ay - al * 0.5f)
        drawLine(arrowCol, tail, head, strokeWidth = w * 0.008f, cap = StrokeCap.Round)
        drawLine(arrowCol, head, Offset(head.x - al * 0.5f, head.y), strokeWidth = w * 0.008f, cap = StrokeCap.Round)
        drawLine(arrowCol, head, Offset(head.x, head.y + al * 0.5f), strokeWidth = w * 0.008f, cap = StrokeCap.Round)

        // A faint scanline texture and a glass reflection sweeping the upper-left.
        var yy = sT + sH * 0.06f
        while (yy < sB) {
            drawLine(Color.Black.copy(alpha = 0.12f), Offset(sL, yy), Offset(sR, yy), strokeWidth = w * 0.0015f)
            yy += sH * 0.045f
        }
        drawPath(
            Path().apply {
                moveTo(sL, sT)
                lineTo(sL + sW * 0.55f, sT)
                lineTo(sL, sT + sH * 0.62f)
                close()
            },
            Color.White.copy(alpha = 0.06f),
        )
    }

    // The glass edge catching the light.
    drawRoundRect(
        ink.toward(Color.White, 0.14f).copy(alpha = 0.35f),
        topLeft = Offset(sL, sT),
        size = Size(sW, sH),
        cornerRadius = scrRad,
        style = Stroke(width = w * 0.003f),
    )
}

/**
 * Paints a single seven-segment glyph in the cell [x],[y],[cw]×[chh]; lit segments burn in [on],
 * the dark ones ghost in [off] as an unlit OLED cell would.
 */
private fun DrawScope.sevenSeg(ch: Char, x: Float, y: Float, cw: Float, chh: Float, on: Color, off: Color) {
    val segs = SEG[ch] ?: return
    val th = cw * 0.15f
    val pad = th * 0.7f
    val midY = y + chh / 2f
    fun seg(id: Char, a: Offset, b: Offset) {
        drawLine(if (id in segs) on else off, a, b, strokeWidth = th, cap = StrokeCap.Round)
    }
    seg('a', Offset(x + pad, y), Offset(x + cw - pad, y))
    seg('g', Offset(x + pad, midY), Offset(x + cw - pad, midY))
    seg('d', Offset(x + pad, y + chh), Offset(x + cw - pad, y + chh))
    seg('f', Offset(x, y + pad), Offset(x, midY - pad * 0.3f))
    seg('b', Offset(x + cw, y + pad), Offset(x + cw, midY - pad * 0.3f))
    seg('e', Offset(x, midY + pad * 0.3f), Offset(x, y + chh - pad))
    seg('c', Offset(x + cw, midY + pad * 0.3f), Offset(x + cw, y + chh - pad))
}
