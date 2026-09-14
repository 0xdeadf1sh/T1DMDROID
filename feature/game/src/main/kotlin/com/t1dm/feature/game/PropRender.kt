package com.t1dm.feature.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.t1dm.ui.game.BIRD_SPEED_MS
import com.t1dm.ui.game.CLOUD_DRIFT_MS
import com.t1dm.ui.game.CLOUD_PARALLAX
import com.t1dm.ui.game.PropField
import com.t1dm.ui.game.PropKind
import com.t1dm.ui.game.PropSet
import com.t1dm.ui.game.propH
import com.t1dm.ui.game.propHalfW
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Units either side of the plot a glyph may reach into it; a blimp's sway plus tail is 47. */
private const val REACH_DP = 48f

/** Hours the sun is up, and when the stars are fully out; each fades over the hour beside it. */
private const val SUNRISE_H = 6f
private const val SUNSET_H = 18f
private const val STARS_OUT_H = 20.5f
private const val STARS_GONE_H = 5.5f
private const val STAR_COUNT = 24

private const val TAU = (2.0 * PI).toFloat()

/** Hoisted: `intArrayOf(-1, 1)` in a glyph is a fresh array on every draw of it. */
private val SIDES = intArrayOf(-1, 1)

/** Glyph unit in dp: a tree at 17 units stands ~44 dp, two thirds of the 64 dp golfer. */
private const val UNIT_DP = 2.6f

/** Reused draw state: a path, the sign labels laid out once, and the unit every size is in. */
class PropArt(
    private val measurer: TextMeasurer,
    private val labelStyle: TextStyle,
    /** Pixels per dp: prop sizes never track the zoom, like the golfer. */
    dpPx: Float,
) {
    val unitPx = dpPx * UNIT_DP
    val path = Path()

    /** Hoisted: a `Stroke(...)` in the draw is an allocation at 60 Hz, and [unitPx] never moves. */
    val hair = Stroke(width = 1f * unitPx)
    val thin = Stroke(width = 1.2f * unitPx)
    val mid = Stroke(width = 1.4f * unitPx)
    val ore = Stroke(width = 2f * unitPx, cap = StrokeCap.Round)
    private val labels = HashMap<Int, TextLayoutResult>()

    /** Sign [i]'s label, measured once; a handful per trace. */
    fun label(i: Int, text: String): TextLayoutResult =
        labels[i] ?: measurer.measure(text, labelStyle).also { labels[i] = it }
}

/** Everything behind the ground line: sun, moon, stars, clouds, birds, balloons, kites. */
internal fun DrawScope.drawSkyProps(
    props: PropSet,
    art: PropArt,
    skin: GameSkin,
    trackLength: Float,
    camLeft: Float,
    camWidth: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
    tS: Float,
    hour: Float,
    plotTop: Float,
    plotBottom: Float,
) {
    val u = art.unitPx
    val reach = REACH_DP * u / pxX
    // Not `size.width`: this scope is translated by plotLeft and clipped to the plot.
    drawSkyBodies(skin, u, hour, tS, camWidth * pxX, plotTop, plotBottom)

    // Clouds: half the camera's pace, so the sky sits behind the hills, and a slow drift downwind.
    val cloudLeft = CLOUD_PARALLAX * camLeft
    props.clouds.drifting(CLOUD_DRIFT_MS * tS, trackLength, cloudLeft - reach, cloudLeft + camWidth + reach) { i, xw ->
        drawCloud(skin, u, (xw - cloudLeft) * pxX, floorPx - props.clouds.ys[i] * pxY, props.clouds.seeds[i])
    }
    props.birds.drifting(-BIRD_SPEED_MS * tS, trackLength, camLeft - reach, camLeft + camWidth + reach) { i, xw ->
        val seed = props.birds.seeds[i]
        val bob = 3f * u * sin(tS * 0.9f + seed * TAU)
        drawFlock(skin, u, (xw - camLeft) * pxX, floorPx - props.birds.ys[i] * pxY + bob, seed, tS)
    }
    props.sky.visible(camLeft - reach, camLeft + camWidth + reach) { i ->
        val sx = (props.sky.xs[i] - camLeft) * pxX
        val sy = floorPx - props.sky.ys[i] * pxY
        val seed = props.sky.seeds[i]
        when (props.sky.kindAt(i)) {
            PropKind.Balloon -> drawBalloon(skin, art, sx, sy + 4f * u * sin(tS * 0.7f + seed * TAU), seed)
            PropKind.Blimp -> drawBlimp(skin, art, sx + 20f * u * sin(tS * 0.15f + seed * TAU), sy, seed)
            PropKind.Kite -> drawKite(skin, u, sx, sy, props.sky.amounts[i] * pxY, seed, tS)
            else -> Unit
        }
    }
}

/** Fossils, bones, pipes: under the trace in a buried ink, before the ground line goes on. */
internal fun DrawScope.drawUndergroundProps(
    props: PropSet,
    art: PropArt,
    skin: GameSkin,
    camLeft: Float,
    camWidth: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
    tS: Float,
) {
    val u = art.unitPx
    val f = props.underground
    f.visible(camLeft - GROUND_REACH_M, camLeft + camWidth + GROUND_REACH_M) { i ->
        val sx = (f.xs[i] - camLeft) * pxX
        val sy = floorPx - f.ys[i] * pxY
        val seed = f.seeds[i]
        val kind = f.kindAt(i)
        val w = 2f * propHalfW(kind, seed) * pxX
        val h = propH(kind, seed) * pxY
        when (kind) {
            PropKind.Fossil -> drawFossil(skin, art, sx, sy, w, h, seed)
            PropKind.Trilobite -> drawTrilobite(skin, art, sx, sy, w, h)
            PropKind.Skull -> drawSkull(skin, art, sx, sy, w, h, seed)
            PropKind.Ammonite -> drawAmmonite(skin, art, sx, sy, w, h)
            PropKind.Bone -> drawBone(skin, u, sx, sy, w, h, seed)
            PropKind.Pipe -> drawPipe(skin, art, sx, sy, w, h, seed, tS)
            PropKind.Manhole -> drawManhole(skin, u, sx, sy, w, h)
            PropKind.Vein -> drawVein(skin, art, sx, sy, w, h, seed)
            PropKind.Root -> drawRoots(skin, u, sx, sy, w, h)
            PropKind.Chest -> drawChest(skin, art, sx, sy, w, h)
            else -> Unit
        }
    }
}

/** Culling margin for ground and buried layers: a barn's roof reaches 1.1 × its 24 m half-width. */
private const val GROUND_REACH_M = 27f

/** Trees, rocks, a cabin, a windmill: WORLD-sized, so each fills the box its collider is. */
internal fun DrawScope.drawGroundProps(
    props: PropSet,
    art: PropArt,
    skin: GameSkin,
    camLeft: Float,
    camWidth: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
    tS: Float,
) {
    val u = art.unitPx
    val f = props.ground
    f.visible(camLeft - GROUND_REACH_M, camLeft + camWidth + GROUND_REACH_M) { i ->
        val sx = (f.xs[i] - camLeft) * pxX
        val sy = floorPx - f.ys[i] * pxY
        val seed = f.seeds[i]
        val kind = f.kindAt(i)
        val w = 2f * propHalfW(kind, seed) * pxX
        val h = propH(kind, seed) * pxY
        when (kind) {
            PropKind.Tree -> drawTree(skin, u, sx, sy, w, h)
            PropKind.Pine -> drawPine(skin, art.path, u, sx, sy, w, h)
            PropKind.Bush -> drawBush(skin, sx, sy, w, h, seed)
            PropKind.Barn -> drawBarn(skin, art.path, u, sx, sy, w, h, seed)
            PropKind.Tent -> drawTent(skin, art.path, u, sx, sy, w, h, seed)
            PropKind.WaterTower -> drawWaterTower(skin, art, sx, sy, w, h)
            PropKind.Tuft -> drawTuft(skin, u, sx, sy, w, h, seed, tS)
            PropKind.Cabin -> drawCabin(skin, art.path, u, sx, sy, w, h, tS)
            PropKind.Windmill -> drawWindmill(skin, art.path, u, sx, sy, w, h, seed, tS)
            PropKind.Scarecrow -> drawScarecrow(skin, art.path, u, sx, sy, w, h)
            PropKind.Birch -> drawBirch(skin, u, sx, sy, w, h)
            PropKind.Palm -> drawPalm(skin, u, sx, sy, w, h, seed)
            PropKind.Willow -> drawWillow(skin, u, sx, sy, w, h, seed, tS)
            PropKind.Grass -> drawGrass(skin, u, sx, sy, w, h, seed, tS)
            PropKind.Flowers -> drawFlowers(skin, u, sx, sy, w, h, seed, tS)
            else -> Unit
        }
    }
}

/** Road signs at every hypo nadir and hyper apex, over the ground line so they stand on it. */
internal fun DrawScope.drawSigns(
    props: PropSet,
    art: PropArt,
    skin: GameSkin,
    camLeft: Float,
    camWidth: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
) {
    val u = art.unitPx
    val f = props.signs
    f.visible(camLeft - GROUND_REACH_M, camLeft + camWidth + GROUND_REACH_M) { i ->
        val sx = (f.xs[i] - camLeft) * pxX
        val sy = floorPx - f.ys[i] * pxY
        val low = f.kindAt(i) == PropKind.SignLow
        val label = art.label(i, props.signLabels[i])
        // World-sized like the trees it stands among; the letters are scaled into the face.
        val postH = SIGN_POST_M * pxY
        val w = SIGN_FACE_W_M * pxX
        val h = SIGN_FACE_H_M * pxY
        drawLine(skin.figure, Offset(sx, sy), Offset(sx, sy - postH), 1f * u, StrokeCap.Round)
        val top = sy - postH - h
        val border = if (low) skin.signLow else skin.signHigh
        val textY: Float
        if (low) {
            // A warning triangle sits point-up, so the face is the wide base under the apex.
            art.path.rewind()
            art.path.moveTo(sx, top - h * 0.8f)
            art.path.lineTo(sx + w * 0.7f, top + h)
            art.path.lineTo(sx - w * 0.7f, top + h)
            art.path.close()
            drawPath(art.path, skin.signFace)
            drawPath(art.path, border, style = art.thin)
            textY = top + h * 0.55f
        } else {
            drawRect(skin.signFace, Offset(sx - w * 0.5f, top), Size(w, h))
            drawRect(border, Offset(sx - w * 0.5f, top), Size(w, h), style = art.thin)
            textY = top + h * 0.5f
        }
        val kx = w * 0.7f / label.size.width.coerceAtLeast(1)
        val ky = h * 0.7f / label.size.height.coerceAtLeast(1)
        scale(kx, ky, pivot = Offset(sx, textY)) {
            drawText(label, skin.signText, Offset(sx - label.size.width * 0.5f, textY - label.size.height * 0.5f))
        }
    }
}

/** Sign geometry, metres: a post and a face sized against the trees, not the screen. */
private const val SIGN_POST_M = 14f
private const val SIGN_FACE_W_M = 12f
private const val SIGN_FACE_H_M = 6f

/** Ascending scan of the world-x window; the lambda is inlined, so the loop allocates nothing. */
private inline fun PropField.visible(lo: Float, hi: Float, block: (i: Int) -> Unit) {
    var i = firstFrom(lo)
    while (i < size && xs[i] <= hi) {
        block(i)
        i++
    }
}

/** Same window with every x moved by [shift] and wrapped into `[0, length)`: two scans at most. */
private inline fun PropField.drifting(
    shift: Float,
    length: Float,
    lo: Float,
    hi: Float,
    block: (i: Int, xWorld: Float) -> Unit,
) {
    if (size == 0 || length <= 0f) return
    var a = lo - shift
    var b = hi - shift
    val k = floor(a / length)
    a -= k * length
    b -= k * length
    val back = shift + k * length
    visible(a, minOf(b, length)) { block(it, xs[it] + back) }
    if (b > length) visible(0f, b - length) { block(it, xs[it] + back + length) }
}

// Sky ------------------------------------------------------------------------------------------

private fun DrawScope.drawSkyBodies(
    skin: GameSkin, u: Float, hour: Float, tS: Float, plotW: Float, plotTop: Float, plotBottom: Float,
) {
    if (!hour.isFinite()) return
    val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
    val day = hour in SUNRISE_H..SUNSET_H
    // Both bodies cross the same arc: in at the left, over the top at noon or midnight.
    val phase = if (day) (hour - SUNRISE_H) / (SUNSET_H - SUNRISE_H) else ((hour + 6f) % 24f) / 12f
    val bx = plotW * (0.08f + 0.84f * phase)
    val by = plotTop + plotH * (0.30f - 0.22f * sin(phase * PI.toFloat()))
    if (day) {
        drawCircle(skin.sun, 9f * u, Offset(bx, by))
        for (k in 0 until 8) {
            val a = k * TAU / 8f + tS * 0.05f
            drawLine(
                skin.sun,
                Offset(bx + cos(a) * 12f * u, by + sin(a) * 12f * u),
                Offset(bx + cos(a) * 16f * u, by + sin(a) * 16f * u),
                1.5f * u,
                StrokeCap.Round,
            )
        }
    } else {
        drawCircle(skin.moon, 8f * u, Offset(bx, by))
        drawCircle(skin.sky, 7f * u, Offset(bx + 4f * u, by - 2f * u))
    }
    val night = when {
        hour >= STARS_OUT_H -> (hour - STARS_OUT_H).coerceIn(0f, 1f)
        hour <= STARS_GONE_H -> (STARS_GONE_H - hour).coerceIn(0f, 1f)
        else -> 0f
    }
    if (night > 0f) {
        for (k in 0 until STAR_COUNT) {
            // A fixed constellation: golden-ratio stepping spreads the stars without a table.
            val fx = (k * 0.6180339f) % 1f
            val fy = ((k * 0.7548777f) % 1f) * 0.45f
            val twinkle = 0.55f + 0.45f * sin(tS * 2f + k * 1.7f)
            drawCircle(
                skin.star.copy(alpha = skin.star.alpha * night * twinkle),
                1.2f * u,
                Offset(plotW * fx, plotTop + plotH * fy),
            )
        }
    }
}

/** A second draw of the seed, decorrelated from the size it also sets, picks a shape variant. */
private fun variant(seed: Float, n: Int): Int = ((seed * 7.31f) % 1f * n).toInt().coerceIn(0, n - 1)

private fun DrawScope.drawCloud(skin: GameSkin, u: Float, x: Float, y: Float, seed: Float) {
    val s = u * (0.7f + 0.7f * seed)
    when (variant(seed, 3)) {
        // Cumulus: three domes on a flat base.
        0 -> {
            drawCircle(skin.cloud, 7f * s, Offset(x - 7f * s, y + 1f * s))
            drawCircle(skin.cloud, 10f * s, Offset(x, y - 3f * s))
            drawCircle(skin.cloud, 7f * s, Offset(x + 8f * s, y + 1f * s))
            drawRect(skin.cloud, Offset(x - 12f * s, y + 1f * s), Size(25f * s, 5f * s))
        }
        // Stratus: a long low streak.
        1 -> {
            drawOval(skin.cloud, Offset(x - 18f * s, y - 3f * s), Size(36f * s, 6f * s))
            drawOval(skin.cloud, Offset(x - 8f * s, y - 6f * s), Size(20f * s, 6f * s))
        }
        // A tower: domes stacked, the top one smallest.
        else -> {
            drawCircle(skin.cloud, 9f * s, Offset(x, y + 1f * s))
            drawCircle(skin.cloud, 7f * s, Offset(x - 5f * s, y - 6f * s))
            drawCircle(skin.cloud, 7f * s, Offset(x + 5f * s, y - 7f * s))
            drawCircle(skin.cloud, 5f * s, Offset(x + 1f * s, y - 13f * s))
            drawRect(skin.cloud, Offset(x - 10f * s, y + 1f * s), Size(20f * s, 5f * s))
        }
    }
}

private fun DrawScope.drawBlimp(skin: GameSkin, art: PropArt, x: Float, y: Float, seed: Float) {
    val u = art.unitPx
    val s = u * (0.8f + 0.5f * seed)
    drawOval(skin.balloon, Offset(x - 18f * s, y - 6f * s), Size(36f * s, 12f * s))
    drawOval(skin.balloonBand, Offset(x - 18f * s, y - 6f * s), Size(36f * s, 12f * s), style = art.thin)
    drawLine(skin.balloonBand, Offset(x - 18f * s, y), Offset(x + 18f * s, y), 1f * u)
    // Tail fins and the gondola slung below the hull.
    drawLine(skin.balloonBand, Offset(x - 15f * s, y - 3f * s), Offset(x - 21f * s, y - 9f * s), 2f * u, StrokeCap.Round)
    drawLine(skin.balloonBand, Offset(x - 15f * s, y + 3f * s), Offset(x - 21f * s, y + 9f * s), 2f * u, StrokeCap.Round)
    drawRect(skin.wood, Offset(x - 4f * s, y + 6f * s), Size(8f * s, 3f * s))
}

private fun DrawScope.drawFlock(skin: GameSkin, u: Float, x: Float, y: Float, seed: Float, tS: Float) {
    val n = 3 + (seed * 3f).toInt()
    val flap = 0.35f + 0.45f * sin(tS * 7f + seed * TAU)
    for (k in 0 until n) {
        // A V: the leader ahead, the rest trailing behind and out on alternate sides.
        val row = (k + 1) / 2
        val side = if (k % 2 == 0) 1f else -1f
        val bx = x + row * 9f * u
        val by = if (k == 0) y else y + side * row * 5f * u
        val wing = 4f * u
        val lift = wing * flap
        drawLine(skin.bird, Offset(bx - wing, by - lift), Offset(bx, by), 1.4f * u, StrokeCap.Round)
        drawLine(skin.bird, Offset(bx, by), Offset(bx + wing, by - lift), 1.4f * u, StrokeCap.Round)
    }
}

private fun DrawScope.drawBalloon(skin: GameSkin, art: PropArt, x: Float, y: Float, seed: Float) {
    val u = art.unitPx
    val r = 8f * u
    drawCircle(skin.balloon, r, Offset(x, y))
    drawCircle(skin.balloonBand, r, Offset(x, y), style = art.thin)
    drawLine(skin.balloonBand, Offset(x, y - r), Offset(x, y + r), 1f * u)
    val basketY = y + r + 7f * u
    drawLine(skin.figure, Offset(x - 3f * u, y + r * 0.8f), Offset(x - 2f * u, basketY), 1f * u)
    drawLine(skin.figure, Offset(x + 3f * u, y + r * 0.8f), Offset(x + 2f * u, basketY), 1f * u)
    drawRect(skin.wood, Offset(x - 3f * u, basketY), Size(6f * u, 4f * u))
}

private fun DrawScope.drawKite(skin: GameSkin, u: Float, x: Float, y: Float, stringPx: Float, seed: Float, tS: Float) {
    val sway = 6f * u * sin(tS * 1.3f + seed * TAU)
    val kx = x + sway
    // The string leaves the ground where the kite is anchored, so a gust only bends it.
    drawLine(skin.string, Offset(x, y + stringPx), Offset(kx, y + 6f * u), 1f * u)
    drawLine(skin.kite, Offset(kx, y - 8f * u), Offset(kx + 6f * u, y), 2f * u, StrokeCap.Round)
    drawLine(skin.kite, Offset(kx + 6f * u, y), Offset(kx, y + 6f * u), 2f * u, StrokeCap.Round)
    drawLine(skin.kite, Offset(kx, y + 6f * u), Offset(kx - 6f * u, y), 2f * u, StrokeCap.Round)
    drawLine(skin.kite, Offset(kx - 6f * u, y), Offset(kx, y - 8f * u), 2f * u, StrokeCap.Round)
    var tx = kx
    var ty = y + 6f * u
    for (k in 0 until 3) {
        val nx = tx + 3f * u * (if (k % 2 == 0) -1f else 1f) + sway * 0.2f
        val ny = ty + 4f * u
        drawLine(skin.kite, Offset(tx, ty), Offset(nx, ny), 1.2f * u, StrokeCap.Round)
        tx = nx
        ty = ny
    }
}

// Underground ----------------------------------------------------------------------------------

// Buried glyphs fill a w × h box centred on (x, y); a manhole or root hangs from the ground at y.

private fun DrawScope.drawFossil(skin: GameSkin, art: PropArt, x: Float, y: Float, w: Float, h: Float, seed: Float) {
    val s = 1.4f * art.unitPx
    // Facing either way, with a ribcage of five or a long-spined eight.
    val face = if (variant(seed, 2) == 0) 1f else -1f
    val ribs = if (variant(seed, 3) == 2) 8 else 5
    val step = w * 0.6f / ribs
    drawLine(skin.buried, Offset(x - face * w * 0.36f, y), Offset(x + face * w * 0.3f, y), s, StrokeCap.Round)
    for (k in 0 until ribs) {
        val rx = x - face * (w * 0.25f - k * step)
        drawLine(skin.buried, Offset(rx, y), Offset(rx - face * w * 0.04f, y - h * 0.45f), s, StrokeCap.Round)
        drawLine(skin.buried, Offset(rx, y), Offset(rx - face * w * 0.04f, y + h * 0.45f), s, StrokeCap.Round)
    }
    val headX = if (face > 0f) x + w * 0.3f else x - w * 0.5f
    drawOval(skin.buried, Offset(headX, y - h * 0.3f), Size(w * 0.2f, h * 0.6f), style = art.mid)
    drawLine(skin.buried, Offset(x - face * w * 0.36f, y), Offset(x - face * w * 0.5f, y - h * 0.4f), s, StrokeCap.Round)
    drawLine(skin.buried, Offset(x - face * w * 0.36f, y), Offset(x - face * w * 0.5f, y + h * 0.4f), s, StrokeCap.Round)
}

private fun DrawScope.drawTrilobite(skin: GameSkin, art: PropArt, x: Float, y: Float, w: Float, h: Float) {
    val u = art.unitPx
    drawOval(skin.buried, Offset(x - w * 0.5f, y - h * 0.5f), Size(w, h), style = art.mid)
    drawOval(skin.buried, Offset(x - w * 0.4f, y - h * 0.5f), Size(w * 0.8f, h * 0.3f), style = art.mid)
    // Segments across the thorax, and a spine down the middle.
    for (k in 1..5) {
        val ry = y - h * 0.2f + k * h * 0.12f
        val half = w * 0.5f * kotlin.math.sqrt(1f - ((ry - y) / (h * 0.5f)).let { it * it }.coerceIn(0f, 1f))
        drawLine(skin.buried, Offset(x - half, ry), Offset(x + half, ry), 1f * u)
    }
    drawLine(skin.buried, Offset(x, y - h * 0.2f), Offset(x, y + h * 0.5f), 1f * u)
}

private fun DrawScope.drawSkull(skin: GameSkin, art: PropArt, x: Float, y: Float, w: Float, h: Float, seed: Float) {
    val u = art.unitPx
    // A long jaw one way, or a blunt dome: two kinds of skull.
    val long = variant(seed, 2) == 0
    if (long) {
        drawOval(skin.buried, Offset(x - w * 0.5f, y - h * 0.5f), Size(w * 0.6f, h), style = art.mid)
        drawRect(skin.buried, Offset(x + w * 0.05f, y - h * 0.1f), Size(w * 0.45f, h * 0.5f), style = art.mid)
        for (k in 0 until 4) {
            val tx = x + w * 0.1f + k * w * 0.1f
            drawLine(skin.buried, Offset(tx, y + h * 0.4f), Offset(tx, y + h * 0.25f), 1f * u)
        }
        drawCircle(skin.buried, h * 0.12f, Offset(x - w * 0.2f, y - h * 0.15f), style = art.hair)
    } else {
        drawOval(skin.buried, Offset(x - w * 0.45f, y - h * 0.5f), Size(w * 0.9f, h * 0.85f), style = art.mid)
        drawCircle(skin.buried, h * 0.13f, Offset(x - w * 0.15f, y - h * 0.1f), style = art.hair)
        drawCircle(skin.buried, h * 0.13f, Offset(x + w * 0.15f, y - h * 0.1f), style = art.hair)
        drawRect(skin.buried, Offset(x - w * 0.25f, y + h * 0.25f), Size(w * 0.5f, h * 0.25f), style = art.mid)
        for (k in -1..1) {
            drawLine(skin.buried, Offset(x + k * w * 0.12f, y + h * 0.25f), Offset(x + k * w * 0.12f, y + h * 0.5f), 1f * u)
        }
    }
}

private fun DrawScope.drawAmmonite(skin: GameSkin, art: PropArt, x: Float, y: Float, w: Float, h: Float) {
    // Four half-turns, each tighter than the last, alternating sides: a spiral in arcs.
    var rx = w * 0.5f
    var ry = h * 0.5f
    var cx = x
    var start = 180f
    for (k in 0 until 4) {
        drawArc(skin.buried, start, 180f, false, Offset(cx - rx, y - ry), Size(2f * rx, 2f * ry), style = art.mid)
        val nx = rx * 0.62f
        cx += if (k % 2 == 0) (rx - nx) else -(rx - nx)
        rx = nx
        ry *= 0.62f
        start += 180f
    }
}

private fun DrawScope.drawBone(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float) {
    rotate(-25f + 50f * seed, Offset(x, y)) {
        if (variant(seed, 2) == 0) {
            drawLine(skin.buried, Offset(x - w * 0.4f, y), Offset(x + w * 0.4f, y), h * 0.3f, StrokeCap.Round)
            for (side in SIDES) {
                drawOval(skin.buried, Offset(x + side * w * 0.4f - w * 0.1f, y - h * 0.5f), Size(w * 0.2f, h * 0.55f))
                drawOval(skin.buried, Offset(x + side * w * 0.4f - w * 0.1f, y - h * 0.05f), Size(w * 0.2f, h * 0.55f))
            }
        } else {
            // A long bone: a shaft with one flared end and one ball end.
            drawLine(skin.buried, Offset(x - w * 0.45f, y), Offset(x + w * 0.4f, y), h * 0.22f, StrokeCap.Round)
            drawOval(skin.buried, Offset(x + w * 0.3f, y - h * 0.3f), Size(w * 0.2f, h * 0.6f))
            drawLine(skin.buried, Offset(x - w * 0.45f, y), Offset(x - w * 0.5f, y - h * 0.4f), h * 0.2f, StrokeCap.Round)
            drawLine(skin.buried, Offset(x - w * 0.45f, y), Offset(x - w * 0.5f, y + h * 0.4f), h * 0.2f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawPipe(skin: GameSkin, art: PropArt, x: Float, y: Float, w: Float, h: Float, seed: Float, tS: Float) {
    val u = art.unitPx
    val half = w * 0.5f
    drawRect(skin.buried, Offset(x - half, y - h * 0.5f), Size(w, h), style = art.mid)
    for (side in SIDES) {
        drawRect(skin.buried, Offset(x + side * half - w * 0.02f, y - h * 0.7f), Size(w * 0.04f, h * 1.4f))
    }
    // Flow: ticks marching along the bore.
    val step = w * 0.12f
    var tx = x - half + w * 0.05f + (tS * w * 0.08f + seed * step) % step
    while (tx < x + half - w * 0.05f) {
        drawLine(skin.water, Offset(tx, y + h * 0.15f), Offset(tx + w * 0.04f, y + h * 0.15f), 1.5f * u, StrokeCap.Round)
        tx += step
    }
}

private fun DrawScope.drawManhole(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float) {
    drawLine(skin.buried, Offset(x - w * 0.3f, y), Offset(x - w * 0.3f, y + h), 1.4f * u)
    drawLine(skin.buried, Offset(x + w * 0.3f, y), Offset(x + w * 0.3f, y + h), 1.4f * u)
    for (k in 1..3) {
        val ry = y + k * h * 0.25f
        drawLine(skin.buried, Offset(x - w * 0.3f, ry), Offset(x + w * 0.3f, ry), 1.2f * u)
    }
    drawOval(skin.stone, Offset(x - w * 0.5f, y - h * 0.08f), Size(w, h * 0.16f))
}

private fun DrawScope.drawVein(skin: GameSkin, art: PropArt, x: Float, y: Float, w: Float, h: Float, seed: Float) {
    val path = art.path
    path.rewind()
    var px = x - w * 0.5f
    var py = y
    path.moveTo(px, py)
    for (k in 0 until 6) {
        px += w / 6f
        py += (if (k % 2 == 0) -1f else 1f) * h * (0.2f + 0.3f * seed)
        path.lineTo(px, py)
    }
    drawPath(path, skin.ore, style = art.ore)
}

private fun DrawScope.drawRoots(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float) {
    val s = 1.4f * u
    drawLine(skin.buried, Offset(x, y), Offset(x - w * 0.35f, y + h * 0.8f), s, StrokeCap.Round)
    drawLine(skin.buried, Offset(x, y), Offset(x, y + h), s, StrokeCap.Round)
    drawLine(skin.buried, Offset(x, y), Offset(x + w * 0.35f, y + h * 0.8f), s, StrokeCap.Round)
    drawLine(skin.buried, Offset(x - w * 0.17f, y + h * 0.4f), Offset(x - w * 0.5f, y + h * 0.35f), s, StrokeCap.Round)
    drawLine(skin.buried, Offset(x + w * 0.17f, y + h * 0.4f), Offset(x + w * 0.5f, y + h * 0.35f), s, StrokeCap.Round)
}

private fun DrawScope.drawChest(skin: GameSkin, art: PropArt, x: Float, y: Float, w: Float, h: Float) {
    val box = h * 0.7f
    drawRect(skin.wood, Offset(x - w * 0.5f, y - h * 0.2f), Size(w, box))
    drawRect(skin.buried, Offset(x - w * 0.5f, y - h * 0.2f), Size(w, box), style = art.mid)
    drawArc(skin.buried, 180f, 180f, false, Offset(x - w * 0.5f, y - h * 0.5f), Size(w, h * 0.6f), style = art.mid)
    drawCircle(skin.ore, 1.6f * art.unitPx, Offset(x, y + h * 0.15f))
}

// Ground ---------------------------------------------------------------------------------------

// Each glyph fills the w × h box its collider is; x is the box's centre, y its ground line.

private fun DrawScope.drawTree(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float) {
    drawRect(skin.wood, Offset(x - w * 0.09f, y - h * 0.55f), Size(w * 0.18f, h * 0.55f))
    drawOval(skin.leaf, Offset(x - w * 0.5f, y - h * 0.78f), Size(w * 0.62f, h * 0.44f))
    drawOval(skin.leaf, Offset(x - w * 0.12f, y - h * 0.78f), Size(w * 0.62f, h * 0.44f))
    drawOval(skin.leaf, Offset(x - w * 0.34f, y - h), Size(w * 0.68f, h * 0.5f))
}

private fun DrawScope.drawPine(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float) {
    drawRect(skin.wood, Offset(x - w * 0.07f, y - h * 0.3f), Size(w * 0.14f, h * 0.3f))
    var base = y - h * 0.22f
    var half = w * 0.5f
    for (k in 0 until 3) {
        path.rewind()
        path.moveTo(x, base - h * 0.42f)
        path.lineTo(x + half, base)
        path.lineTo(x - half, base)
        path.close()
        drawPath(path, skin.leaf)
        base -= h * 0.18f
        half *= 0.72f
    }
}

private fun DrawScope.drawBirch(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float) {
    val trunkW = w * 0.24f
    drawRect(skin.birch, Offset(x - trunkW * 0.5f, y - h * 0.62f), Size(trunkW, h * 0.62f))
    for (k in 0 until 5) {
        val ty = y - h * (0.08f + k * 0.11f)
        drawLine(skin.figure, Offset(x - trunkW * 0.5f, ty), Offset(x + trunkW * (if (k % 2 == 0) 0.1f else -0.2f), ty), 1f * u)
    }
    drawOval(skin.leaf, Offset(x - w * 0.5f, y - h * 0.82f), Size(w * 0.55f, h * 0.36f))
    drawOval(skin.leaf, Offset(x - w * 0.05f, y - h * 0.82f), Size(w * 0.55f, h * 0.36f))
    drawOval(skin.leaf, Offset(x - w * 0.3f, y - h), Size(w * 0.6f, h * 0.4f))
}

private fun DrawScope.drawPalm(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float) {
    val lean = w * (0.1f + 0.15f * seed)
    val topX = x + lean
    val topY = y - h * 0.72f
    // The trunk bends: three segments sway from the foot to the crown.
    drawLine(skin.wood, Offset(x, y), Offset(x + lean * 0.3f, y - h * 0.3f), w * 0.12f, StrokeCap.Round)
    drawLine(skin.wood, Offset(x + lean * 0.3f, y - h * 0.3f), Offset(x + lean * 0.75f, y - h * 0.55f), w * 0.1f, StrokeCap.Round)
    drawLine(skin.wood, Offset(x + lean * 0.75f, y - h * 0.55f), Offset(topX, topY), w * 0.08f, StrokeCap.Round)
    for (k in 0 until 6) {
        val a = -2.6f + k * 0.52f
        val ex = topX + cos(a) * w * 0.5f
        val ey = topY + sin(a) * h * 0.28f + h * 0.05f
        drawLine(skin.leaf, Offset(topX, topY), Offset(ex, ey), 2f * u, StrokeCap.Round)
        drawLine(skin.leaf, Offset(ex, ey), Offset(ex + cos(a) * w * 0.05f, ey + h * 0.06f), 2f * u, StrokeCap.Round)
    }
    drawCircle(skin.wood, 1.6f * u, Offset(topX - w * 0.04f, topY + h * 0.03f))
    drawCircle(skin.wood, 1.6f * u, Offset(topX + w * 0.04f, topY + h * 0.03f))
}

private fun DrawScope.drawWillow(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float, tS: Float) {
    drawRect(skin.wood, Offset(x - w * 0.07f, y - h * 0.55f), Size(w * 0.14f, h * 0.55f))
    drawOval(skin.leaf, Offset(x - w * 0.45f, y - h), Size(w * 0.9f, h * 0.55f))
    // Fronds hang from the crown's rim and swing together in the wind.
    val sway = w * 0.03f * sin(tS * 1.1f + seed * TAU)
    for (k in 0 until 9) {
        val fx = x - w * 0.4f + k * w * 0.1f
        val top = y - h * 0.72f
        drawLine(skin.leaf, Offset(fx, top), Offset(fx + sway, y - h * (0.12f + 0.12f * ((k * 7) % 3))), 1.6f * u, StrokeCap.Round)
    }
}

private fun DrawScope.drawGrass(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float, tS: Float) {
    val lean = w * 0.12f * sin(tS * 1.9f + seed * TAU)
    for (k in -2..2) {
        val bx = x + k * w * 0.2f
        drawLine(skin.leaf, Offset(bx, y), Offset(bx + k * w * 0.06f + lean, y - h * (0.7f + 0.3f * ((k + 2) % 2))), 1.2f * u, StrokeCap.Round)
    }
}

private fun DrawScope.drawFlowers(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float, tS: Float) {
    val lean = w * 0.06f * sin(tS * 1.4f + seed * TAU)
    for (k in -1..1) {
        val sx = x + k * w * 0.3f
        val top = y - h * (0.75f + 0.25f * ((k + 1) % 2))
        drawLine(skin.leaf, Offset(sx, y), Offset(sx + lean, top), 1.2f * u, StrokeCap.Round)
        val ink = if ((k + seed * 3f).toInt() % 2 == 0) skin.petal else skin.petalAlt
        val r = h * 0.14f
        for (p in 0 until 5) {
            val a = p * TAU / 5f
            drawCircle(ink, r, Offset(sx + lean + cos(a) * r * 1.2f, top + sin(a) * r * 1.2f))
        }
        drawCircle(skin.straw, r * 0.8f, Offset(sx + lean, top))
    }
}

private fun DrawScope.drawBush(skin: GameSkin, x: Float, y: Float, w: Float, h: Float, seed: Float) {
    when (variant(seed, 3)) {
        0 -> {
            drawOval(skin.leaf, Offset(x - w * 0.5f, y - h * 0.8f), Size(w * 0.5f, h * 0.8f))
            drawOval(skin.leaf, Offset(x, y - h * 0.8f), Size(w * 0.5f, h * 0.8f))
            drawOval(skin.leaf, Offset(x - w * 0.3f, y - h), Size(w * 0.6f, h))
        }
        // A hedge: one long low mound.
        1 -> drawOval(skin.leaf, Offset(x - w * 0.5f, y - h), Size(w, h * 1.6f))
        // A berry bush: the mound with fruit.
        else -> {
            drawOval(skin.leaf, Offset(x - w * 0.4f, y - h), Size(w * 0.8f, h * 1.6f))
            for (k in 0 until 4) {
                drawCircle(skin.petal, h * 0.1f, Offset(x - w * 0.25f + k * w * 0.17f, y - h * (0.35f + 0.3f * (k % 2))))
            }
        }
    }
}

private fun DrawScope.drawTuft(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float, tS: Float) {
    val lean = w * 0.2f * sin(tS * 1.7f + seed * TAU)
    for (k in -1..1) {
        drawLine(skin.leaf, Offset(x + k * w * 0.2f, y), Offset(x + k * w * 0.45f + lean, y - h), 1.2f * u, StrokeCap.Round)
    }
}

private fun DrawScope.drawBarn(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float) {
    val wall = h * 0.65f
    drawRect(skin.roof, Offset(x - w * 0.5f, y - wall), Size(w, wall))
    // A gambrel roof: two pitches a side, meeting at the ridge.
    path.rewind()
    path.moveTo(x - w * 0.55f, y - wall)
    path.lineTo(x - w * 0.42f, y - h * 0.88f)
    path.lineTo(x, y - h)
    path.lineTo(x + w * 0.42f, y - h * 0.88f)
    path.lineTo(x + w * 0.55f, y - wall)
    path.close()
    drawPath(path, skin.wood)
    val doorW = w * 0.22f
    drawRect(skin.figure, Offset(x - doorW * 0.5f, y - wall * 0.6f), Size(doorW, wall * 0.6f))
    drawLine(skin.signFace, Offset(x - doorW * 0.5f, y - wall * 0.6f), Offset(x + doorW * 0.5f, y), 1f * u)
    drawLine(skin.signFace, Offset(x + doorW * 0.5f, y - wall * 0.6f), Offset(x - doorW * 0.5f, y), 1f * u)
    if (variant(seed, 2) == 0) drawRect(skin.window, Offset(x - w * 0.05f, y - wall * 0.9f), Size(w * 0.1f, wall * 0.15f))
    else drawCircle(skin.window, w * 0.05f, Offset(x, y - wall * 0.85f))
}

private fun DrawScope.drawTent(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float) {
    val ink = if (variant(seed, 2) == 0) skin.cloth else skin.kite
    path.rewind()
    path.moveTo(x - w * 0.5f, y)
    path.lineTo(x, y - h)
    path.lineTo(x + w * 0.5f, y)
    path.close()
    drawPath(path, ink)
    // The flap: a darker inner triangle for the open door.
    path.rewind()
    path.moveTo(x - w * 0.12f, y)
    path.lineTo(x, y - h * 0.5f)
    path.lineTo(x + w * 0.12f, y)
    path.close()
    drawPath(path, skin.figure)
    drawLine(skin.wood, Offset(x, y - h), Offset(x, y - h * 1.12f), 1.2f * u, StrokeCap.Round)
}

private fun DrawScope.drawWaterTower(skin: GameSkin, art: PropArt, x: Float, y: Float, w: Float, h: Float) {
    val u = art.unitPx
    val legTop = y - h * 0.6f
    for (side in SIDES) {
        drawLine(skin.wood, Offset(x + side * w * 0.45f, y), Offset(x + side * w * 0.3f, legTop), 1.6f * u, StrokeCap.Round)
    }
    drawLine(skin.wood, Offset(x - w * 0.4f, y - h * 0.2f), Offset(x + w * 0.4f, y - h * 0.2f), 1.2f * u)
    drawLine(skin.wood, Offset(x - w * 0.35f, y - h * 0.4f), Offset(x + w * 0.35f, y - h * 0.4f), 1.2f * u)
    drawLine(skin.wood, Offset(x - w * 0.4f, y - h * 0.2f), Offset(x + w * 0.35f, y - h * 0.4f), 1f * u)
    drawLine(skin.wood, Offset(x + w * 0.4f, y - h * 0.2f), Offset(x - w * 0.35f, y - h * 0.4f), 1f * u)
    drawRect(skin.stone, Offset(x - w * 0.5f, y - h * 0.92f), Size(w, h * 0.32f))
    drawRect(skin.figure, Offset(x - w * 0.5f, y - h * 0.92f), Size(w, h * 0.32f), style = art.hair)
    drawLine(skin.figure, Offset(x - w * 0.5f, y - h * 0.76f), Offset(x + w * 0.5f, y - h * 0.76f), 1f * u)
    drawOval(skin.roof, Offset(x - w * 0.55f, y - h), Size(w * 1.1f, h * 0.14f))
}

private fun DrawScope.drawCabin(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float, tS: Float) {
    val wall = h * 0.6f
    drawRect(skin.wood, Offset(x - w * 0.5f, y - wall), Size(w, wall))
    path.rewind()
    path.moveTo(x - w * 0.58f, y - wall)
    path.lineTo(x, y - h)
    path.lineTo(x + w * 0.58f, y - wall)
    path.close()
    drawPath(path, skin.roof)
    drawRect(skin.figure, Offset(x - w * 0.1f, y - wall * 0.55f), Size(w * 0.2f, wall * 0.55f))
    drawRect(skin.window, Offset(x + w * 0.18f, y - wall * 0.75f), Size(w * 0.2f, wall * 0.3f))
    drawRect(skin.stone, Offset(x + w * 0.22f, y - h * 0.98f), Size(w * 0.14f, h * 0.3f))
    // Smoke: three puffs rising on a three-second loop, growing as they thin out.
    for (k in 0 until 3) {
        val t = ((tS + k * 1f) % 3f) / 3f
        drawCircle(
            skin.smoke.copy(alpha = (1f - t) * 0.6f),
            (1.5f + 3f * t) * u,
            Offset(x + w * 0.29f + w * 0.2f * t, y - h - 2f * u - h * 0.35f * t),
        )
    }
}

private fun DrawScope.drawWindmill(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float, tS: Float) {
    val tower = h * 0.9f
    path.rewind()
    path.moveTo(x - w * 0.5f, y)
    path.lineTo(x + w * 0.5f, y)
    path.lineTo(x + w * 0.22f, y - tower)
    path.lineTo(x - w * 0.22f, y - tower)
    path.close()
    drawPath(path, skin.stone)
    val hubY = y - tower
    val spin = tS * (0.6f + 0.6f * seed)
    val blade = h * 0.5f
    for (k in 0 until 4) {
        val a = spin + k * TAU / 4f
        drawLine(skin.figure, Offset(x, hubY), Offset(x + cos(a) * blade, hubY + sin(a) * blade), 1.6f * u, StrokeCap.Round)
        // Each sail: a slat off the blade's leading edge.
        val bx = x + cos(a) * blade * 0.7f
        val by = hubY + sin(a) * blade * 0.7f
        drawLine(skin.cloth, Offset(bx, by), Offset(bx - sin(a) * blade * 0.25f, by + cos(a) * blade * 0.25f), 3f * u)
    }
    drawCircle(skin.figure, 1.6f * u, Offset(x, hubY))
}

private fun DrawScope.drawScarecrow(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float) {
    drawLine(skin.wood, Offset(x, y), Offset(x, y - h * 0.75f), 1.6f * u, StrokeCap.Round)
    drawLine(skin.wood, Offset(x - w * 0.5f, y - h * 0.55f), Offset(x + w * 0.5f, y - h * 0.55f), 1.6f * u, StrokeCap.Round)
    path.rewind()
    path.moveTo(x - w * 0.36f, y - h * 0.18f)
    path.lineTo(x - w * 0.22f, y - h * 0.55f)
    path.lineTo(x + w * 0.22f, y - h * 0.55f)
    path.lineTo(x + w * 0.36f, y - h * 0.18f)
    path.close()
    drawPath(path, skin.cloth)
    drawOval(skin.straw, Offset(x - w * 0.22f, y - h * 0.9f), Size(w * 0.44f, h * 0.16f))
    drawLine(skin.figure, Offset(x - w * 0.34f, y - h * 0.9f), Offset(x + w * 0.34f, y - h * 0.9f), 1.4f * u, StrokeCap.Round)
    drawRect(skin.figure, Offset(x - w * 0.18f, y - h), Size(w * 0.36f, h * 0.1f))
}
