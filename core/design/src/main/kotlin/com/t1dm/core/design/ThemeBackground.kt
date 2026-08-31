package com.t1dm.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp

/** The painters paint at full strength; the caller applies the user's alpha once, over the whole
 *  layer. */
@Composable
fun ThemeBackdrop(alphaPct: Int, modifier: Modifier = Modifier) {
    if (alphaPct <= 0) return
    val palette = LocalT1dmSemantics.current
    val a = (alphaPct / 100f).coerceIn(0f, 1f)
    val resId = rememberBackdropRasterId(palette.id)
    if (resId != 0) {
        // Centred at half size, Fit rather than Crop, so the whole motif stays visible.
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(resId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.5f).alpha(a),
                contentScale = ContentScale.Fit,
            )
        }
    } else {
        Canvas(modifier.fillMaxSize().alpha(a)) {
            drawThemeBackground(palette)
        }
    }
}

/** `@drawable/theme_bg_<themeId>`, or 0 when none ships. */
@Composable
private fun rememberBackdropRasterId(themeId: String): Int {
    val ctx = LocalContext.current
    return remember(themeId) {
        runCatching { ctx.resources.getIdentifier("theme_bg_$themeId", "drawable", ctx.packageName) }
            .getOrDefault(0)
    }
}

private val MOTIF_PAINTERS: Map<String, DrawScope.(T1dmPalette) -> Unit> = mapOf(
    ThemeIds.TRON to { p -> drawTronBackground(p) },
    ThemeIds.UMBRELLA to { p -> drawUmbrellaBackground(p) },
    ThemeIds.HELLO_KITTY to { p -> drawHelloKittyBackground(p) },
    ThemeIds.EINK to { p -> drawEInkBackground(p) },
)

fun DrawScope.drawThemeBackground(p: T1dmPalette) {
    val painter = MOTIF_PAINTERS[p.id]
    if (painter != null) painter(p) else drawRect(p.background)
}

private fun DrawScope.drawTronBackground(p: T1dmPalette) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val horizon = h * 0.42f

    // Opaque base, under the caller's alpha layer.
    drawRect(p.background, size = size)

    drawRect(
        Brush.verticalGradient(
            0f to p.background,
            0.30f to lerp(p.background, p.primary, 0.05f),
            0.42f to lerp(p.background, p.primary, 0.16f),
            0.52f to lerp(p.background, p.primary, 0.05f),
            1f to p.background,
            startY = 0f, endY = h,
        ),
        size = size,
    )

    drawCircle(
        Brush.radialGradient(
            listOf(p.primary.copy(alpha = 0.22f), p.primary.copy(alpha = 0f)),
            center = Offset(cx, horizon),
            radius = w * 0.6f,
        ),
        radius = w * 0.6f,
        center = Offset(cx, horizon),
    )

    fun glow(a: Offset, b: Offset, wide: Color, core: Color, wWide: Float, wCore: Float) {
        drawLine(wide, a, b, strokeWidth = wWide, cap = StrokeCap.Round)
        drawLine(core, a, b, strokeWidth = wCore, cap = StrokeCap.Round)
    }

    val meshWide = lerp(p.grid, p.primary, 0.30f).copy(alpha = 0.20f)
    val meshCore = lerp(p.grid, p.primary, 0.60f).copy(alpha = 0.55f)

    for (half in intArrayOf(1, -1)) {
        val farY = if (half == 1) h else 0f
        val span = kotlin.math.abs(farY - horizon)
        // Quadratic spacing bunches them toward the horizon.
        val rows = 9
        for (i in 1..rows) {
            val t = i.toFloat() / rows
            val tt = t * t
            val y = horizon + half * span * tt
            val fade = 1f - 0.55f * tt
            glow(
                Offset(0f, y), Offset(w, y),
                meshWide.copy(alpha = meshWide.alpha * fade),
                meshCore.copy(alpha = meshCore.alpha * fade),
                h * 0.006f, h * 0.0015f,
            )
        }
        val cols = 7
        for (k in -cols..cols) {
            val xFar = cx + k.toFloat() / cols * w * 1.9f
            glow(Offset(cx, horizon), Offset(xFar, farY), meshWide, meshCore, h * 0.006f, h * 0.0015f)
        }
    }

    glow(
        Offset(0f, horizon), Offset(w, horizon),
        p.primary.copy(alpha = 0.30f), p.primary.copy(alpha = 0.95f),
        h * 0.022f, h * 0.004f,
    )
}

private fun DrawScope.drawUmbrellaBackground(p: T1dmPalette) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = kotlin.math.min(w, h) * 0.46f

    // Opaque base under the caller's alpha layer.
    drawRect(p.background, size = size)

    drawCircle(
        Brush.radialGradient(
            listOf(lerp(p.background, p.primary, 0.12f), p.background),
            center = Offset(cx, cy), radius = r * 1.6f,
        ),
        radius = r * 1.6f, center = Offset(cx, cy),
    )
    drawCircle(p.primary.copy(alpha = 0.14f), radius = r * 1.06f, center = Offset(cx, cy), style = Stroke(width = w * 0.02f))

    val white = p.ink
    val red = p.primary

    // A vertex points straight up.
    fun vert(k: Int): Offset {
        val ang = -kotlin.math.PI / 2.0 + k * kotlin.math.PI / 4.0
        return Offset(cx + (r * kotlin.math.cos(ang)).toFloat(), cy + (r * kotlin.math.sin(ang)).toFloat())
    }

    for (k in 0 until 8) {
        val v0 = vert(k)
        val v1 = vert(k + 1)
        val wedge = Path().apply { moveTo(cx, cy); lineTo(v0.x, v0.y); lineTo(v1.x, v1.y); close() }
        val even = k % 2 == 0
        val base = if (even) red else white
        drawPath(
            wedge,
            Brush.radialGradient(
                listOf(lerp(base, Color.Black, if (even) 0.34f else 0.10f), base),
                center = Offset(cx, cy), radius = r,
            ),
        )
    }

    for (k in 0 until 8) {
        val v = vert(k)
        drawLine(p.background.copy(alpha = 0.92f), Offset(cx, cy), v, strokeWidth = w * 0.007f, cap = StrokeCap.Butt)
    }

    val oct = Path().apply {
        val v0 = vert(0); moveTo(v0.x, v0.y)
        for (k in 1 until 8) { val v = vert(k); lineTo(v.x, v.y) }
        close()
    }
    drawPath(oct, lerp(red, Color.Black, 0.45f), style = Stroke(width = w * 0.012f, join = StrokeJoin.Miter))
    drawPath(oct, white.copy(alpha = 0.35f), style = Stroke(width = w * 0.003f, join = StrokeJoin.Miter))

    drawCircle(lerp(red, Color.Black, 0.30f), radius = r * 0.11f, center = Offset(cx, cy))
    drawCircle(white.copy(alpha = 0.85f), radius = r * 0.11f, center = Offset(cx, cy), style = Stroke(width = w * 0.006f))
}

private fun DrawScope.drawHelloKittyBackground(p: T1dmPalette) {
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val cy = h * 0.54f
    val rF = w * 0.30f

    // Opaque base under the caller's alpha layer.
    drawRect(p.background, size = size)

    drawRect(
        Brush.verticalGradient(
            0f to lerp(p.background, p.surface, 0.45f),
            0.6f to p.background,
            1f to lerp(p.background, p.primary, 0.07f),
            startY = 0f, endY = h,
        ),
        size = size,
    )

    fun heart(x: Float, y: Float, s: Float, color: Color) {
        val path = Path().apply {
            moveTo(x, y + s)
            cubicTo(x - s * 1.4f, y - s * 0.2f, x - s * 0.6f, y - s * 1.2f, x, y - s * 0.4f)
            cubicTo(x + s * 0.6f, y - s * 1.2f, x + s * 1.4f, y - s * 0.2f, x, y + s)
            close()
        }
        drawPath(path, color)
    }

    heart(w * 0.14f, h * 0.16f, w * 0.045f, p.primary.copy(alpha = 0.35f))
    heart(w * 0.86f, h * 0.12f, w * 0.035f, p.secondary.copy(alpha = 0.40f))
    heart(w * 0.90f, h * 0.80f, w * 0.05f, p.primary.copy(alpha = 0.30f))
    heart(w * 0.10f, h * 0.82f, w * 0.04f, p.secondary.copy(alpha = 0.35f))
    heart(w * 0.20f, h * 0.92f, w * 0.028f, p.primary.copy(alpha = 0.28f))

    val outline = lerp(p.ink, Color.Black, 0.30f)
    val faceW = w * 0.012f

    // Ears, drawn beneath the face.
    for (sgn in intArrayOf(-1, 1)) {
        val ear = Path().apply {
            moveTo(cx + sgn * rF * 0.20f, cy - rF * 0.78f)
            lineTo(cx + sgn * rF * 0.82f, cy - rF * 0.48f)
            lineTo(cx + sgn * rF * 0.66f, cy - rF * 1.30f)
            close()
        }
        drawPath(ear, p.surface)
        drawPath(ear, outline, style = Stroke(width = faceW, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }

    drawCircle(Color.Black.copy(alpha = 0.06f), radius = rF, center = Offset(cx + w * 0.006f, cy + h * 0.008f))
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White, lerp(p.surface, p.primary, 0.06f)),
            center = Offset(cx - rF * 0.3f, cy - rF * 0.35f), radius = rF * 1.5f,
        ),
        radius = rF, center = Offset(cx, cy),
    )
    drawCircle(outline, radius = rF, center = Offset(cx, cy), style = Stroke(width = faceW))

    for (sgn in intArrayOf(-1, 1)) {
        val inner = Path().apply {
            moveTo(cx + sgn * rF * 0.44f, cy - rF * 0.78f)
            lineTo(cx + sgn * rF * 0.66f, cy - rF * 0.66f)
            lineTo(cx + sgn * rF * 0.62f, cy - rF * 1.12f)
            close()
        }
        drawPath(inner, lerp(p.primary, Color.White, 0.25f).copy(alpha = 0.8f))
    }

    for (sgn in intArrayOf(-1, 1)) {
        drawCircle(p.primary.copy(alpha = 0.22f), radius = rF * 0.14f, center = Offset(cx + sgn * rF * 0.56f, cy + rF * 0.20f))
    }

    val eye = lerp(p.ink, Color.Black, 0.45f)
    for (sgn in intArrayOf(-1, 1)) {
        val ex = cx + sgn * rF * 0.42f
        val ey = cy - rF * 0.02f
        val ew = rF * 0.13f
        val eh = rF * 0.20f
        drawOval(eye, topLeft = Offset(ex - ew / 2f, ey - eh / 2f), size = Size(ew, eh))
        drawCircle(Color.White.copy(alpha = 0.85f), radius = rF * 0.03f, center = Offset(ex - ew * 0.15f, ey - eh * 0.22f))
    }

    // A small golden nose.
    val noseW = rF * 0.16f
    val noseH = rF * 0.11f
    val noseTL = Offset(cx - noseW / 2f, cy + rF * 0.14f - noseH / 2f)
    drawOval(Color(0xFFF6B93B), topLeft = noseTL, size = Size(noseW, noseH))
    drawOval(lerp(Color(0xFFF6B93B), Color.Black, 0.30f), topLeft = noseTL, size = Size(noseW, noseH), style = Stroke(width = w * 0.004f))

    val whisker = p.inkMuted
    for (sgn in intArrayOf(-1, 1)) {
        for (row in -1..1) {
            val y0 = cy + rF * 0.16f + row * rF * 0.16f
            val y1 = y0 + row * rF * 0.05f
            drawLine(
                whisker,
                Offset(cx + sgn * rF * 0.42f, y0),
                Offset(cx + sgn * rF * 0.98f, y1),
                strokeWidth = w * 0.006f, cap = StrokeCap.Round,
            )
        }
    }

    // The bow, on the viewer's-right ear.
    val bx = cx + rF * 0.62f
    val by = cy - rF * 0.92f
    val ww = rF * 0.42f
    val wh = rF * 0.34f
    val bow = p.primary
    for (sgn in intArrayOf(-1, 1)) {
        val wing = Path().apply {
            moveTo(bx, by)
            lineTo(bx + sgn * ww, by - wh * 0.55f)
            lineTo(bx + sgn * ww, by + wh * 0.55f)
            close()
        }
        drawPath(
            wing,
            Brush.radialGradient(
                listOf(lerp(bow, Color.White, 0.25f), bow),
                center = Offset(bx, by), radius = ww * 1.2f,
            ),
        )
        drawPath(wing, lerp(bow, Color.Black, 0.35f), style = Stroke(width = w * 0.006f, join = StrokeJoin.Round))
    }
    drawCircle(lerp(bow, Color.Black, 0.12f), radius = rF * 0.11f, center = Offset(bx, by))
    drawCircle(lerp(bow, Color.White, 0.30f).copy(alpha = 0.7f), radius = rF * 0.11f, center = Offset(bx, by), style = Stroke(width = w * 0.005f))
}

/** A page of set text: head and folio rules, ragged paragraphs, paper grain. Every position comes
 *  from [hashFrac], so the page is the same on every recompose. */
private fun DrawScope.drawEInkBackground(p: T1dmPalette) {
    val w = size.width
    val h = size.height

    // Opaque base under the caller's alpha layer.
    drawRect(p.background, size = size)
    drawRect(
        Brush.verticalGradient(
            0f to lerp(p.background, p.surface, 0.90f),
            0.55f to lerp(p.background, p.surface, 0.35f),
            1f to p.background,
            startY = 0f, endY = h,
        ),
        size = size,
    )

    val left = w * 0.14f
    val right = w * 0.86f
    val col = right - left
    val rule = h * 0.0015f

    drawLine(p.grid, Offset(left, h * 0.105f), Offset(right, h * 0.105f), strokeWidth = rule)
    drawLine(p.grid, Offset(left, h * 0.930f), Offset(right, h * 0.930f), strokeWidth = rule)

    val lh = h * 0.030f
    val bottom = h * 0.885f
    val glyph = p.inkMuted.copy(alpha = 0.55f)
    var y = h * 0.165f
    var i = 0
    var inPara = 0
    var paraLen = 4 + (hashFrac(1) * 4).toInt()
    while (y < bottom) {
        val indent = if (inPara == 0) col * 0.05f else 0f
        val last = inPara == paraLen - 1
        val len = if (last) col * (0.30f + 0.40f * hashFrac(i)) else col * (0.90f + 0.10f * hashFrac(i)) - indent
        drawLine(
            glyph,
            Offset(left + indent, y), Offset(left + indent + len, y),
            strokeWidth = lh * 0.30f, cap = StrokeCap.Round,
        )
        y += lh
        i++
        inPara++
        if (inPara >= paraLen) {
            inPara = 0
            y += lh * 0.55f
            paraLen = 3 + (hashFrac(i * 7) * 5).toInt()
        }
    }

    val grain = p.ink.copy(alpha = 0.035f)
    val speck = w * 0.0016f
    for (k in 0 until 220) {
        drawCircle(grain, radius = speck, center = Offset(hashFrac(k * 3 + 1) * w, hashFrac(k * 5 + 2) * h))
    }
}

/** 0f..1f, stable for a given [i]. */
private fun hashFrac(i: Int): Float {
    val s = kotlin.math.sin(i * 12.9898f) * 43758.5453f
    return s - kotlin.math.floor(s)
}
