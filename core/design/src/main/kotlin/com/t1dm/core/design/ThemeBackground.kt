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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The per-theme full-screen BACKDROP (Phase 7D). Each theme paints its own signature motif behind the
 * whole app — the Windows flag, Kasane Teto, the Tron light-grid, the Umbrella mark, the Hello Kitty
 * face — all in pure Compose Canvas (no raster assets), so each tints and scales with the theme exactly
 * like the rest of the app's iconography ([WatchArt], [NavIcons]).
 *
 * [ThemeBackdrop] draws it at a user-set opacity (Settings -> Display -> Background), so it reads as a
 * faint wash by default and can be dialled to 0 (off) or all the way up. The painters themselves paint
 * at full strength; the alpha is applied by the caller once, over the whole layer.
 */
@Composable
fun ThemeBackdrop(alphaPct: Int, modifier: Modifier = Modifier) {
    if (alphaPct <= 0) return
    val palette = LocalT1dmSemantics.current
    val a = (alphaPct / 100f).coerceIn(0f, 1f)
    // Drop-in override: if a raster `@drawable/theme_bg_<themeId>` (e.g. theme_bg_teto.png,
    // theme_bg_windows_xp.png) has been placed in the app, use it as the backdrop, scaled to fill;
    // otherwise fall back to the pure-Canvas painter below. This lets the user supply their own
    // per-theme wallpaper/logo without touching code — it is drawn at the same user-set opacity.
    val ctx = LocalContext.current
    val resId = remember(palette.id) {
        runCatching { ctx.resources.getIdentifier("theme_bg_${palette.id}", "drawable", ctx.packageName) }
            .getOrDefault(0)
    }
    if (resId != 0) {
        // Draw the supplied image as a CENTRED motif at half the screen size (not a full-bleed crop),
        // over the app-background base, at the user's opacity. Fit (not Crop) keeps the whole motif
        // visible; the 0.5 fraction is the "half size" the backdrop reads best at.
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

/** Dispatch to the active theme's painter; a custom/unknown theme gets a plain background wash. */
fun DrawScope.drawThemeBackground(p: T1dmPalette) {
    when (p.id) {
        ThemeIds.TRON -> drawTronBackground(p)
        ThemeIds.UMBRELLA -> drawUmbrellaBackground(p)
        ThemeIds.HELLO_KITTY -> drawHelloKittyBackground(p)
        ThemeIds.WINDOWS_XP -> drawWindowsXpBackground(p)
        ThemeIds.TETO -> drawTetoBackground(p)
        else -> drawRect(p.background)
    }
}

// ── Per-theme painters (designed in the 7D theme pass; pure shape APIs only) ──────────────────────────

private fun DrawScope.drawTronBackground(p: T1dmPalette) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val horizon = h * 0.42f

    // Opaque base so the whole backdrop sits under the caller's alpha layer.
    drawRect(p.background, size = size)

    // A cold vertical wash that blooms to a bright band exactly at the horizon.
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

    // A radial halo pinned on the vanishing point.
    drawCircle(
        Brush.radialGradient(
            listOf(p.primary.copy(alpha = 0.22f), p.primary.copy(alpha = 0f)),
            center = Offset(cx, horizon),
            radius = w * 0.6f,
        ),
        radius = w * 0.6f,
        center = Offset(cx, horizon),
    )

    // Every conduit is stroked twice: a wide soft bloom, then a thin bright core.
    fun glow(a: Offset, b: Offset, wide: Color, core: Color, wWide: Float, wCore: Float) {
        drawLine(wide, a, b, strokeWidth = wWide, cap = StrokeCap.Round)
        drawLine(core, a, b, strokeWidth = wCore, cap = StrokeCap.Round)
    }

    val meshWide = lerp(p.grid, p.primary, 0.30f).copy(alpha = 0.20f)
    val meshCore = lerp(p.grid, p.primary, 0.60f).copy(alpha = 0.55f)

    // Mirror the lattice above and below the horizon — a floor and a ceiling of light.
    for (half in intArrayOf(1, -1)) {
        val farY = if (half == 1) h else 0f
        val span = kotlin.math.abs(farY - horizon)
        // Latitude lines: quadratic spacing bunches them toward the horizon (perspective).
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
        // Longitude lines: a fan radiating from the vanishing point past the frame edge.
        val cols = 7
        for (k in -cols..cols) {
            val xFar = cx + k.toFloat() / cols * w * 1.9f
            glow(Offset(cx, horizon), Offset(xFar, farY), meshWide, meshCore, h * 0.006f, h * 0.0015f)
        }
    }

    // The horizon itself — the brightest seam.
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

    // A faint radial lift beneath the mark, and a whisper of a containment ring.
    drawCircle(
        Brush.radialGradient(
            listOf(lerp(p.background, p.primary, 0.12f), p.background),
            center = Offset(cx, cy), radius = r * 1.6f,
        ),
        radius = r * 1.6f, center = Offset(cx, cy),
    )
    drawCircle(p.primary.copy(alpha = 0.14f), radius = r * 1.06f, center = Offset(cx, cy), style = Stroke(width = w * 0.02f))

    val white = p.ink   // the palette's off-white reads as the logo's white, theme-coherent.
    val red = p.primary // hazard red.

    // Eight octagon vertices, a vertex pointing straight up (the umbrella's peak).
    fun vert(k: Int): Offset {
        val ang = -kotlin.math.PI / 2.0 + k * kotlin.math.PI / 4.0
        return Offset(cx + (r * kotlin.math.cos(ang)).toFloat(), cy + (r * kotlin.math.sin(ang)).toFloat())
    }

    // Eight wedges, apex at centre, alternating red and white, each shaded from a dark hub outward.
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

    // Thin dark ribs along every radial, seating each panel like an umbrella's struts.
    for (k in 0 until 8) {
        val v = vert(k)
        drawLine(p.background.copy(alpha = 0.92f), Offset(cx, cy), v, strokeWidth = w * 0.007f, cap = StrokeCap.Butt)
    }

    // The octagon silhouette: a heavy dark contour with a fine bright inner highlight.
    val oct = Path().apply {
        val v0 = vert(0); moveTo(v0.x, v0.y)
        for (k in 1 until 8) { val v = vert(k); lineTo(v.x, v.y) }
        close()
    }
    drawPath(oct, lerp(red, Color.Black, 0.45f), style = Stroke(width = w * 0.012f, join = StrokeJoin.Miter))
    drawPath(oct, white.copy(alpha = 0.35f), style = Stroke(width = w * 0.003f, join = StrokeJoin.Miter))

    // The central hub.
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

    // A gentle blush wash, paling at the crown, warming toward the foot.
    drawRect(
        Brush.verticalGradient(
            0f to lerp(p.background, p.surface, 0.45f),
            0.6f to p.background,
            1f to lerp(p.background, p.primary, 0.07f),
            startY = 0f, endY = h,
        ),
        size = size,
    )

    // A reusable heart, tip down.
    fun heart(x: Float, y: Float, s: Float, color: Color) {
        val path = Path().apply {
            moveTo(x, y + s)
            cubicTo(x - s * 1.4f, y - s * 0.2f, x - s * 0.6f, y - s * 1.2f, x, y - s * 0.4f)
            cubicTo(x + s * 0.6f, y - s * 1.2f, x + s * 1.4f, y - s * 0.2f, x, y + s)
            close()
        }
        drawPath(path, color)
    }

    // Hearts strewn quietly across the field so the face still leads.
    heart(w * 0.14f, h * 0.16f, w * 0.045f, p.primary.copy(alpha = 0.35f))
    heart(w * 0.86f, h * 0.12f, w * 0.035f, p.secondary.copy(alpha = 0.40f))
    heart(w * 0.90f, h * 0.80f, w * 0.05f, p.primary.copy(alpha = 0.30f))
    heart(w * 0.10f, h * 0.82f, w * 0.04f, p.secondary.copy(alpha = 0.35f))
    heart(w * 0.20f, h * 0.92f, w * 0.028f, p.primary.copy(alpha = 0.28f))

    val outline = lerp(p.ink, Color.Black, 0.30f)
    val faceW = w * 0.012f

    // Ears beneath the face — a filled triangle each side.
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

    // The round white face — a soft cast shadow, a radial sheen, a crisp dark contour.
    drawCircle(Color.Black.copy(alpha = 0.06f), radius = rF, center = Offset(cx + w * 0.006f, cy + h * 0.008f))
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White, lerp(p.surface, p.primary, 0.06f)),
            center = Offset(cx - rF * 0.3f, cy - rF * 0.35f), radius = rF * 1.5f,
        ),
        radius = rF, center = Offset(cx, cy),
    )
    drawCircle(outline, radius = rF, center = Offset(cx, cy), style = Stroke(width = faceW))

    // Pink inner-ear accents peeking above the face.
    for (sgn in intArrayOf(-1, 1)) {
        val inner = Path().apply {
            moveTo(cx + sgn * rF * 0.44f, cy - rF * 0.78f)
            lineTo(cx + sgn * rF * 0.66f, cy - rF * 0.66f)
            lineTo(cx + sgn * rF * 0.62f, cy - rF * 1.12f)
            close()
        }
        drawPath(inner, lerp(p.primary, Color.White, 0.25f).copy(alpha = 0.8f))
    }

    // Soft blush cheeks.
    for (sgn in intArrayOf(-1, 1)) {
        drawCircle(p.primary.copy(alpha = 0.22f), radius = rF * 0.14f, center = Offset(cx + sgn * rF * 0.56f, cy + rF * 0.20f))
    }

    // Two dark oval eyes, each with a small catch-light.
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

    // Three whiskers a side, splaying gently.
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

    // The signature bow, perched on the viewer's-right ear.
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

private fun DrawScope.drawWindowsXpBackground(p: T1dmPalette) {
    val w = size.width
    val h = size.height

    // Opaque base so the caller's global alpha has something solid beneath it.
    drawRect(p.background, size = Size(w, h))

    // A gentle Luna sky: pale near-white above, a soft Bliss blue swelling toward the horizon.
    drawRect(
        Brush.verticalGradient(
            0f to p.background,
            0.42f to lerp(p.background, Color(0xFF6FA8DC), 0.34f),
            1f to lerp(p.background, Color(0xFF3B7BC8), 0.52f),
            startY = 0f, endY = h,
        ),
        size = Size(w, h),
    )

    // Two soft clouds drifting the upper sky.
    val cloud = Color.White.copy(alpha = 0.55f)
    run {
        val cx = w * 0.24f; val cy = h * 0.15f; val r = w * 0.05f
        drawCircle(cloud, r * 0.9f, Offset(cx, cy))
        drawCircle(cloud, r * 1.3f, Offset(cx + r * 1.1f, cy + r * 0.15f))
        drawCircle(cloud, r * 0.85f, Offset(cx + r * 2.2f, cy))
    }
    run {
        val cx = w * 0.79f; val cy = h * 0.10f; val r = w * 0.038f
        drawCircle(cloud, r * 0.85f, Offset(cx, cy))
        drawCircle(cloud, r * 1.2f, Offset(cx + r * 1.0f, cy + r * 0.12f))
        drawCircle(cloud, r * 0.8f, Offset(cx + r * 2.0f, cy))
    }

    // The iconic Bliss hill silhouette along the foot — one smooth grassy crest.
    val hill = Path().apply {
        moveTo(0f, h * 0.86f)
        cubicTo(w * 0.28f, h * 0.74f, w * 0.62f, h * 0.83f, w, h * 0.78f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(
        hill,
        Brush.verticalGradient(
            0f to lerp(p.inRange, Color(0xFF7CB342), 0.6f).copy(alpha = 0.55f),
            1f to lerp(p.inRange, Color(0xFF33691E), 0.7f).copy(alpha = 0.6f),
            startY = h * 0.72f, endY = h,
        ),
    )

    // ── The waving four-pane Windows flag, centred and large ─────────────────────────────────────
    val cx = w / 2f
    val cy = h * 0.44f
    val s = kotlin.math.min(w, h) * 0.34f
    fun fx(x: Float): Float = cx + (x - 54f) / 28f * s
    fun fy(y: Float): Float = cy + (y - 55f) / 28f * s

    // A soft glossy glow cradling the flag.
    drawCircle(
        Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.30f),
            1f to Color.White.copy(alpha = 0f),
            center = Offset(cx, cy), radius = s * 1.5f,
        ),
        radius = s * 1.5f, center = Offset(cx, cy),
    )

    fun pane(fill: Color, build: Path.() -> Unit) {
        val path = Path().apply(build)
        drawPath(path, fill.copy(alpha = 0.92f))
        drawPath(path, Color.White.copy(alpha = 0.22f), style = Stroke(width = s * 0.02f, join = StrokeJoin.Round))
    }

    pane(Color(0xFFE8402A)) { // red — top-left
        moveTo(fx(27f), fy(35f))
        cubicTo(fx(34f), fy(32f), fx(41f), fy(32f), fx(50f), fy(34f))
        lineTo(fx(51f), fy(53f))
        cubicTo(fx(42f), fy(51f), fx(35f), fy(51f), fx(28f), fy(54f))
        close()
    }
    pane(Color(0xFF7CB518)) { // green — top-right
        moveTo(fx(55f), fy(34f))
        cubicTo(fx(63f), fy(32f), fx(71f), fy(31f), fx(80f), fy(32f))
        lineTo(fx(81f), fy(51f))
        cubicTo(fx(72f), fy(50f), fx(64f), fy(51f), fx(56f), fy(53f))
        close()
    }
    pane(Color(0xFF1287D8)) { // blue — bottom-left
        moveTo(fx(28f), fy(58f))
        cubicTo(fx(35f), fy(55f), fx(42f), fy(55f), fx(51f), fy(57f))
        lineTo(fx(52f), fy(76f))
        cubicTo(fx(43f), fy(74f), fx(36f), fy(74f), fx(29f), fy(77f))
        close()
    }
    pane(Color(0xFFFCB915)) { // yellow — bottom-right
        moveTo(fx(56f), fy(57f))
        cubicTo(fx(64f), fy(55f), fx(72f), fy(54f), fx(81f), fy(55f))
        lineTo(fx(82f), fy(74f))
        cubicTo(fx(73f), fy(73f), fx(65f), fy(74f), fx(57f), fy(76f))
        close()
    }
}

private fun DrawScope.drawTetoBackground(p: T1dmPalette) {
    val w = size.width
    val h = size.height

    // Opaque base so the backdrop stays solid beneath the caller's alpha layer.
    drawRect(p.background)

    // A warm, near-black vertical wash — a crimson breath up top, a deeper foot below.
    drawRect(
        Brush.verticalGradient(
            0f to lerp(p.background, p.primary, 0.12f),
            0.4f to p.background,
            1f to lerp(p.background, Color.Black, 0.4f),
        ),
    )
    // A soft votive glow cradling the face.
    drawCircle(
        Brush.radialGradient(
            colors = listOf(p.primary.copy(alpha = 0.16f), p.primary.copy(alpha = 0f)),
            center = Offset(w * 0.5f, h * 0.34f),
            radius = w * 0.62f,
        ),
        radius = w * 0.62f,
        center = Offset(w * 0.5f, h * 0.34f),
    )

    // The crimson key: a lit pink crest, a mid scarlet body, a black-sunk groove.
    val deepRed = lerp(p.primary, Color.Black, 0.55f)
    val midRed = p.primary
    val litRed = lerp(p.primary, Color(0xFFFF9FB2), 0.55f)
    val rim = lerp(p.secondary, Color.White, 0.35f)
    val faceTone = lerp(p.background, p.ink, 0.10f)

    val crimsonV = Brush.verticalGradient(
        colors = listOf(litRed, midRed, deepRed),
        startY = h * 0.02f, endY = h * 0.9f,
    )

    // The hair crown — a crimson mass arcing across the top, parting at the centre.
    val crown = Path().apply {
        moveTo(w * 0.15f, h * 0.36f)
        cubicTo(w * 0.10f, h * 0.09f, w * 0.32f, h * 0.01f, w * 0.5f, h * 0.02f)
        cubicTo(w * 0.68f, h * 0.01f, w * 0.90f, h * 0.09f, w * 0.85f, h * 0.36f)
        cubicTo(w * 0.74f, h * 0.19f, w * 0.62f, h * 0.17f, w * 0.5f, h * 0.19f)
        cubicTo(w * 0.38f, h * 0.17f, w * 0.26f, h * 0.19f, w * 0.15f, h * 0.36f)
        close()
    }
    drawPath(crown, crimsonV)

    // The suggested face between the curls.
    drawOval(
        faceTone,
        topLeft = Offset(w * 0.5f - w * 0.135f, h * 0.20f),
        size = Size(w * 0.27f, h * 0.23f),
    )

    // The bangs — a fringe with a soft centre part over the brow.
    val bangs = Path().apply {
        moveTo(w * 0.35f, h * 0.31f)
        cubicTo(w * 0.35f, h * 0.15f, w * 0.44f, h * 0.11f, w * 0.5f, h * 0.11f)
        cubicTo(w * 0.56f, h * 0.11f, w * 0.65f, h * 0.15f, w * 0.65f, h * 0.31f)
        cubicTo(w * 0.59f, h * 0.22f, w * 0.55f, h * 0.22f, w * 0.5f, h * 0.26f)
        cubicTo(w * 0.45f, h * 0.22f, w * 0.41f, h * 0.22f, w * 0.35f, h * 0.31f)
        close()
    }
    drawPath(bangs, crimsonV)

    // Two eye glints — a warm halo under a bright core.
    for (side in intArrayOf(-1, 1)) {
        val ex = w * 0.5f + side * w * 0.062f
        val ey = h * 0.305f
        drawCircle(p.primary.copy(alpha = 0.35f), radius = w * 0.022f, center = Offset(ex, ey))
        drawOval(
            lerp(p.secondary, Color.White, 0.25f),
            topLeft = Offset(ex - w * 0.013f, ey - h * 0.017f),
            size = Size(w * 0.026f, h * 0.034f),
        )
    }

    // The twin drill-curls, coiling down each flank as a stack of shaded, tapering spheres.
    for (side in intArrayOf(-1, 1)) {
        val coils = 8
        for (i in 0 until coils) {
            val t = i / (coils - 1f)
            val cy = h * (0.15f + 0.74f * t)
            val rad = w * (0.16f - 0.118f * t)
            val drift = sin(t.toDouble() * PI * 2.2).toFloat() * w * 0.045f * (1f - 0.5f * t)
            val cx = w * 0.5f + side * (w * 0.31f + drift)
            drawOval(
                Brush.radialGradient(
                    colors = listOf(litRed, midRed, deepRed),
                    center = Offset(cx - rad * 0.35f, cy - rad * 0.42f),
                    radius = rad * 1.7f,
                ),
                topLeft = Offset(cx - rad, cy - rad * 0.9f),
                size = Size(rad * 2f, rad * 1.8f),
            )
            drawArc(
                deepRed.copy(alpha = 0.55f),
                20f, 140f, false,
                topLeft = Offset(cx - rad * 0.96f, cy - rad * 0.8f),
                size = Size(rad * 1.92f, rad * 1.7f),
                style = Stroke(width = rad * 0.14f, cap = StrokeCap.Round),
            )
            drawArc(
                rim.copy(alpha = 0.3f),
                190f, 120f, false,
                topLeft = Offset(cx - rad * 0.9f, cy - rad * 0.85f),
                size = Size(rad * 1.8f, rad * 1.7f),
                style = Stroke(width = rad * 0.09f, cap = StrokeCap.Round),
            )
        }
    }
}